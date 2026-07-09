package com.noop.data

/**
 * DERIVED nap classification (#518) for a [SleepSession], computed at READ time, NO schema column /
 * migration. A block is a nap when it is SHORT (< [SleepSession.NAP_MAX_HOURS]) or DAYTIME-onset (onset
 * not in the overnight window). The day's MAIN sleep is resolved separately (the longest,
 * overnight-preferring block, see SleepScreen.mainSleepBlock); this flag only describes the block's own
 * shape, so the UI can label / count naps consistently with iOS SleepView.isNap. A long overnight
 * split-sleep block is NOT a nap.
 *
 * Extracted out of the [SleepSession] entity (Task 8 data hoist) because it needs the LOCAL hour-of-day
 * of [SleepSession.effectiveStartTs] via [java.util.Calendar], which has no commonMain-safe equivalent
 * without kotlinx-datetime (out of scope this phase); kept as an androidMain-only extension property
 * rather than a member so the entity itself (a plain data-holder, no persisted column either way) can
 * live in commonMain. Currently unreferenced elsewhere in the codebase.
 */
val SleepSession.isNapShaped: Boolean
    get() {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = effectiveStartTs * 1000L }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val overnightOnset = h >= 20 || h < 10
        return durationHours < SleepSession.NAP_MAX_HOURS || !overnightOnset
    }
