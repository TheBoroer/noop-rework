import Foundation
import Shared

/// Per-second heart rate derived from the WHOOP 5.0 type-47 **v26** optical PPG buffer (issue #156).
///
/// On v26-heavy stretches of a night the strap records the 24 Hz optical PPG waveform instead of the
/// v18 per-second summary that carries HR. The v26 records are real cardiac signal, so this pure
/// estimator recovers a per-second HR from them via windowed autocorrelation, keeping the biometric
/// timeline continuous through the gaps. It is HR-only — PPG carries NO body motion, so this fills HR
/// continuity, NOT actigraphy and NOT HRV (the contributor confirmed v26 gives no RMSSD).
///
/// Mirrors `tools/linux-capture/ppg_hr.py` (PR #162, Python side): 8 s autocorrelation window, a
/// 30–220 bpm search band (lags 6…48 at 24 Hz), linear detrend, normalised peak as confidence, and a
/// fundamental-period preference so the harmonic peaks at 2×/3× the true period don't report half/third
/// the real rate. Pure + Foundation-only so it is unit-testable from synthetic and captured waveforms.

public struct PpgHrSample: Equatable, Codable, Sendable {
    public let ts: Int          // wall-clock unix seconds (one estimate per second)
    public let bpm: Int         // PPG-derived heart rate, whole bpm — the same Int domain as the
                                // measured HRSample.bpm and the Android PpgHr.Estimate.bpm, so the
                                // stored value and type match across platforms (#219).
    public let conf: Double     // normalised autocorrelation peak behind `bpm` (0…1)
    public init(ts: Int, bpm: Int, conf: Double) {
        self.ts = ts; self.bpm = bpm; self.conf = conf
    }
}

public enum PpgHr {
    // Values of record: the shared Kotlin `com.noop.protocol.PpgHr` constants, read once here so the
    // Swift tunable-estimator defaults and the Kotlin pipeline can never drift.
    public static let sampleRateHz = Int(Shared.PpgHr.shared.SAMPLE_RATE_HZ)  // v26: 24 samples per 1-second record
    public static let windowSeconds = Int(Shared.PpgHr.shared.WINDOW_SECONDS) // autocorrelation window (stable at low HR)
    public static let hrLoBpm = Shared.PpgHr.shared.MIN_BPM
    public static let hrHiBpm = Shared.PpgHr.shared.MAX_BPM
    public static let minConfidence = Shared.PpgHr.shared.MIN_CONFIDENCE     // reject a window whose best peak is weaker

    /// Linear-detrend a waveform: subtract the least-squares best-fit line to remove DC + baseline
    /// wander (slow respiration/perfusion drift) before the autocorrelation, so the pulse dominates.
    static func detrend(_ x: [Double]) -> [Double] {
        let n = x.count
        guard n > 1 else { return x.map { _ in 0 } }
        let nD = Double(n)
        // x-axis is the sample index 0…n-1; closed-form slope/intercept of the LS line.
        let sumI = nD * (nD - 1) / 2
        let sumI2 = (nD - 1) * nD * (2 * nD - 1) / 6
        var sumY = 0.0, sumIY = 0.0
        for (i, y) in x.enumerated() { let id = Double(i); sumY += y; sumIY += id * y }
        let denom = nD * sumI2 - sumI * sumI
        guard denom != 0 else {
            let mean = sumY / nD
            return x.map { $0 - mean }
        }
        let slope = (nD * sumIY - sumI * sumY) / denom
        let intercept = (sumY - slope * sumI) / nD
        return x.enumerated().map { (i, y) in y - (slope * Double(i) + intercept) }
    }

    /// Normalised autocorrelation of `x` at `lag` (0 when the signal is flat).
    static func acf(_ x: [Double], _ lag: Int) -> Double {
        let n = x.count - lag
        guard n > 0 else { return 0 }
        let mean = x.reduce(0, +) / Double(x.count)
        var den = 0.0
        for v in x { let d = v - mean; den += d * d }
        guard den != 0 else { return 0 }
        var num = 0.0
        for i in 0..<n { num += (x[i] - mean) * (x[i + lag] - mean) }
        return num / den
    }

