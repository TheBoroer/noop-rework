import XCTest
@testable import StrandAnalytics
import WhoopProtocol
import WhoopStore

final class AnalyticsEngineTests: XCTestCase {

    func testVersion() {
        XCTAssertEqual(StrandAnalytics.version, "0.1.0")
    }

    func testDayStringUTC() {
        // 2021-01-01 00:00:00 UTC == 1609459200.
        XCTAssertEqual(AnalyticsEngine.dayString(1_609_459_200), "2021-01-01")
    }

    // MARK: - Offset-aware day-string (#277 local-day re-bucketing)

    func testDayStringLocalEveningWestOfUTC() {
        // A Toronto user (UTC-4) at 22:00 local on 2021-06-15. Local 22:00 EDT == 02:00 UTC the
        // NEXT day (2021-06-16). The UTC bucket would be "2021-06-16"; the LOCAL day is "2021-06-15".
        // 2021-06-16 02:00:00 UTC == 1623808800.
        let tsUtc = 1_623_808_800
        let offset = -4 * 3600  // UTC-4
        XCTAssertEqual(AnalyticsEngine.dayString(tsUtc), "2021-06-16")           // old UTC behaviour
        XCTAssertEqual(AnalyticsEngine.dayString(tsUtc, offsetSec: offset), "2021-06-15")  // local day
    }

    func testDayStringOffsetZeroMatchesUTC() {
        // Offset 0 must be byte-identical to the legacy UTC behaviour for every caller/test.
        let ts = 1_609_459_200
        XCTAssertEqual(AnalyticsEngine.dayString(ts, offsetSec: 0), AnalyticsEngine.dayString(ts))
        // A non-midnight ts too.
        XCTAssertEqual(AnalyticsEngine.dayString(ts + 45_000, offsetSec: 0),
                       AnalyticsEngine.dayString(ts + 45_000))
    }

    func testDayStringSamplesSpanningOneLocalDayMapToOneKey() {
        // Every wall-clock second across a UTC-4 user's local 2021-06-15 (00:00 → 23:59:59 local)
        // must map to the single key "2021-06-15", even though the late-evening hours cross midnight
        // UTC into 2021-06-16. Local 00:00 EDT 2021-06-15 == 04:00 UTC == 1623729600.
        let offset = -4 * 3600
        let localMidnightUtc = 1_623_729_600  // 2021-06-15 00:00:00 local (04:00 UTC)
        // Pick samples at local 00:00, 12:00, 20:00, 23:59 — the last three cross UTC midnight.
        let probes = [0, 12 * 3600, 20 * 3600, 24 * 3600 - 1]
        for p in probes {
            XCTAssertEqual(AnalyticsEngine.dayString(localMidnightUtc + p, offsetSec: offset),
                           "2021-06-15", "local-day probe at +\(p)s mis-bucketed")
        }
        // And one second earlier / one second past the local day fall on the neighbours.
        XCTAssertEqual(AnalyticsEngine.dayString(localMidnightUtc - 1, offsetSec: offset), "2021-06-14")
        XCTAssertEqual(AnalyticsEngine.dayString(localMidnightUtc + 24 * 3600, offsetSec: offset),
                       "2021-06-16")
    }

    func testDayStringEastOfUTC() {
        // A Tokyo user (UTC+9) just after local midnight on 2021-06-16 (15:00 UTC on 2021-06-15)
        // is on local day 2021-06-16 while the UTC bucket is still 2021-06-15.
        // 2021-06-15 15:30:00 UTC == 1623771000.
        let tsUtc = 1_623_771_000
        let offset = 9 * 3600
        XCTAssertEqual(AnalyticsEngine.dayString(tsUtc), "2021-06-15")
        XCTAssertEqual(AnalyticsEngine.dayString(tsUtc, offsetSec: offset), "2021-06-16")
    }

