package com.noop.util

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [epochSecondsToLocalDate] at the UTC midnight boundary and across the unix epoch.
 * Constants verified against `date -u -r <epoch>` (not the task brief's originals, which were
 * off by exactly 5 days, see task-3-report.md): 1783555199 is 2026-07-08 23:59:59 UTC,
 * 1783555200 is 2026-07-09 00:00:00 UTC (today's date at authoring time).
 */
class TimeCompatTest {
    @Test
    fun epochToLocalDateAroundMidnightUtc() {
        val utc = TimeZone.UTC
        assertEquals("2026-07-08", epochSecondsToLocalDate(1783555199, utc).toString())  // 23:59:59 UTC
        assertEquals("2026-07-09", epochSecondsToLocalDate(1783555200, utc).toString())  // 00:00:00 UTC
    }

    @Test
    fun negativeEpochBeforeUnixZero() {
        assertEquals("1969-12-31", epochSecondsToLocalDate(-1, TimeZone.UTC).toString())
    }
}
