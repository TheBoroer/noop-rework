package com.noop.analytics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * PHASE2 Task 7, Step 1: pins SleepMark's CURRENT (pre-port) locale/timezone-dependent output under
 * a FIXED Locale.US + a FIXED zone (America/New_York), written and run green against the original
 * java.text/java.util-based SleepMark.kt BEFORE any code change. SleepMark's own formatters
 * otherwise read the JVM's Locale.getDefault()/TimeZone.getDefault() (device-dependent, which is why
 * the pre-existing SleepMarkTest only checks structure, never an exact string). Forcing the JVM
 * defaults for the duration of each test here makes the SHORT-style clock string and the
 * yyyy-MM-dd day key deterministic, so the literals pinned here can be diffed byte-for-byte against
 * the kotlinx-datetime/formatShortTime port, unchanged, once that port lands.
 *
 * NOTE on the AM/PM literal: DateFormat.getTimeInstance(SHORT, Locale.US) on this repo's pinned JDK
 * (OpenJDK 21, CLDR locale data) separates the hour:minute from AM/PM with U+202F (NARROW NO-BREAK
 * SPACE), not a plain ASCII space - confirmed empirically (`java.text.DateFormat` probe) before
 * writing this literal, not assumed. A real Android device's ICU may render a plain space instead;
 * this test's job is only to pin what THIS JVM test target already produces, which is the exact
 * runtime the ported formatShortTime.android actual runs under whenever this same JVM test target
 * exercises it (the actual is the same two-line DateFormat.getTimeInstance recipe, unchanged).
 */
class SleepMarkLocaleFormatPinningTest {

    private lateinit var savedLocale: Locale
    private lateinit var savedZone: TimeZone

    @Before
    fun fixLocaleAndZone() {
        savedLocale = Locale.getDefault()
        savedZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun restoreLocaleAndZone() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    // 2026-06-26 12:02:00 UTC == 2026-06-26 08:02 AM America/New_York (EDT, UTC-4) - same calendar
    // day in both zones, deliberately, to isolate the clock-string parity check from the day-
    // boundary edge case (covered separately below).
    private val fixedTsMs = 1_782_475_320_000L

    @Test
    fun logLineClockStringPinnedUsLocaleNewYorkZone() {
        val bed = SleepMark(SleepMarkType.BEDTIME, fixedTsMs).logLine()
        assertEquals("Sleep mark · bedtime (going to sleep) @ 8:02 AM", bed)
    }

    @Test
    fun confirmationClockStringPinnedUsLocaleNewYorkZone() {
        val wake = SleepMark(SleepMarkType.WAKE, fixedTsMs).confirmation()
        assertEquals("Logged wake-up at 8:02 AM.", wake)
    }

    @Test
    fun dayKeyPinnedNewYorkZone() {
        val mark = SleepMark(SleepMarkType.BEDTIME, fixedTsMs)
        assertEquals("2026-06-26", mark.dayKey)
    }

    // 2026-06-27 02:30:00 UTC is still 2026-06-26 10:30 PM in America/New_York (EDT, UTC-4) - the
    // day key must resolve to the EARLIER local day, not the UTC day, proving dayFormatter's
    // TimeZone.getDefault() (not UTC) is what's pinned here.
    @Test
    fun dayKeyCrossesMidnightByLocalZoneNotUtc() {
        val crossMidnightTsMs = 1_782_527_400_000L // 2026-06-27 02:30:00 UTC
        val mark = SleepMark(SleepMarkType.BEDTIME, crossMidnightTsMs)
        assertEquals("2026-06-26", mark.dayKey)
    }

    @Test
    fun fromRowRoundTripsThroughDayKeyAtLocalMidnight() {
        val original = SleepMark(SleepMarkType.WAKE, fixedTsMs)
        val row = original.metricPoint("device-1")
        val restored = SleepMark.fromRow(row)
        assertEquals(SleepMarkType.WAKE, restored?.type)
        // fromRow resolves to that day's LOCAL midnight, not the original sub-day instant.
        assertEquals("2026-06-26", restored?.dayKey)
    }
}