    /// Build a still, low-HR night ending on a known UTC day.
    private func night(endDay: String, hours: Int) -> (start: Int, end: Int,
                                                       hr: [HRSample], rr: [RRInterval],
                                                       gravity: [GravitySample]) {
        // Pick an end timestamp on `endDay` at 06:00 UTC.
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.timeZone = TimeZone(identifier: "UTC")
        fmt.dateFormat = "yyyy-MM-dd"
        let dayMidnight = Int(fmt.date(from: endDay)!.timeIntervalSince1970)
        let end = dayMidnight + 6 * 3600
        let start = end - hours * 3600

        var hr: [HRSample] = []
        var rr: [RRInterval] = []
        var grav: [GravitySample] = []
        for t in start..<end {
            hr.append(HRSample(ts: t, bpm: 50))
            grav.append(GravitySample(ts: t, x: 0, y: 0, z: 1))  // still
        }
        // RR every 2 s at ~1200 ms with tiny oscillation (avoids ectopic rejection).
        var toggle = false
        for t in stride(from: start, to: end, by: 2) {
            rr.append(RRInterval(ts: t, rrMs: toggle ? 1205 : 1195))
            toggle.toggle()
        }
        return (start, end, hr, rr, grav)
    }

    func testAnalyzeDayProducesSleepMetric() {
        let day = "2021-06-15"
        let n = night(endDay: day, hours: 7)
        let profile = UserProfile(weightKg: 75, heightCm: 178, age: 30, sex: "male")
        let result = AnalyticsEngine.analyzeDay(
            day: day, hr: n.hr, rr: n.rr, gravity: n.gravity, profile: profile)

        XCTAssertEqual(result.daily.day, day)
        XCTAssertEqual(result.sleepSessions.count, 1)
        XCTAssertNotNil(result.daily.totalSleepMin)
        XCTAssertGreaterThan(result.daily.totalSleepMin!, 0)
        XCTAssertEqual(result.daily.restingHr, 50)
        XCTAssertNotNil(result.daily.avgHrv)
        XCTAssertEqual(result.daily.avgHrv!, 10.0, accuracy: 1.0)  // RMSSD of ±5 ms oscillation
        // CachedSleepSession rows mirror the detected sessions and carry stage JSON.
        XCTAssertEqual(result.cachedSleep.count, 1)
        XCTAssertNotNil(result.cachedSleep[0].stagesJSON)
        XCTAssertEqual(result.cachedSleep[0].restingHr, 50)
    }

    func testAnalyzeDayColdStartRecoveryNil() {
        // No baselines supplied → recovery is nil (cold-start gate).
        let day = "2021-06-16"
        let n = night(endDay: day, hours: 7)
        let result = AnalyticsEngine.analyzeDay(
            day: day, hr: n.hr, rr: n.rr, gravity: n.gravity,
            profile: UserProfile(age: 30))
        XCTAssertNil(result.daily.recovery)
        XCTAssertNil(result.recovery)
    }

    func testAnalyzeDayWithBaselinesProducesRecovery() {
        let day = "2021-06-17"
        let n = night(endDay: day, hours: 7)
        // Trusted HRV + RHR baselines around the values this night will produce.
        let hrvBase = Baselines.foldHistory(Array(repeating: 10.0, count: 14), cfg: Baselines.hrvCfg)
        let rhrBase = Baselines.foldHistory(Array(repeating: 50.0, count: 14), cfg: Baselines.restingHRCfg)
        XCTAssertTrue(hrvBase.usable)
        let result = AnalyticsEngine.analyzeDay(
            day: day, hr: n.hr, rr: n.rr, gravity: n.gravity,
            profile: UserProfile(age: 30),
            baselines: AnalyticsEngine.ProfileBaselines(hrv: hrvBase, restingHR: rhrBase))
        XCTAssertNotNil(result.recovery)
        XCTAssertEqual(result.daily.recovery, result.recovery)
        XCTAssertGreaterThanOrEqual(result.recovery!, 0)
        XCTAssertLessThanOrEqual(result.recovery!, 100)
    }

