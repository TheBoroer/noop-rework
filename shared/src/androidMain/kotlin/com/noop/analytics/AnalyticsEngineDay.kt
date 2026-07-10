package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RespSample
import com.noop.data.RrInterval
import com.noop.data.SkinTempSample
import com.noop.data.Spo2Sample
import com.noop.data.StepSample
import com.noop.protocol.DeviceFamily
import kotlin.math.max
import kotlin.math.roundToLong

// PHASE2: hoist (blocked on Baselines: analyzeDay orchestrates over Baselines.deviation and the
// ProfileBaselines fold, and Baselines' android.content.SharedPreferences boundary legitimately
// stays androidMain this phase. Everything else AnalyticsEngine.kt held moved to commonMain in
// Task 6; this extension keeps the historical `AnalyticsEngine.analyzeDay(...)` call shape at
// every call site (IntelligenceEngine + the JVM tests) unchanged.)

/**
 * Analyze one day's streams into a [DayResult].
 *
 * @param day the calendar day (UTC) this metric is for; a sleep session is
 *   attributed to the day its `end` falls on (a night ending that morning).
 * @param hr/rr/resp/gravity the day's raw streams (the wider window around the
 *   night may be passed; sleep detection finds the in-bed span itself).
 * @param profile user profile (age/sex/weight/height) for HRmax + calories.
 * @param baselines personal baselines for recovery normalization.
 * @param maxHROverride explicit HRmax (bpm) to use for strain/zones; null →
 *   Tanaka from profile.age.
 */