    /// Subtract the record-synchronous (period = `fs`) component of `x` — the artifact a per-record DC
    /// step / phase reset injects. It autocorrelates strongly at lag = fs (= 60 bpm at 24 Hz), so a
    /// sub-60-bpm sleeper would otherwise SNAP to 60 because fundamental-period preference picks the
    /// smaller lag (#194, ryanbr).
    ///
    /// CRUCIAL: a true ~60-bpm pulse is ALSO period-fs (one cycle per record), so a blind column-mean
    /// subtraction would erase a real 60 bpm. The discriminator is the record BOUNDARY: an artifact
    /// (phase reset / DC step) is DISCONTINUOUS there, while a real pulse flows smoothly across it. So
    /// only de-artifact when the boundary first-difference is much larger than the within-record one;
    /// otherwise leave the signal untouched. Gated on ≥ 4 records so the comb is well estimated.
    static func removeRecordRateComponent(_ x: [Double], fs: Int) -> [Double] {
        let n = x.count
        guard fs > 1, n >= fs * 4 else { return x }
        var withinSum = 0.0, withinCount = 0, boundarySum = 0.0, boundaryCount = 0
        for i in 1..<n {
            let d = abs(x[i] - x[i - 1])
            if i % fs == 0 { boundarySum += d; boundaryCount += 1 } else { withinSum += d; withinCount += 1 }
        }
        guard withinCount > 0, boundaryCount > 0 else { return x }
        let within = withinSum / Double(withinCount)
        let boundary = boundarySum / Double(boundaryCount)
        // Smooth boundaries → a real pulse, not an artifact → leave it alone (preserves a true 60 bpm).
        guard within > 0, boundary > within * 3 else { return x }
        var colSum = [Double](repeating: 0, count: fs)
        var colCount = [Int](repeating: 0, count: fs)
        for i in 0..<n { let p = i % fs; colSum[p] += x[i]; colCount[p] += 1 }
        var colMean = [Double](repeating: 0, count: fs)
        for p in 0..<fs where colCount[p] > 0 { colMean[p] = colSum[p] / Double(colCount[p]) }
        return x.enumerated().map { (i, v) in v - colMean[i % fs] }
    }

    /// Estimate (bpm, confidence) from one PPG window via autocorrelation, or nil when the window is
    /// too short or no pulsatile peak clears `minConfidence` (flat/garbage PPG → no fabricated HR).
    ///
    /// STAYS SWIFT (Phase 2b): the Kotlin twin of this per-window estimator (`estimateWindow`) is
    /// `private` and hardcodes the 24 Hz / 30-220 bpm / 0.3-conf configuration, so this TUNABLE public
    /// API has no public Kotlin shape to route through. The canonical full pipeline (`derivePpgHr`
    /// with the default configuration, the only one any production caller uses) delegates below;
    /// `PpgHrTests` pins this estimator and the delegated pipeline against the same waveforms.
    public static func estimate(_ samples: [Int],
                                fs: Int = sampleRateHz,
                                loBpm: Double = hrLoBpm,
                                hiBpm: Double = hrHiBpm,
                                minConf: Double = minConfidence) -> (bpm: Double, conf: Double)? {
        guard samples.count >= fs * 3 else { return nil }   // need >= 3 s to resolve a low HR
        // De-artifact (#194) THEN linear-detrend, so the autocorrelation sees the pulse, not the
        // record-rate comb that would peg a low HR at 60 bpm.
        let x = detrend(removeRecordRateComponent(samples.map(Double.init), fs: fs))
        let fsD = Double(fs)
        let loLag = max(2, Int((fsD * 60 / hiBpm).rounded()))
        let hiLag = min(x.count - 2, Int((fsD * 60 / loBpm).rounded()))
        guard hiLag > loLag else { return nil }
        var vals = [Int: Double]()
        var peak = -Double.infinity
        for lag in loLag...hiLag {
            let v = acf(x, lag)
            vals[lag] = v
            if v > peak { peak = v }
        }
        guard peak >= minConf else { return nil }
        // Prefer the FUNDAMENTAL period: the smallest-lag local maximum that is nearly as strong as the
        // global peak. Autocorrelation also peaks at 2×/3× the true period (half/third HR); taking the
        // global max there would report half the real rate, so prefer the shortest prominent period.
        var bestLag: Int? = nil
        if loLag + 1 <= hiLag - 1 {
            for lag in (loLag + 1)...(hiLag - 1) {
                let v = vals[lag]!
                if v >= 0.85 * peak && v >= vals[lag - 1]! && v >= vals[lag + 1]! {
                    bestLag = lag
                    break
                }
            }
        }
        let lag = bestLag ?? (vals.max { $0.value < $1.value }!.key)
        // Round to whole bpm (matching Android's Math.round) — the lag is integer so sub-bpm precision
        // isn't real signal, and whole bpm keeps the value + type in lockstep with Android (#219).
        let bpm = (fsD * 60 / Double(lag)).rounded()
        let conf = (vals[lag]! * 1000).rounded() / 1000
        return (bpm, conf)
    }

