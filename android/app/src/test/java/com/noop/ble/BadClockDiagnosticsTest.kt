package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #324: pins the pure bad-clock log formatters (port of upstream 694ae229's BadClockDiagnosticsTest).
 * Deterministic — `now` is injected, all output UTC — so the Backfiller's dropped-record span clause
 * can never drift with the test machine's zone or clock.
 */
class BadClockDiagnosticsTest {

    private val now = 1_752_000_000L   // 2025-07-08T18:40:00Z

    @Test
    fun isoDayIsUtc() {
        // 2026-01-01T00:00:00Z — one second either side lands on different UTC days.
        assertEquals("2026-01-01", BadClockDiagnostics.isoDay(1_767_225_600L))
        assertEquals("2025-12-31", BadClockDiagnostics.isoDay(1_767_225_599L))
    }

    @Test
    fun hoursOffsetWordsAheadBehindAndNow() {
        assertEquals("26445h ahead", BadClockDiagnostics.hoursOffset(now + 26_445L * 3600L, now))
        assertEquals("512h behind", BadClockDiagnostics.hoursOffset(now - 512L * 3600L, now))
        // Within an hour either side reads "~now" (magnitude below 1h truncates to 0).
        assertEquals("~now", BadClockDiagnostics.hoursOffset(now + 3599L, now))
        assertEquals("~now", BadClockDiagnostics.hoursOffset(now - 3599L, now))
    }

    @Test
    fun spanClauseEmptyWhenNothingDropped() {
        assertEquals("", BadClockDiagnostics.droppedSpanClause(null, null, now))
        // Half-known (shouldn't happen, but the guard is total): still empty, never a broken clause.
        assertEquals("", BadClockDiagnostics.droppedSpanClause(1L, null, now))
        assertEquals("", BadClockDiagnostics.droppedSpanClause(null, 1L, now))
    }

    @Test
    fun spanClauseSingleDateWhenOldestEqualsNewest() {
        val ts = 1_767_225_600L   // 2026-01-01Z
        assertEquals(
            " (dated 2026-01-01, ${BadClockDiagnostics.hoursOffset(ts, now)})",
            BadClockDiagnostics.droppedSpanClause(ts, ts, now),
        )
    }

    @Test
    fun spanClauseRangeWordsTheNewestOffset() {
        // The reporter's #324 shape: a poisoned range years ahead; offset words the NEWEST (frontier).
        val oldest = 1_845_072_000L   // 2028-06-20Z
        val newest = 1_878_681_600L   // 2029-07-14Z
        val clause = BadClockDiagnostics.droppedSpanClause(oldest, newest, now)
        assertEquals(
            " (dated 2028-06-20 -> 2029-07-14, ${BadClockDiagnostics.hoursOffset(newest, now)})",
            clause,
        )
    }
}