fun AnalyticsEngine.analyzeDay(
    day: String,
    hr: List<HrSample> = emptyList(),
    rr: List<RrInterval> = emptyList(),
    resp: List<RespSample> = emptyList(),
    gravity: List<GravitySample> = emptyList(),
    steps: List<StepSample> = emptyList(),
    // Calendar-day-scoped overrides for the ADDITIVE daily totals (steps + activeKcalEst) AND
    // workout detection + strain. When null, each falls back to the same night window the rest of
    // the analysis uses (preserving the pure-function contract). The caller (IntelligenceEngine)
    // supplies a full [localMidnight(day), localMidnight(day)+86400) read here so a day's late
    // hours — which fall outside the ~42h night window (it ends at dayStart+12h ≈ noon) — are still
    // seen. dayHr/daySteps drive the additive step + calorie totals; dayHr/dayGravity ALSO feed
    // WorkoutDetector so an afternoon/evening workout is detected on its OWN calendar day instead of
    // lagging to the next pass; dayHr ALSO drives strain ("Effort") so the day's load reflects the
    // WHOLE calendar day, not midnight→noon (+ the night window's −30h prior-evening bleed). A
    // workout straddling local midnight splits at the day boundary (same tradeoff as the totals).
    // Sleep / recovery keep using hr/rr/resp/gravity — staging needs the pre-midnight night span.
    dayHr: List<HrSample>? = null,
    daySteps: List<StepSample>? = null,
    dayGravity: List<GravitySample>? = null,
    // Wear-gated nightly skin-temp mean is harvested here (baseline-independent); IntelligenceEngine
    // seeds a personal baseline from these means across nights and re-derives skinTempDevC in pass 2
    // (same two-pass shape as avgHrv→recovery). (PR #85)
    skinTemp: List<SkinTempSample> = emptyList(),
    // Device family that wrote [skinTemp], so the raw→°C conversion picks the right scale (#938):
    // 5/MG banks CENTIDEGREES (raw/100), the WHOOP 4.0 v24 field is a RAW ADC on a different scale.
    // Default WHOOP5 keeps every 5/MG + pure-function caller byte-identical; IntelligenceEngine passes
    // the day owner's real family.
    skinTempFamily: DeviceFamily = DeviceFamily.WHOOP5,
    // WHOOP 4.0 raw SpO2 PPG ADC samples (red/IR) for the night window (#93). The nightly red/IR
    // means over detected sleep are banked on the DailyMetric as RAW ADC — honest "the sensor
    // decoded" data, NOT a calibrated blood-oxygen % (that needs WHOOP's proprietary curve). Default
    // empty keeps pure-function callers/tests + non-4.0 nights null.
    spo2: List<Spo2Sample> = emptyList(),
    profile: UserProfile,
    baselines: ProfileBaselines = ProfileBaselines(),
    maxHROverride: Double? = null,
    // Wall-clock UTC offset (seconds) for the sleep detector's daytime false-sleep guard (#90).
    // Default 0 keeps pure-function callers/tests on UTC; IntelligenceEngine passes the device's
    // real offset.
    tzOffsetSeconds: Long = 0L,
    // Off-wrist [start, end) intervals (unix seconds) for the off-wrist sleep backstop (#500),
    // paired from WRIST_OFF/WRIST_ON events by [offWristIntervals]. The HR-gap proxy in detectSleep
    // is the always-on guard; these explicit intervals sharpen it under the FRACTIONAL rule (#504) —
    // a session is dropped only when its off-wrist coverage reaches maxOffWristSleepFraction. Default
    // empty keeps pure-function callers/tests event-free; IntelligenceEngine passes the night window's intervals.
    wristOff: List<Pair<Long, Long>> = emptyList(),
    // Personal sleep need (hours) for the Rest "duration vs need" component. null → 8 h default.
    // IntelligenceEngine refines it from the user's recent average asleep hours. (Charge/Effort/Rest)
    sleepNeedHours: Double? = null,
    // How many recent nights informed [sleepNeedHours] (0 = still on the 8 h default). Drives the
    // Rest confidence tier ONLY; does not affect the score. (Charge/Effort/Rest)
    sleepNeedNights: Int = 0,
    // Sleep/wake regularity in [0,1] (1 = perfectly regular) for the Rest "consistency" component.
    // null (single-day / pure callers with no history) → the term drops and its weight
    // renormalizes, exactly like the recovery driver-drop discipline. (Charge/Effort/Rest)
    sleepConsistency: Double? = null,
    // The user's learned habitual midsleep (local time-of-day seconds in [0, 86400)) for the
    // main-night scored pick, so a late/shift sleeper's real night out-scores a daytime nap. null =
    // cold-start: the selector falls back to the broad overnight-band bonus. IntelligenceEngine
    // computes this once per run from the trailing sleep history and threads it down; pure-function
    // callers/tests leave it null and stay on the cold-start band. Mirrors Swift. (#547)
    habitualMidsleepSec: Long? = null,
    // The strap's OWN persisted v18 BAND sleep_state per timestamp (Interpreter's `(sb shr 4) and 3`:
    // 0 wake/1 still/2 asleep/3 up). Consumed ONLY to confirm a borderline H7 morning re-onset — a
    // daytime block the strap itself scored "asleep" is kept even on a borderline HR dip (#531). Default
    // empty keeps pure-function callers/tests free of it; IntelligenceEngine threads the night window's
    // persisted band state. Mirrors Swift. (#531 / H8 consume)
    bandSleepState: List<Pair<Long, Int>> = emptyList(),
    // Opt-in experimental sleep staging (V2). When true, detected nights are staged by [SleepStagerV2]
    // instead of V1. Default false keeps V1 the byte-identical default for pure-function callers/tests;
    // IntelligenceEngine threads PuffinExperiment.from(context).experimentalSleepV2. Mirrors Swift. (V7 / #690)
    useSleepStagerV2: Boolean = false,
    // Sleep & Rest test-mode trace sink (E11). null = byte-identical default. When non-null the gate
    // trace from detectSleep and the Rest sub-score line are forwarded line-by-line. Mirrors Swift.
    traceSink: ((String) -> Unit)? = null,
    // HRV & Autonomic test-mode sink (#141). null = byte-identical default. When non-null, the nightly
    // per-5-min-window RMSSDs (tagged by sleep stage) + a whole-night vs deep-only vs last-SWS summary
    // are forwarded so an "HRV reads ~2x higher than WHOOP" report shows WHICH stages lift it.
    hrvTraceSink: ((String) -> Unit)? = null,
    // Whether to emit the ~90 per-window `hrv window …` lines (vs just the 1-line summary). The caller
    // sets it TRUE only for the most-recent night so the 5000-line ring buffer isn't flooded (21 nights ×
    // ~90 windows would evict the always-on diagnostics); the 1-line `hrv nightSummary` is kept for EVERY
    // night so the whole-night-vs-deep pattern is still visible across the week.
    hrvWindowDetail: Boolean = false,
    // #141: when true, the nightly HRV is RMSSD over DEEP-sleep windows only (WHOOP-style), instead of
    // the whole-night mean. Display-only preference threaded from the caller (UnitPrefs.hrvWindow). The
    // default (false) is byte-identical to the historical whole-night value.
    deepHrvWindow: Boolean = false,
): DayResult {

    // ── Sleep detection + staging ─────────────────────────────────────────
    val allSessions = SleepStager.detectSleep(
        hr = hr, rr = rr, resp = resp, gravity = gravity, tzOffsetSeconds = tzOffsetSeconds,
        wristOff = wristOff, bandSleepState = bandSleepState,
        useSleepStagerV2 = useSleepStagerV2,
        traceSink = traceSink,
    )
    // Sessions attributed to `day` = those whose end falls on `day` (LOCAL day, #277). `day` is
    // the caller's local-day key; attribute by the same offset so the bucket and the key agree.
    val matched = allSessions.filter { dayString(it.end, tzOffsetSeconds) == day }

    // ── The day's MAIN night (#525) ───────────────────────────────────────
    // A day can hold an overnight AND a daytime nap (both end on `day`, so both are in `matched`).
    // The sleep-DURATION figures (total sleep / stage minutes / efficiency / disturbances, hence the
    // Rest composite, the debt ledger, and the dashboard card) describe the MAIN night — the SAME
    // block the Sleep tab's hero shows (longest, preferring an overnight-anchored onset). They must
    // NOT silently sum the nap in, or the "your night" number disagrees across screens (the #525
    // report). Naps stay their OWN session rows in `sleepSessions`, where the Sleep tab lists and
    // labels them separately. [SleepStageTotals.mainNightIndex] is the single shared selector so the
    // analytics rollup and the Sleep tab resolve to the identical block.
    // Pick by the LEARNED-TIMING score, threading the user's learned habitual midsleep so a
    // late/shift sleeper's real night out-scores a daytime nap (null = cold-start overnight band).
    // BIPHASIC GAP-BRIDGE (#561): a main sleep briefly interrupted by a short wake (a fragment the
    // detector left split because the wake gap was longer than its sparse-gravity bridge, or a true
    // biphasic night) is scored as ONE night via [SleepStageTotals.mainNightGroupIndices]: it bridges
    // adjacent blocks whose gap is < `gapBridgeMaxMin`, scores the bridged span, and returns ALL the
    // fragments in the winning group. The AASM aggregate below then SUMS the group's stages — in-bed is
    // the SUM of each fragment's own in-bed span (the inter-fragment wake gap is NOT part of any fragment,
    // so it is excluded and we do NOT invent WASO for it). A day with no bridgeable gap collapses to the
    // single block the bare [mainNightIndex] would pick. Intelligence / the Ledger / the Sleep tab all
    // read this SAME group, so #525 does not regress. Mirrors Swift. (#525 / #561)
    val mainGroupIdx = SleepStageTotals.mainNightGroupIndices(
        matched.map { SleepStageTotals.NightBlock(it.start, it.end) },
        tzOffsetSeconds, habitualMidsleepSec,
    ) ?: emptyList()
    val mainGroup: List<DetectedSleep> = mainGroupIdx.map { matched[it] }

    // ── Daily sleep aggregates (AASM) SUMMED over the main-night GROUP (#525 / #561) ──
    var deepS = 0.0
    var remS = 0.0
    var lightS = 0.0
    var tstS = 0.0
    var inBedS = 0.0
    var effWeighted = 0.0
    var disturbances = 0
    for (s in mainGroup) {
        val m = SleepStager.hypnogramMetrics(s)
        val inBed = (s.end - s.start).toDouble()
        inBedS += inBed                       // each fragment's own in-bed span (the gap is added below)
        effWeighted += s.efficiency * inBed   // in-bed-weighted efficiency across the group
        deepS += m.deepMin * 60.0
        remS += m.remMin * 60.0
        lightS += m.lightMin * 60.0
        tstS += m.tstS
        disturbances += m.disturbances
    }
    // OUT-OF-BED time BETWEEN bridged fragments is AWAKE (#777/#705): a main night bridged from two
    // fragments split by a 20-min wake gap was reporting that gap as nowhere (it is in no fragment's
    // [start,end) span), so 20+ min of real awake read as ~4 min - a v7.1 regression, multi-reporter.
    // Fold the gap into AWAKE by extending the in-bed denominator (in-bed = asleep + awake; tstS is
    // unchanged), so efficiency and the Rest composite both reflect it. ONE shared definition with the
    // edit/recompute seam ([SleepStageTotals.interFragmentAwakeSeconds]), so the two paths agree and the
    // denominator is never double-counted. A bridged gap also counts as one disturbance. Mirrors Swift.
    val gapAwakeS = SleepStageTotals.interFragmentAwakeSeconds(mainGroup.map { it.start to it.end })
    if (gapAwakeS > 0.0) {
        inBedS += gapAwakeS                   // the gap is fully awake: extends in-bed, adds 0 to effWeighted
        disturbances += 1
    }
    val efficiency = if (inBedS > 0) effWeighted / inBedS else 0.0

    // #525 NOTE: the sleep-DURATION figures above are main-night-only (the headline "your night"),
    // but the physiological aggregates below (resting HR, HRV, respiration) intentionally stay over
    // ALL matched sessions. This is deliberate, not an oversight: recovery should reflect the body's
    // best resting physiology for the day, the main overnight dominates these anyway (it is far longer
    // than any nap and HRV is in-bed-weighted by duration), and narrowing them to the main night would
    // widen the change's blast radius into the recovery score right at a release boundary for a
    // negligible shift. The Rest/sleep-quality term is main-night; the recovery physiology is
    // day-best-resting, night-dominated. Mirrors the Swift note in AnalyticsEngine.swift.
    // Daily resting HR = lowest per-session resting HR across matched sessions.
    val restingHRDaily: Int? = matched.mapNotNull { it.restingHR }.minOrNull()
    // Daily avg HRV = in-bed-weighted mean of per-session avg HRV.
    val avgHRVDaily: Double? = if (deepHrvWindow) {
        // #141: WHOOP-style HRV — pool RMSSD over DEEP-stage 5-min windows only (slow-wave sleep),
        // instead of the whole-night mean below. Reuses the SAME sessionHrvWindows the HRV trace is
        // built from, so the displayed value equals the `deepOnly` figure the trace logs. rr is sorted
        // (RMSSD = successive diffs). null when the night has no detected deep sleep (WHOOP-4.0 staging
        // can be sparse) — the caller then shows calibrating, never a fabricated number.
        val rrSorted = rr.sortedBy { it.ts }
        val deep = matched.flatMap { s ->
            SleepStager.sessionHrvWindows(s.start, s.end, rrSorted, s.stages)
                .filter { it.stage == "deep" }.mapNotNull { it.rmssd }
        }
        if (deep.isEmpty()) null else deep.sum() / deep.size
    } else run {
        val pairs = matched.mapNotNull { s ->
            s.avgHRV?.let { it to (s.end - s.start).toDouble() }
        }
        if (pairs.isEmpty()) {
            null
        } else {
            val total = pairs.sumOf { it.first * it.second }
            val weight = pairs.sumOf { it.second }
            if (weight > 0) total / weight else null
        }
    }

    // ── HRV & Autonomic nightly trace (#141) ──────────────────────────────
    // Per-5-min-window RMSSD tagged by the sleep stage at its center, then a night summary comparing
    // NOOP's whole-night mean (what it reports) against a deep-only mean and a WHOOP-style
    // last-slow-wave-sleep value — so an "HRV reads ~2x higher than WHOOP" report shows WHICH stages
    // lift it, and lets a deep-sleep-windowed fix be validated before it ships. Reuses the SAME
    // sessionHrvWindows the value is built from (can't diverge). Zero cost when the sink is null.
    if (hrvTraceSink != null) {
        // sessionHrvWindows requires ts-sorted rr (RMSSD = successive diffs); the value path passes the
        // stager's pre-sorted rrS, so sort our own copy of the day's raw rr once here for the re-window.
        val rrSorted = rr.sortedBy { it.ts }
        val allWin = ArrayList<SleepStager.HrvWindow>()
        for (s in matched) {
            val wins = SleepStager.sessionHrvWindows(s.start, s.end, rrSorted, s.stages)
            if (hrvWindowDetail) {
                for (w in wins) {
                    hrvTraceSink(
                        "hrv window t=${(w.startTs - s.start) / 60}min stage=${w.stage} " +
                            "beats=${w.cleanBeats} rmssd=${w.rmssd?.let { "${round2(it)}ms" } ?: "nil"}",
                    )
                }
            }
            allWin.addAll(wins)
        }
        fun meanMs(ws: List<SleepStager.HrvWindow>): String {
            val v = ws.mapNotNull { it.rmssd }
            return if (v.isEmpty()) "nil" else "${round2(v.sum() / v.size)}ms"
        }
        val withR = allWin.filter { it.rmssd != null }
        val deepW = withR.filter { it.stage == "deep" }
        val lastSws = SleepStager.lastDeepRun(allWin).filter { it.rmssd != null }
        // `reported` is the value NOOP actually displays (duration-weighted session-mean-of-means);
        // `wholeNight` is the pooled-window mean it equals on single-session nights and the apples-to-
        // apples baseline for the deepOnly/lastSWS comparison (all three are pooled window means).
        hrvTraceSink(
            "hrv nightSummary reported=${avgHRVDaily?.let { "${round2(it)}ms" } ?: "nil"} " +
                "wholeNight=${meanMs(withR)} deepOnly=${meanMs(deepW)} " +
                "lastSWS=${meanMs(lastSws)} nWin=${withR.size} nDeep=${deepW.size}",
        )
    }

    // Nightly APPROXIMATE respiratory rate (breaths/min) from the R-R stream via
    // RSA. WHOOP5 v18 carries no raw resp ADC, so this is an on-device estimate,
    // NOT a cloud/clinical respiration value. Per matched in-bed session, estimate
    // over [start, end]; the night's value = median of finite per-session
    // estimates; null only when no session yields a finite estimate.
    val respRateDaily: Double? = run {
        val perSession = matched
            .map { SleepStager.respRateFromRR(rr, it.start, it.end) }
            .filter { it.isFinite() }
        if (perSession.isEmpty()) null else HrvAnalyzer.median(perSession)
    }

    // sleepStart/sleepEnd available for callers wiring sleep_start/end columns.
    @Suppress("UNUSED_VARIABLE") val sleepStart = matched.minOfOrNull { it.start }
    @Suppress("UNUSED_VARIABLE") val sleepEnd = matched.maxOfOrNull { it.end }

    // ── Skin-temperature deviation (offline) ──────────────────────────────
    // Wear-gated in-bed mean (baseline-independent, harvested every pass) + the deviation against
    // the personal baseline. In pass 1 baselines.skinTemp is null so the deviation is null and the
    // mean is harvested; IntelligenceEngine seeds the baseline from those means and re-derives the
    // deviation in pass 2 (mirrors avgHrv→recovery). Computed BEFORE Charge so the Charge skin-temp
    // penalty can read it. APPROXIMATE. (PR #85)
    val nightlySkinTempC = wornNightlySkinTempC(matched, hr, skinTemp, skinTempFamily)
    val skinTempDevC: Double? = nightlySkinTempC?.let { v ->
        baselines.skinTemp?.takeIf { it.usable }?.let { round2(Baselines.deviation(v, it).delta) }
    }

    // ── Raw SpO2 (WHOOP 4.0 v24 PPG ADC) ──────────────────────────────────
    // Nightly red/IR ADC means over the detected in-bed spans, or null when the night carried no raw
    // SpO2 samples in any span. Baseline-independent (unlike skin temp): a RAW device reading banked
    // as-is for the Health "Raw SpO2" tile — NOT a calibrated blood-oxygen %. (#93)
    val nightlySpo2Raw = nightlySpo2RawMeans(matched, spo2)

    // ── Rest (sleep_performance composite, 0–100) ─────────────────────────
    // Replaces the bare efficiency proxy: duration-vs-personal-need 0.50 + efficiency 0.20 +
    // restorative (deep+REM)/asleep 0.20 + consistency 0.10. Stored under the sleep_performance
    // key. null when no in-bed session. (Charge/Effort/Rest)
    val rest: Double? = if (matched.isEmpty()) null else RestScorer.rest(
        asleepSeconds = tstS,
        efficiency = efficiency,
        deepSeconds = deepS,
        remSeconds = remS,
        sleepNeedHours = sleepNeedHours,
        consistency = sleepConsistency,
    )
    // Sleep & Rest test mode (E11): emit the Rest sub-score breakdown for this night, reusing the
    // IDENTICAL inputs `rest` consumed above so the trace can never disagree with the score. Emitted
    // only when a trace is requested and this day scored a night. Mirrors Swift.
    if (traceSink != null && matched.isNotEmpty()) {
        traceSink(RestScorer.subScoreLine(
            tstSeconds = tstS, inBedSeconds = inBedS, efficiency = efficiency,
            restorativeSeconds = deepS + remS,
            needHours = sleepNeedHours ?: RestScorer.defaultSleepNeedHours,
            consistency = sleepConsistency, deepSeconds = deepS,
            groupFragments = mainGroup.size, groupInBedSeconds = inBedS))
    }

    // ── Recovery / Charge ─────────────────────────────────────────────────
    var recovery: Double? = null
    val hrvVal = avgHRVDaily
    val rhrVal = restingHRDaily
    val hrvBase = baselines.hrv
    if (hrvVal != null && rhrVal != null && hrvBase != null) {
        // Charge "Rest quality" term reads the Rest composite ÷100 (0..1), not raw efficiency.
        val sleepPerf = rest?.let { it / 100.0 }
        recovery = RecoveryScorer.recovery(
            hrv = hrvVal,
            rhr = rhrVal.toDouble(),
            resp = respRateDaily, // term drops + renormalizes when null / no baseline
            hrvBaseline = hrvBase,
            rhrBaseline = baselines.restingHR,
            respBaseline = baselines.resp,
            sleepPerf = sleepPerf,
            skinTempDev = skinTempDevC, // symmetric penalty; term drops + renormalizes when null
        )
    }

    // ── Strain ("Effort") — cardiovascular load over the full CALENDAR day ──
    // Integrate dayHr ([localMidnight, +24h), clamped to now for today) when supplied so Effort
    // covers the WHOLE day — an afternoon/evening workout lands in today's Effort same-day instead
    // of being cut off at the night window's ≈ noon bound, and the prior evening's HR no longer
    // bleeds in. Falls back to the night hr for pure-function callers/tests.
    val effMaxHR: Double? = maxHROverride
        ?: if (profile.age > 0) StrainScorer.tanakaHRmax(profile.age) else null
    val restForStrain = restingHRDaily?.toDouble() ?: StrainScorer.defaultRestingHR
    val strain = StrainScorer.strain(
        hr = dayHr ?: hr,
        maxHR = effMaxHR,
        restingHR = restForStrain,
        sex = profile.sex,
    )

    // ── Workouts ──────────────────────────────────────────────────────────
    // Detect over the full CALENDAR day (dayHr/dayGravity) when supplied so a current-day
    // afternoon/evening workout is caught on its own day rather than lagging until a later pass
    // re-reads it through the next night window (which ends at ≈ noon). Falls back to the night
    // window for pure-function callers/tests.
    val workouts = WorkoutDetector.detect(
        hr = dayHr ?: hr,
        gravity = dayGravity ?: gravity,
        restingHR = restingHRDaily?.toDouble(),
        maxHR = maxHROverride,
        age = if (profile.age > 0) profile.age else null,
        profile = profile,
    )

    // ── Steps (APPROXIMATE) ───────────────────────────────────────────────
    // step_motion_counter@57 is a CUMULATIVE u16 running counter (it climbs while you move, holds
    // flat when still, and wraps at 65536). The daily total is the SUM of WRAP-AWARE increments of
    // that counter across the time-ordered 1 Hz records (already ts-ASC from the DAO): delta =
    // (cur - prev) and 0xFFFF. The first record has no predecessor (contributes 0). The day's read
    // window may include adjacent-day samples, so filter to the LOCAL-day key
    // dayString(ts, tzOffset)==day first (#277).
    //
    // Reading byte @57 ALONE and summing it (the old bug, #132/#276/#316: exzanimo saw ~24× too
    // many steps) both ignored the high byte and summed a running total — exploding the count to
    // ~10M/day. Decoding the full u16 and summing wrap-aware DELTAS yields a sane ~14k. ESTIMATE
    // only — not cloud/clinical parity.
    val stepsTotal: Int? = run {
        // Prefer the full-calendar-day stream for the additive total; fall back to the
        // night-window stream when the caller didn't supply one (pure-function callers/tests).
        val sorted = (daySteps ?: steps).filter { dayString(it.ts, tzOffsetSeconds) == day }.sortedBy { it.ts }
        if (sorted.size < 2) return@run null
        // A delta this large is a big time-gap / disconnect boundary between sync sessions (or a
        // firmware reboot, byte-indistinguishable from a wrap), NOT real steps — drop it so gaps
        // don't inflate the total. Real 1 Hz motion never ticks this fast between adjacent records.
        val maxStepDelta = 512
        var total = 0L
        for (i in 1 until sorted.size) {
            val delta = (sorted[i].counter - sorted[i - 1].counter) and 0xFFFF // wrap-aware u16 increment
            if (delta in 1 until maxStepDelta) total += delta // ignore a delta >= 512 (gap/reset)
        }
        if (total <= 0L) return@run null
        // @57 counts motion ticks, not validated steps — the 5/MG counter overcounts. Divide
        // by the user-calibrated ticks-per-step (default 1.0 = raw pass-through; floor 0.5 so
        // a bad pref can at most double, never explode, the total). (#139)
        val scaled = (total.toDouble() / max(profile.stepTicksPerStep, 0.5)).roundToLong()
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (scaled > 0) scaled else null
    }

    // ── Daily calories (APPROXIMATE, HR-only whole-day estimate) ──────────
    // Whole-day active+resting energy from the full HR window, using the same resting/active
    // per-second model the per-workout estimate uses (resting BMR below activeThreshold, Keytel
    // active above). effMaxHR + restingHRDaily are the same effective HRmax / resting baseline
    // strain uses. Null when there is no HR. A heart-rate ESTIMATE — not cloud/clinical parity.
    // Whole-day additive totals (steps above, calories here) are summed over the full LOCAL
    // calendar day supplied by the caller (dayHr / daySteps), NOT the ~42h sleep-detection
    // window — which, anchored to the current time-of-day, would drop a past day's late hours
    // and double-count seconds shared with adjacent days. The filter uses the LOCAL-day key
    // (dayString(ts, tzOffset)) so it agrees with the bucket (#277). Fall back to the
    // night-window hr for pure-function callers that don't supply dayHr. Strain keeps the full
    // window (bounded log).
    val dayHrFiltered = (dayHr ?: hr).filter { dayString(it.ts, tzOffsetSeconds) == day }
    val activeKcalEst: Double? = if (dayHrFiltered.isEmpty()) {
        null
    } else {
        Calories.estimateDayCalories(
            hrSamples = dayHrFiltered,
            profile = profile,
            hrmax = effMaxHR,
            restingHR = restingHRDaily?.toDouble(),
        )
    }

    // ── Assemble DailyMetric ──────────────────────────────────────────────
    // deviceId is stamped by the caller (IntelligenceEngine persists under
    // "<deviceId>-noop"); use the imported source id as a placeholder here so
    // the value type is complete. The caller copies with its computed id.
    val daily = DailyMetric(
        deviceId = "",
        day = day,
        totalSleepMin = if (matched.isEmpty()) null else tstS / 60.0,
        efficiency = if (matched.isEmpty()) null else efficiency,
        deepMin = if (matched.isEmpty()) null else deepS / 60.0,
        remMin = if (matched.isEmpty()) null else remS / 60.0,
        lightMin = if (matched.isEmpty()) null else lightS / 60.0,
        disturbances = if (matched.isEmpty()) null else disturbances,
        restingHr = restingHRDaily,
        avgHrv = avgHRVDaily,
        recovery = recovery,
        strain = strain,
        exerciseCount = workouts.size,
        spo2Pct = null,
        skinTempDevC = skinTempDevC,
        respRateBpm = respRateDaily,
        steps = stepsTotal,
        activeKcalEst = activeKcalEst,
        spo2Red = nightlySpo2Raw?.first,
        spo2Ir = nightlySpo2Raw?.second,
    )

    // ── Per-score confidence tiers (mirror Swift ScoreConfidence.derive decisions) ──
    val chargeConfidence = ScoreConfidence.forCharge(recovery, baselines.hrv)
    val effortConfidence = ScoreConfidence.forEffort(strain, hr.size)
    // Rest confidence with H9: downgrade a high-efficiency night whose deep+REM share is implausibly low
    // to low-confidence (likely staging miss) — honest, no faked stages. tstS/efficiency are the
    // main-group totals computed above; restorative = deepS + remS. Mirrors Swift.
    val restConfidence = ScoreConfidence.forRest(
        hasSession = matched.isNotEmpty(),
        hasStagedSleep = (deepS + remS) > 0,
        asleepSeconds = tstS,
        restorativeSeconds = deepS + remS,
        efficiency = efficiency,
    )

    // ── Per-session per-epoch motion (H8) ─────────────────────────────────
    // The strap's per-epoch movement on the SAME 30 s grid as each session's stages, for the caller to
    // persist beside `stagesJSON`. A session that can't grid (too little gravity) is omitted, so the
    // caller persists NULL there rather than a fabricated zero series. Mirrors Swift.
    val sessionMotionByStart = HashMap<Long, List<Double>>()
    for (s in matched) {
        val motion = SleepStager.sessionEpochMotion(s.start, s.end, gravity)
        if (motion.isNotEmpty()) sessionMotionByStart[s.start] = motion
    }

    // ── Per-session per-epoch BAND sleep_state (#175) ─────────────────────
    // Grid the strap's OWN band sleep_state (the SAME [bandSleepState] samples the H7 guard consumes)
    // onto each matched session's 30 s epochs, for the caller to persist beside `stagesJSON`. This is the
    // source the band-state chain lacked (persist → next pass's H7 re-onset CONFIRM). A session whose
    // window carries no band samples is omitted (no key) → the caller persists NULL, an absent signal
    // stays absent. Empty on a WHOOP 4.0. The band code is carried verbatim; it NEVER overrides the
    // derived hypnogram, only confirms a borderline morning re-onset. Mirrors Swift.
    val sessionSleepStateByStart = HashMap<Long, List<Int>>()
    if (bandSleepState.isNotEmpty()) {
        for (s in matched) {
            val states = SleepStager.sessionEpochSleepState(s.start, s.end, bandSleepState)
            if (states.isNotEmpty()) sessionSleepStateByStart[s.start] = states
        }
    }

    return DayResult(
        daily = daily,
        sleepSessions = matched,
        workouts = workouts,
        recovery = recovery,
        strain = strain,
        rest = rest,
        nightlySkinTempC = nightlySkinTempC,
        chargeConfidence = chargeConfidence,
        effortConfidence = effortConfidence,
        restConfidence = restConfidence,
        sessionMotionByStart = sessionMotionByStart,
        sessionSleepStateByStart = sessionSleepStateByStart,
    )
}
