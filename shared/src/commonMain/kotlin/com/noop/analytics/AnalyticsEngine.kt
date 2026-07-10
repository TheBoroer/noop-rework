package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.EventRow
import com.noop.data.HrSample
import com.noop.data.SkinTempSample
import com.noop.data.Spo2Sample
import com.noop.protocol.DeviceFamily
import com.noop.protocol.skinTempCelsius
import com.noop.util.epochSecondsToLocalDate
import com.noop.util.orgJsonQuote
import com.noop.util.toFixed
import kotlinx.datetime.TimeZone
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * AnalyticsEngine.kt — orchestrator producing DailyMetric + sleep-session results.
 *
 * Faithful Kotlin port of StrandAnalytics/AnalyticsEngine.swift (verified on macOS).
 * Same algorithm, same constants, same thresholds; Kotlin-ized types, Double math.
 *
 * Given a day's raw streams + a user profile + personal baselines, it runs the
 * individual analyzers (SleepStager / RecoveryScorer / StrainScorer / WorkoutDetector
 * / Baselines) and assembles a [com.noop.data.DailyMetric] (Room cache shape) plus the
 * detected [DetectedSleep] sessions.
 *
 * This is a PURE function over its inputs — it does NOT touch the database
 * (persistence is wired by IntelligenceEngine). All derived values are APPROXIMATE.
 *
 * All `ts` / `start` / `end` are wall-clock unix SECONDS (Long); the Swift source
 * uses Int seconds.
 *
 * Phase 2a Task 6: the object lives in commonMain; ONLY [analyzeDay] stays behind in androidMain
 * (AnalyticsEngineDay.kt) as an extension with an unchanged call shape, because it orchestrates
 * over [Baselines], whose SharedPreferences boundary legitimately stays androidMain this phase.
 */
object AnalyticsEngine {