    func testAnalyzeDayNoMatchingNight() {
        // A night ending on a different day → no sleep attributed to `day`.
        let n = night(endDay: "2021-06-18", hours: 7)
        let result = AnalyticsEngine.analyzeDay(
            day: "2021-06-19", hr: n.hr, rr: n.rr, gravity: n.gravity,
            profile: UserProfile(age: 30))
        XCTAssertEqual(result.sleepSessions.count, 0)
        XCTAssertNil(result.daily.totalSleepMin)
        XCTAssertEqual(result.daily.exerciseCount, 0)
    }

    func testAnalyzeDayDailyMetricRoundTripsThroughCodable() throws {
        // The produced DailyMetric must encode/decode (it's the WhoopStore cache shape).
        let day = "2021-06-20"
        let n = night(endDay: day, hours: 7)
        let result = AnalyticsEngine.analyzeDay(
            day: day, hr: n.hr, rr: n.rr, gravity: n.gravity, profile: UserProfile(age: 30))
        let data = try JSONEncoder().encode(result.daily)
        let decoded = try JSONDecoder().decode(DailyMetric.self, from: data)
        XCTAssertEqual(decoded, result.daily)
    }

    func testAnalyzeDayPopulatesParityFields() throws {
        // The Android-parity computations must land on the DailyMetric when the streams are
        // supplied: RSA respiration from RR, daily steps from the cumulative @57 counter,
        // whole-day HR-only calories, and the wear-gated skin-temp deviation (usable baseline).
        let day = "2021-06-21"
        let n = night(endDay: day, hours: 7)
        // RSA-modulated RR replacing the square-wave fixture: mean 1200 ms (HR 50), ±40 ms at
        // 0.25 Hz — a planted 15 breaths/min the estimator must recover.
        var rr: [RRInterval] = []
        var tSec = 0.0
        while tSec < Double(n.end - n.start) {
            let rrMs = 1200.0 + 40.0 * sin(2.0 * Double.pi * 0.25 * tSec)
            tSec += rrMs / 1000.0
            rr.append(RRInterval(ts: n.start + Int(tSec), rrMs: Int(rrMs)))
        }
        // Worn in-bed skin temp at 34 °C across the whole night (raw = °C × 100, the firmware's
        // centidegree scale — see SkinTempAnalyticsTests' SCALE NOTE).
        let skin = (0..<(n.end - n.start)).map { SkinTempSample(ts: n.start + $0, raw: 3400) }
        // Step counter: morning movement after wake, inside the same UTC day → 250 steps.
        let steps = [StepSample(ts: n.end + 600, counter: 100),
                     StepSample(ts: n.end + 1200, counter: 350)]
        let skinBase = Baselines.foldHistory([33.5, 33.4, 33.6, 33.5],
                                             cfg: Baselines.metricCfg["skin_temp"]!)
        XCTAssertTrue(skinBase.usable)
        let result = AnalyticsEngine.analyzeDay(
            day: day, hr: n.hr, rr: rr, gravity: n.gravity, steps: steps, skinTemp: skin,
            profile: UserProfile(age: 30),
            baselines: AnalyticsEngine.ProfileBaselines(skinTemp: skinBase))
        XCTAssertEqual(result.sleepSessions.count, 1)
        XCTAssertEqual(result.daily.steps, 250)
        XCTAssertGreaterThan(try XCTUnwrap(result.daily.activeKcalEst), 0)
        // RSA respiration recovered from the in-bed RR (≈15 bpm planted, ±3 tolerance).
        XCTAssertEqual(try XCTUnwrap(result.daily.respRateBpm), 15.0, accuracy: 3.0)
        // Wear-gated nightly mean (34 °C plateau) + a positive deviation vs the ~33.5 °C baseline.
        XCTAssertEqual(try XCTUnwrap(result.nightlySkinTempC), 34.0, accuracy: 1e-9)
        XCTAssertGreaterThan(try XCTUnwrap(result.daily.skinTempDevC), 0.2)
    }

