// TASK8: re-hoist after data hoist
package com.noop.analytics

/**
 * The full analysis result for one day. Mirrors Swift `AnalyticsEngine.DayResult`.
 *
 * NOTE: [daily] is the Room entity com.noop.data.DailyMetric (cache shape, with
 * recovery/strain/sleep rolled up). The detected sleep sessions are the analytics
 * [DetectedSleep] shape; persistence to com.noop.data.SleepSession rows is wired
 * by the caller (the engine port maps DetectedSleep → SleepSession when upserting).
 *
 * Split out of AnalyticsModels.kt (which stays commonMain): this is the ONLY shape in that
 * file with a direct `com.noop.data` dependency ([daily]), so it alone stays androidMain-only
 * until Task 8 hoists `com.noop.data`.
 */
data class DayResult(
    /** DailyMetric in the Room cache shape (recovery/strain/sleep rolled up). */
    val daily: com.noop.data.DailyMetric,
    /** Detected sleep sessions (rich, with stage segments). */
    val sleepSessions: List<DetectedSleep>,
    /** Detected workout/exercise sessions. */
    val workouts: List<ExerciseSession>,
    /** Charge (recovery) score [0,100] or null (cold-start / no HRV baseline). */
    val recovery: Double?,
    /** Effort (strain) score [0,100] or null (insufficient HR samples / invalid HRR). */
    val strain: Double?,
    /**
     * Rest (sleep_performance) composite [0,100] or null (no in-bed session). The persistence /
     * series layer stores this under the `sleep_performance` key. Replaces the bare efficiency
     * proxy (duration-vs-need 0.50 + efficiency 0.20 + restorative 0.20 + consistency 0.10).
     */
    val rest: Double? = null,
    /**
     * Wear-gated mean in-bed skin temperature (°C) for this night, or null when no worn in-bed
     * samples were available. Baseline-INDEPENDENT (like avgHrv): the caller seeds a personal
     * skin-temp baseline from these nightly means and re-derives [com.noop.data.DailyMetric.skinTempDevC]
     * in a second pass. APPROXIMATE. (PR #85)
     */
    val nightlySkinTempC: Double? = null,
    /** Per-score certainty tier for Charge (recovery). Mirrors Swift. */
    val chargeConfidence: ScoreConfidence = ScoreConfidence.CALIBRATING,
    /** Per-score certainty tier for Effort (strain). Mirrors Swift. */
    val effortConfidence: ScoreConfidence = ScoreConfidence.CALIBRATING,
    /** Per-score certainty tier for Rest (sleep_performance composite). Mirrors Swift. */
    val restConfidence: ScoreConfidence = ScoreConfidence.CALIBRATING,
    /**
     * Per-session per-epoch MOTION magnitudes (H8), keyed by each matched session's detected start
     * ([DetectedSleep.start]), on the same 30 s epoch grid as that session's `stagesJSON`. The caller
     * persists these via `WhoopRepository.persistSessionMotion` after upserting the sleep-session rows. A
     * session with too little gravity to grid is OMITTED (no key), so the caller never persists a fabricated
     * zero series. Mirrors Swift `DayResult.sessionMotionByStart`. (H8)
     */
    val sessionMotionByStart: Map<Long, List<Double>> = emptyMap(),
    /**
     * Per-session per-epoch BAND sleep_state (#175), keyed by each matched session's detected start, on the
     * same 30 s grid as `stagesJSON` / [sessionMotionByStart]. The strap's OWN @81 code (0 wake/1 still/2
     * asleep/3 up) gridded per session, for the caller to persist via `WhoopRepository.persistSessionSleepState`.
     * A session with no band-state samples is OMITTED (no key), so the caller persists NULL there rather than a
     * fabricated array. Feeds the H7 re-onset CONFIRM guard on the NEXT pass; never overrides the derived
     * hypnogram. Empty on a WHOOP 4.0. Mirrors Swift `DayResult.sessionSleepStateByStart`. (#175)
     */
    val sessionSleepStateByStart: Map<Long, List<Int>> = emptyMap(),
)