    /**
     * Pair the strap's WRIST_OFF/WRIST_ON events into off-wrist [start, end) intervals for the sleep
     * detector's fractional wear filter (#500; design credited to j0b-dev's #504). Each WRIST_OFF opens
     * an interval that closes at the next WRIST_ON, or at [windowEnd] if the strap is still off at the
     * end of the read window. Events need not be pre-sorted; kinds are formatted "NAME(n)" (e.g.
     * "WRIST_OFF(10)"), matched by prefix. Repeated OFFs/ONs without a partner are coalesced. Mirrors Swift.
     */
    fun offWristIntervals(events: List<EventRow>, windowEnd: Long): List<Pair<Long, Long>> {
        val wear = events
            .filter { it.kind.startsWith("WRIST_OFF") || it.kind.startsWith("WRIST_ON") }
            .sortedBy { it.ts }
        val intervals = ArrayList<Pair<Long, Long>>()
        var offStart: Long? = null
        for (e in wear) {
            if (e.kind.startsWith("WRIST_OFF")) {
                if (offStart == null) offStart = e.ts            // ignore repeated OFFs
            } else {                                             // WRIST_ON closes an open off-wrist span
                val s = offStart
                if (s != null && e.ts > s) intervals.add(s to e.ts)
                offStart = null
            }
        }
        val s = offStart
        if (s != null && windowEnd > s) intervals.add(s to windowEnd)
        return intervals
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Day-string helper (UTC YYYY-MM-DD), mirrors Swift AnalyticsEngine.isoDay.
    // ─────────────────────────────────────────────────────────────────────────

    /** Format a unix-seconds timestamp as a UTC YYYY-MM-DD day string. (kotlinx-datetime
     *  [epochSecondsToLocalDate] on the fixed-UTC zone; LocalDate.toString() is the ISO yyyy-MM-dd
     *  the java.time DateTimeFormatter emitted, byte-identical; pinned by LocalDayBucketingTest.) */
    fun dayString(ts: Long): String = epochSecondsToLocalDate(ts, TimeZone.UTC).toString()

    /**
     * Format a unix-seconds timestamp as the device's LOCAL YYYY-MM-DD day string (#277).
     *
     * The day key is the core aggregation key for daily metrics; the dashboard reads "today" by the
     * device's LOCAL calendar day, so the bucket must be the LOCAL day too. A west-of-UTC user's
     * evening (which crosses midnight UTC) would otherwise flow into the next UTC bucket and the local
     * "today" read would never find it — freezing the dashboard (Toronto/UTC-4 report). [offsetSec] is
     * seconds EAST of UTC (TimeZone.getDefault().getOffset(...)/1000). The local date is the UTC date
     * of `(ts + offsetSec)`: shifting the instant by the offset turns the fixed-UTC formatter into a
     * local-calendar formatter. [offsetSec] == 0 is byte-identical to the UTC [dayString] above, so
     * pure-function callers/tests on UTC are unchanged.
     */
    fun dayString(ts: Long, offsetSec: Long): String = dayString(ts + offsetSec)

    /**
     * JSON-encode stage segments to the verbatim array shape the sleepSession cache
     * stores. Mirrors Swift `encodeStages` (JSONEncoder on [StageSegment]); the field
     * names (start, end, stage) match the Codable wire shape and the Android
     * SleepScreen reader (decoders are key-order-independent, so the reader is unaffected).
     *
     * DETERMINISM (parity with Swift's `.sortedKeys`): the object keys are emitted in a FIXED
     * alphabetical order — `end`, `stage`, `start` — built by hand rather than via
     * `JSONObject.put` order. `org.json.JSONObject` (both the stock Android runtime impl and the
     * `org.json:json` JVM test jar) backs its key store with a plain `HashMap`, so `toString()`
     * emits keys in hash-iteration order, which is NOT insertion order and is not guaranteed stable
     * across runtimes/versions. The post-sync self-heal ([SleepStageHealer.selfHealEditedStages])
     * skips its write when the re-derived JSON equals the stored JSON; an unstable key order would
     * defeat that equality check (spurious rewrites, or a Robolectric-vs-device mismatch). Sorting
     * the keys makes a re-derive over identical bounds+raw byte-identical to what was stored.
     * Values are escaped via [orgJsonQuote] (the byte-identical Kotlin port of org.json's
     * JSONObject.quote, Task 4); the stage string is constrained, but stay safe.
     */
    fun encodeStages(stages: List<StageSegment>): String? {
        return try {
            val sb = StringBuilder()
            sb.append('[')
            for ((i, s) in stages.withIndex()) {
                if (i > 0) sb.append(',')
                // Keys alphabetical: end, stage, start — matches Swift JSONEncoder.outputFormatting
                // = .sortedKeys on StageSegment{start,end,stage}.
                sb.append("{\"end\":").append(s.end)
                    .append(",\"stage\":").append(orgJsonQuote(s.stage))
                    .append(",\"start\":").append(s.start)
                    .append('}')
            }
            sb.append(']')
            sb.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /** Round to 2 decimal places (matches the imported/demo skin-temp deviation precision). (PR #85)
     *  Internal (not private) so the androidMain [analyzeDay] extension keeps using it (Task 6). */
    internal fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    /** Min worn, in-bed skin-temp samples (1 Hz ⇒ seconds) before a nightly mean is trusted. ~5 min
     *  guards against a few stray samples fabricating a baseline value. (PR #85) */
    private const val MIN_SKIN_TEMP_SAMPLES_INLINE = 300

    /**
     * Wear-gated mean in-bed skin temperature (°C) for the night, or null when too few worn samples.
     * A sample counts when (a) its timestamp falls inside a detected in-bed [sessions] span, (b) a
     * concurrent HR sample reads a worn, alive BPM (the strap streams HR only on-wrist), and (c) the
     * value is in the plausible worn range — so an on-charger interval drifting to ambient (which still
     * passes the strap's looser 20–45 decode gate, e.g. the ~22 °C off-wrist decode fixture) can't
     * poison the nightly mean.
     *
     * The raw→°C conversion is DEVICE-FAMILY-AWARE (#938): 5/MG stores CENTIDEGREES (°C = raw/100), but
     * the WHOOP 4.0 v24 field@72 is a RAW ADC on a different scale — running it through /100 read every
     * worn 4.0 night ~8 °C, below the 28 °C worn gate, so kept=0 and skin temp + the illness signal
     * vanished (issue #938). The shared [skinTempCelsius] picks the right scale; [family] defaults to
     * WHOOP5 so every existing 5/MG + pure-function caller is byte-identical. All values APPROXIMATE.
     */
    internal fun wornNightlySkinTempC(
        sessions: List<DetectedSleep>,
        hr: List<HrSample>,
        skinTemp: List<SkinTempSample>,
        family: DeviceFamily = DeviceFamily.WHOOP5,
        minSamples: Int = MIN_SKIN_TEMP_SAMPLES_INLINE,
    ): Double? = skinTempFunnel(sessions, hr, skinTemp, family, minSamples).mean

    /**
     * Nightly means of the WHOOP 4.0 raw SpO2 PPG channels (red/IR ADC) over the detected in-bed
     * [sessions], or null when no raw SpO2 sample fell inside any span. A sample counts when its
     * timestamp lies within a session's [start, end]. WHOOP 4.0 banks these as raw PPG ADC values
     * (spo2_red@68 / spo2_ir@70 on the v24 historical layout) but NOT a calibrated blood-oxygen % —
     * computing one needs WHOOP's proprietary curve, so we surface the RAW means only. Deliberately NO
     * wear gate (unlike skin temp): the strap only streams SpO2 on-wrist, so there is no off-charger
     * drift to exclude, and the value is surfaced honestly as raw ADC — never scored — so there is
     * nothing to poison into a fake %. Pure + deterministic; twin of the Swift `nightlySpo2RawMeans`. (#93)
     */
    internal fun nightlySpo2RawMeans(
        sessions: List<DetectedSleep>,
        spo2: List<Spo2Sample>,
    ): Pair<Int, Int>? {
        if (sessions.isEmpty() || spo2.isEmpty()) return null
        var redSum = 0L
        var irSum = 0L
        var kept = 0
        for (s in spo2) {
            if (sessions.none { s.ts in it.start..it.end }) continue
            redSum += s.red
            irSum += s.ir
            kept++
        }
        if (kept == 0) return null
        return (redSum / kept).toInt() to (irSum / kept).toInt()
    }

    /** Plausible worn skin-temperature range (°C). Off-wrist/charging samples drift to ambient and are
     *  excluded; the strap's own decode gate is the looser 20–45. (PR #85) */
    private const val SKIN_TEMP_MIN_C: Double = 28.0
    private const val SKIN_TEMP_MAX_C: Double = 42.0

    // ── Skin-temp funnel diagnostic (#752) ──────────────────────────────────────────────────────────

    /**
     * Why nightly skin temp funneled toward absent for one night. Counts are over the night's raw skin-temp
     * samples; each sample is attributed to the FIRST gate that dropped it, in the SAME order
     * [wornNightlySkinTempC] applies (not-worn → out-of-window → out-of-range → kept), so the four drop
     * buckets plus [kept] sum to [totalSamples]. Pure + deterministic; shares the exact gate logic with the
     * real computation, so it explains the SAME mean the app uses. Mirrors Swift `SkinTempFunnelDiagnostic`.
     * (#752)
     */
    data class SkinTempFunnelDiagnostic(
        val totalSamples: Int,
        val droppedNotWorn: Int,
        val droppedOutOfWindow: Int,
        val droppedOutOfRange: Int,
        val kept: Int,
        val minSamples: Int,
        val mean: Double?,
    ) {
        /** True when the night produced no usable mean - the case this diagnostic exists to triage. */
        val isAbsent: Boolean get() = mean == null

        /** One human-readable line for the caller to LOG. No I/O here - the engine stays pure.
         *  ([toFixed] is the Task 2 locale-independent stand-in for String.format(Locale.US, "%.2f");
         *  tie-exact JVM parity, pinned by AnalyticsFormatPinningTest.) */
        val summary: String
            get() = "skin-temp-funnel: $totalSamples samples → kept $kept/$minSamples " +
                "(mean=${mean?.let { "${it.toFixed(2)}°C" } ?: "absent"}); " +
                "dropped[notWorn=$droppedNotWorn, outOfWindow=$droppedOutOfWindow, " +
                "outOfRange=$droppedOutOfRange]"
    }

    /**
     * Read-only skin-temp funnel for one night (#752). Re-runs the SAME wear/window/range gates
     * [wornNightlySkinTempC] uses (and produces the IDENTICAL mean), additionally counting where each
     * sample dropped, so an absent skin temp is self-explaining. [wornNightlySkinTempC] is a thin wrapper
     * over this, so the two can never disagree. Pure + deterministic. Mirrors Swift `skinTempFunnel`. (#752)
     */
    fun skinTempFunnel(
        sessions: List<DetectedSleep>,
        hr: List<HrSample>,
        skinTemp: List<SkinTempSample>,
        family: DeviceFamily = DeviceFamily.WHOOP5,
        minSamples: Int = MIN_SKIN_TEMP_SAMPLES_INLINE,
    ): SkinTempFunnelDiagnostic {
        val total = skinTemp.size
        // No sessions ⇒ every sample is out of window; no samples ⇒ an empty funnel. Either way the mean is
        // null, exactly as [wornNightlySkinTempC]'s early return produced before.
        if (sessions.isEmpty() || skinTemp.isEmpty()) {
            return SkinTempFunnelDiagnostic(
                totalSamples = total, droppedNotWorn = 0,
                droppedOutOfWindow = if (sessions.isEmpty()) total else 0,
                droppedOutOfRange = 0, kept = 0, minSamples = minSamples, mean = null,
            )
        }
        val wornSeconds = HashSet<Long>(hr.size)
        for (h in hr) if (h.bpm in 30..220) wornSeconds.add(h.ts)
        var sum = 0.0
        var kept = 0
        var notWorn = 0
        var outOfWindow = 0
        var outOfRange = 0
        for (t in skinTemp) {
            if (t.ts !in wornSeconds) { notWorn++; continue }
            if (sessions.none { t.ts in it.start..it.end }) { outOfWindow++; continue }
            val c = skinTempCelsius(t.raw, family)   // #938: family-aware (5/MG=raw/100, 4.0=raw ADC map)
            if (c < SKIN_TEMP_MIN_C || c > SKIN_TEMP_MAX_C) { outOfRange++; continue }
            sum += c
            kept++
        }
        val mean = if (kept >= minSamples) sum / kept else null
        return SkinTempFunnelDiagnostic(
            totalSamples = total, droppedNotWorn = notWorn, droppedOutOfWindow = outOfWindow,
            droppedOutOfRange = outOfRange, kept = kept, minSamples = minSamples, mean = mean,
        )
    }
}

/*
 * RestScorer — NOOP "Rest" (sleep_performance) composite, 0–100.
 *
 * Faithful Kotlin mirror of the Swift Rest composite (AnalyticsEngine / RestScorer). Keep every
 * constant and the weight set byte-identical to Swift — parity tests enforce it.
 *
 *   Rest = 0.50·duration + 0.20·efficiency + 0.20·restorative + 0.10·consistency
 *
 * Each sub-component is itself on 0–100:
 *   duration     — asleep hours / personal need, clamped at 100 (8 h default, refined by recent avg).
 *   efficiency   — asleep / in-bed (0..1) × 100.
 *   restorative  — (deep + REM) / asleep share, normalized by a healthy target share, clamped 100.
 *   consistency  — sleep/wake regularity (0..1) × 100; when the caller has no history it is null and
 *                  the term DROPS, renormalizing the remaining weights (same discipline as recovery).
 *
 * Outputs APPROXIMATE — not WHOOP's proprietary Sleep Performance.
 */
object RestScorer {

    /** Component weights (sum 1.0 when all present). Byte-identical to Swift. */
    const val wDuration: Double = 0.50
    const val wEfficiency: Double = 0.20
    const val wRestorative: Double = 0.20
    const val wConsistency: Double = 0.10

    /** Default personal sleep need (hours) before any recent-average refinement. */
    const val defaultSleepNeedHours: Double = 8.0

    /**
     * Healthy restorative (deep + REM) share of asleep time. A share at/above this earns full
     * restorative credit; below it scales linearly. ~0.50 reflects ~20% deep + ~25–30% REM in a
     * well-structured night.
     */
    const val restorativeTargetShare: Double = 0.50

    /**
     * Deep-sleep share of asleep time that earns FULL restorative credit (~13% is the healthy floor
     * for adults; below it the restorative term scales down toward [deepFloorFactor]). DEEP honesty
     * (Reddit HRV/sleep report): pooling deep+REM let a night with normal REM but almost no DEEP earn
     * near-full restorative credit (Rest read 95+ with little deep). Byte-identical to Swift.
     */
    const val deepShareTarget: Double = 0.13

    /** Most the restorative term is scaled down when deep is ~absent — half, never zeroed, so a
     *  low-deep night reads honestly without the whole night tanking. Swift parity. */
    const val deepFloorFactor: Double = 0.5

    /** Neutral consistency (fraction) used when the caller supplies no regularity signal. Swift parity. */
    const val NEUTRAL_CONSISTENCY: Double = 0.5

    /**
     * Rest composite [0,100], or null when there is no asleep time.
     *
     * @param asleepSeconds total sleep time (TST) for the night, seconds.
     * @param efficiency asleep / in-bed in [0,1].
     * @param deepSeconds deep-stage seconds.
     * @param remSeconds REM-stage seconds.
     * @param sleepNeedHours personal need (hours); null → [defaultSleepNeedHours].
     * @param consistency sleep/wake regularity in [0,1]; null drops the term + renormalizes.
     */
    fun rest(
        asleepSeconds: Double,
        efficiency: Double,
        deepSeconds: Double,
        remSeconds: Double,
        sleepNeedHours: Double? = null,
        consistency: Double? = null,
    ): Double? {
        if (asleepSeconds <= 0.0) return null

        val asleepHours = asleepSeconds / 3600.0
        val needHours = (sleepNeedHours ?: defaultSleepNeedHours).coerceAtLeast(1e-9)

        // Duration vs personal need (clamped at 100 — sleeping past need does not over-credit).
        val durationScore = min(100.0, asleepHours / needHours * 100.0)
        // Efficiency (0..1 → 0..100), clamped.
        val efficiencyScore = (efficiency * 100.0).coerceIn(0.0, 100.0)
        // Restorative share vs healthy target (clamped at 100), then scaled by a gentle deep-adequacy
        // factor in [deepFloorFactor, 1]: full once deep ≥ target share, ramping to the floor as
        // deep → 0, so a near-zero-deep night loses up to half this term (~10 pts) — honest, not
        // tanking, no fabricated stages. Mirrors Swift Rest.composite EXACTLY.
        val restorativeShare = (deepSeconds + remSeconds) / asleepSeconds
        val deepAdequacy = ((deepSeconds / asleepSeconds) / deepShareTarget).coerceIn(0.0, 1.0)
        val deepFactor = deepFloorFactor + (1.0 - deepFloorFactor) * deepAdequacy
        val restorativeScore = min(100.0, restorativeShare / restorativeTargetShare * 100.0) * deepFactor

        // Consistency uses a NEUTRAL 0.5 (→50) when the caller supplies none — matching the Swift
        // Rest.composite EXACTLY (parity is required; Swift adds a neutral term, it does NOT drop +
        // renormalize). Weights sum to 1.0 so the weighted sum is already on 0..100.
        val consistencyScore = ((consistency ?: NEUTRAL_CONSISTENCY) * 100.0).coerceIn(0.0, 100.0)
        val weighted = wDuration * durationScore +
            wEfficiency * efficiencyScore +
            wRestorative * restorativeScore +
            wConsistency * consistencyScore
        return (weighted * 100.0).roundToInt() / 100.0
    }

    /**
     * Sleep & Rest test-mode (E11) diagnostic line for the Rest composite. Recomputes the four weighted
     * sub-scores from the SAME inputs `rest()` reads (on the 0..1 scale, byte-aligned with the Swift
     * `Rest.subScoreLine`), and reuses `rest()` for the final `composite=` value so the trace can never
     * disagree with the score. `groupFragments` / `groupInBedSeconds` describe the main-night GROUP
     * composition (#525/#561). Pure, side-effect-free, no em-dashes. Mirrors Swift exactly.
     */
    fun subScoreLine(
        tstSeconds: Double, inBedSeconds: Double, efficiency: Double, restorativeSeconds: Double,
        needHours: Double, consistency: Double?, deepSeconds: Double?,
        groupFragments: Int, groupInBedSeconds: Double,
    ): String {
        fun clamp01(x: Double) = maxOf(0.0, minOf(1.0, x))
        // roundToLong == Math.round semantics (ties toward positive infinity), multiplatform.
        fun r2(x: Double) = (x * 100.0).roundToLong() / 100.0
        val needSeconds = maxOf(needHours, 0.1) * 3600.0
        val durationScore = clamp01(tstSeconds / needSeconds)
        val efficiencyScore = clamp01(efficiency)
        val deepFactor = if (deepSeconds != null && tstSeconds > 0 && deepShareTarget > 0) {
            val adequacy = clamp01((deepSeconds / tstSeconds) / deepShareTarget)
            deepFloorFactor + (1.0 - deepFloorFactor) * adequacy
        } else 1.0
        val restorativeScore = if (tstSeconds > 0)
            clamp01((restorativeSeconds / tstSeconds) / restorativeTargetShare) * deepFactor else 0.0
        val consistencyScore = clamp01(consistency ?: NEUTRAL_CONSISTENCY)
        // Reuse the real scorer for the composite (cannot diverge). `rest()` takes deep + REM separately;
        // restorative = deep + REM, so REM = restorative - deep. null deep -> 0 deep (no-adequacy path).
        val composite = rest(
            asleepSeconds = tstSeconds, efficiency = efficiency,
            deepSeconds = deepSeconds ?: 0.0,
            remSeconds = restorativeSeconds - (deepSeconds ?: 0.0),
            sleepNeedHours = needHours, consistency = consistency,
        ) ?: 0.0
        return "rest composite=${r2(composite)} " +
            "dur=${r2(durationScore)}*wDur=$wDuration " +
            "eff=${r2(efficiencyScore)}*wEff=$wEfficiency " +
            "restor=${r2(restorativeScore)}*wRestor=$wRestorative deepFactor=${r2(deepFactor)} " +
            "consist=${r2(consistencyScore)}*wConsist=$wConsistency " +
            "group=$groupFragments groupInBedMin=${(groupInBedSeconds / 60).toInt()}"
    }

    /**
     * Rest composite [0,100] derived from a persisted [DailyMetric] (the pass-2 / display path — raw
     * streams are gone but the night's totals remain). null when there's no sleep. Single source of
     * truth so the persisted sleep_performance series and the Charge "Rest quality" term agree. Mirrors
     * Swift `AnalyticsEngine.Rest.composite(daily:)`.
     */
    fun restFromDaily(daily: DailyMetric, consistency: Double? = null): Double? {
        val tstMin = daily.totalSleepMin ?: return null
        val eff = daily.efficiency ?: return null
        if (tstMin <= 0.0) return null
        return rest(
            asleepSeconds = tstMin * 60.0,
            efficiency = eff,
            deepSeconds = (daily.deepMin ?: 0.0) * 60.0,
            remSeconds = (daily.remMin ?: 0.0) * 60.0,
            sleepNeedHours = null,
            consistency = consistency,
        )
    }
}