    func testAnalyzeDayWithoutNewStreamsLeavesParityFieldsNil() {
        // Pure-function contract: callers that don't supply steps/skinTemp (all pre-existing
        // call sites and tests) get nil steps + nil skinTempDevC — never a fabricated value.
        let day = "2021-06-22"
        let n = night(endDay: day, hours: 7)
        let result = AnalyticsEngine.analyzeDay(
            day: day, hr: n.hr, rr: n.rr, gravity: n.gravity, profile: UserProfile(age: 30))
        XCTAssertNil(result.daily.steps)
        XCTAssertNil(result.daily.skinTempDevC)
        XCTAssertNil(result.nightlySkinTempC)
    }

    func testAnalyzeDayStepsAttributedByLocalDay() {
        // A UTC-4 user's steps taken at local 21:00–22:00 on 2021-06-15 cross UTC midnight into
        // 2021-06-16. With the matching local-day key + tzOffset, analyzeDay must attribute them to
        // the LOCAL day 2021-06-15 (the bucket the dashboard reads), not the UTC day. Local 21:00 EDT
        // 2021-06-15 == 01:00 UTC 2021-06-16 == 1623805200.
        let offset = -4 * 3600
        let day = "2021-06-15"
        let lateEveningUtc = 1_623_805_200  // local 21:00 on 2021-06-15 (next-day UTC)
        let steps = [StepSample(ts: lateEveningUtc, counter: 100),
                     StepSample(ts: lateEveningUtc + 1800, counter: 360)]  // +260 within the local day
        let result = AnalyticsEngine.analyzeDay(
            day: day, steps: steps, profile: UserProfile(), tzOffsetSeconds: offset)
        XCTAssertEqual(result.daily.steps, 260)
        // Sanity: the OLD UTC bucketing would have dropped these (they're UTC day 2021-06-16) →
        // verify offset 0 with the UTC day produces nil, proving the offset is what saves them.
        let utcResult = AnalyticsEngine.analyzeDay(
            day: day, steps: steps, profile: UserProfile(), tzOffsetSeconds: 0)
        XCTAssertNil(utcResult.daily.steps)
    }

    // MARK: - Rest composite (Charge/Effort/Rest)

    func testRestCompositePerfectNight() {
        // 8 h asleep over 8 h in bed (eff 1.0), 4 h deep+REM (50% restorative), perfect
        // consistency, need 8 h → every sub-score saturates → 100.
        let r = AnalyticsEngine.Rest.composite(
            tstSeconds: 8 * 3600, inBedSeconds: 8 * 3600, efficiency: 1.0,
            restorativeSeconds: 4 * 3600, needHours: 8.0, consistency: 1.0)
        XCTAssertEqual(r, 100.0, accuracy: 1e-9)
    }

    func testRestCompositeDurationDominatedAndClamped() {
        // Duration term alone: 8 h asleep vs 8 h need → 1.0 × 0.50 weight = 50, all other
        // sub-scores 0. Confirms the 0.50 duration weight and that over-need clamps at 1.0.
        let r = AnalyticsEngine.Rest.composite(
            tstSeconds: 8 * 3600, inBedSeconds: 99_999, efficiency: 0.0,
            restorativeSeconds: 0.0, needHours: 8.0, consistency: 0.0)
        XCTAssertEqual(r, 50.0, accuracy: 1e-9)
        // Sleeping well over need does not push duration past 1.0.
        let over = AnalyticsEngine.Rest.composite(
            tstSeconds: 12 * 3600, inBedSeconds: 12 * 3600, efficiency: 1.0,
            restorativeSeconds: 6 * 3600, needHours: 8.0, consistency: 1.0)
        XCTAssertEqual(over, 100.0, accuracy: 1e-9)
    }

    func testRestCompositeNilConsistencyIsNeutral() {
        // A single day carries no regularity signal → nil consistency scores the neutral 0.5.
        let withNil = AnalyticsEngine.Rest.composite(
            tstSeconds: 4 * 3600, inBedSeconds: 5 * 3600, efficiency: 0.8,
            restorativeSeconds: 1 * 3600, needHours: 8.0, consistency: nil)
        let withHalf = AnalyticsEngine.Rest.composite(
            tstSeconds: 4 * 3600, inBedSeconds: 5 * 3600, efficiency: 0.8,
            restorativeSeconds: 1 * 3600, needHours: 8.0, consistency: 0.5)
        XCTAssertEqual(withNil, withHalf, accuracy: 1e-9)
        XCTAssertEqual(withNil, 56.0, accuracy: 1e-9)
    }