    /// Per-second PPG-HR over a list of v26 records `(ts, samples)`.
    ///
    /// Records are grouped into consecutive-second runs (PPG phase is only continuous within a run); a
    /// window centred on each second is autocorrelated. Returns one `PpgHrSample` per second that
    /// yielded a confident estimate, ascending by ts. Records may be unsorted / contain gaps.
    ///
    /// DELEGATED (Phase 2b) for the canonical configuration (`fs == sampleRateHz`,
    /// `windowSeconds == windowSeconds`: the shared constants, and the only configuration any
    /// caller or test uses): the whole pipeline runs on the shared Kotlin `PpgHr.estimate`, so
    /// macOS/iOS/Android derive the SAME per-second HR from the same offload. Swift semantics are
    /// preserved at the seam: on a duplicate ts the LAST record wins (the Kotlin pipeline would
    /// concatenate, so the wrapper dedupes before flattening). A non-canonical `fs`/`windowSeconds`
    /// falls through to the Swift windowing lane over the tunable Swift `estimate` above, because
    /// the Kotlin pipeline hardcodes the canonical rate.
    public static func derivePpgHr(records: [(ts: Int, samples: [Int])],
                                   fs: Int = sampleRateHz,
                                   windowSeconds: Int = windowSeconds) -> [PpgHrSample] {
        guard !records.isEmpty else { return [] }
        // One waveform per second (last write wins on a duplicate ts): BOTH lanes rely on this.
        var secs = [Int: [Int]]()
        for r in records { secs[r.ts] = r.samples }

        if fs == PpgHr.sampleRateHz && windowSeconds == PpgHr.windowSeconds {
            var kotlinSamples: [Shared.PpgHr.Sample] = []
            for (ts, vals) in secs {
                for v in vals { kotlinSamples.append(.init(ts: Int64(ts), value: Int32(v))) }
            }
            return Shared.PpgHr.shared.estimate(samples: kotlinSamples).map {
                PpgHrSample(ts: Int(truncatingIfNeeded: $0.ts), bpm: Int($0.bpm), conf: $0.conf)
            }
        }

        let order = secs.keys.sorted()
        // Split into consecutive-second runs.
        var runs = [[Int]]()
        var cur = [order[0]]
        for u in order.dropFirst() {
            if u - cur.last! == 1 { cur.append(u) }
            else { runs.append(cur); cur = [u] }
        }
        runs.append(cur)

        let half = windowSeconds / 2
        var out = [PpgHrSample]()
        for run in runs where run.count >= 3 {
            let runSet = Set(run)
            for t in run {
                // Window of consecutive seconds present in this run, centred on t.
                var win = [Int]()
                for u in (t - half)...(t + half) where runSet.contains(u) { win.append(u) }
                guard win.count >= 3 else { continue }
                var sig = [Int]()
                for u in win { sig.append(contentsOf: secs[u]!) }
                if let est = estimate(sig, fs: fs) {
                    out.append(PpgHrSample(ts: t, bpm: Int(est.bpm), conf: est.conf))
                }
            }
        }
        out.sort { $0.ts < $1.ts }
        return out
    }
}