    func testAnalyzeDayPopulatesRestAndConfidence() {
        // A normal night yields a Rest score and a Rest confidence that is at least
        // .building (a session exists). With no HRV baseline, Charge is .calibrating;
        // 7 h of 1 Hz HR makes Effort .solid.
        let day = "2021-06-23"
        let n = night(endDay: day, hours: 7)
        let result = AnalyticsEngine.analyzeDay(
            day: day, hr: n.hr, rr: n.rr, gravity: n.gravity, profile: UserProfile(age: 30))
        XCTAssertNotNil(result.restScore)
        XCTAssertGreaterThan(result.restScore!, 0)
        XCTAssertLessThanOrEqual(result.restScore!, 100)
        XCTAssertNotEqual(result.restConfidence, .calibrating)  // a session exists
        XCTAssertEqual(result.chargeConfidence, .calibrating)   // no HRV baseline
        XCTAssertEqual(result.effortConfidence, .solid)         // 7 h of 1 Hz HR ≫ 1 h
    }

    func testNoMatchingNightLeavesRestNilAndCalibrating() {
        let n = night(endDay: "2021-06-24", hours: 7)
        let result = AnalyticsEngine.analyzeDay(
            day: "2021-06-25", hr: n.hr, rr: n.rr, gravity: n.gravity, profile: UserProfile(age: 30))
        XCTAssertNil(result.restScore)
        XCTAssertEqual(result.restConfidence, .calibrating)
    }

    // MARK: - ScoreConfidence boundaries

    func testChargeConfidenceTiers() {
        let trusted = BaselineState(baseline: 50, spread: 5, nValid: 14,
                                    nightsSinceUpdate: 0, status: .trusted)
        let provisional = BaselineState(baseline: 50, spread: 5, nValid: 5,
                                        nightsSinceUpdate: 0, status: .provisional)
        let calibrating = BaselineState(baseline: 50, spread: 5, nValid: 2,
                                        nightsSinceUpdate: 0, status: .calibrating)
        // Score present + trusted baseline → solid.
        XCTAssertEqual(ScoreConfidence.charge(recovery: 60, hrvBaseline: trusted), .solid)
        // Score present + provisional baseline → building.
        XCTAssertEqual(ScoreConfidence.charge(recovery: 60, hrvBaseline: provisional), .building)
        // No score → calibrating regardless of baseline.
        XCTAssertEqual(ScoreConfidence.charge(recovery: nil, hrvBaseline: trusted), .calibrating)
        // Unusable baseline → calibrating.
        XCTAssertEqual(ScoreConfidence.charge(recovery: 60, hrvBaseline: calibrating), .calibrating)
        XCTAssertEqual(ScoreConfidence.charge(recovery: 60, hrvBaseline: nil), .calibrating)
    }

    func testEffortConfidenceTiers() {
        // No strain → calibrating. Thin HR window → building. Dense → solid (boundary at 3600).
        XCTAssertEqual(ScoreConfidence.effort(strain: nil, hrSampleCount: 10_000), .calibrating)
        XCTAssertEqual(ScoreConfidence.effort(strain: 40, hrSampleCount: 3599), .building)
        XCTAssertEqual(ScoreConfidence.effort(strain: 40, hrSampleCount: 3600), .solid)
    }

    func testRestConfidenceTiers() {
        XCTAssertEqual(ScoreConfidence.rest(hasSession: false, hasStagedSleep: false), .calibrating)
        XCTAssertEqual(ScoreConfidence.rest(hasSession: true, hasStagedSleep: false), .building)
        XCTAssertEqual(ScoreConfidence.rest(hasSession: true, hasStagedSleep: true), .solid)
    }
}
