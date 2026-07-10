package com.noop.util

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cross-platform structural check only: [formatShortTime] is genuinely locale/device-dependent (a
 * SHORT-style clock string, 12- or 24-hour depending on the running device's own locale), so a
 * commonTest can't assert an exact string (see `LocalTimeFormatAndroidParityTest`, androidUnitTest,
 * for the exact-string parity pin under a fixed Locale/zone). What both platforms' actuals must
 * share regardless of locale: at least one digit, and at least one non-digit separator (":" for a
 * 24-hour clock, ":" plus a locale-specific AM/PM marker for a 12-hour one).
 */
class LocalTimeFormatTest {

    // 2026-06-26 12:02:00 UTC - same fixture instant used by the androidUnitTest parity tests.
    private val fixedEpochSeconds = 1_782_475_320L

    @Test
    fun formatShortTimeWithExplicitZoneHasDigitsAndASeparator() {
        val s = formatShortTime(fixedEpochSeconds, zoneId = "UTC")
        assertTrue(s.any { it.isDigit() }, "expected at least one digit in [$s]")
        assertTrue(s.any { !it.isDigit() }, "expected a non-digit separator in [$s]")
    }

    @Test
    fun formatShortTimeWithDefaultZoneHasDigitsAndASeparator() {
        val s = formatShortTime(fixedEpochSeconds, zoneId = null)
        assertTrue(s.any { it.isDigit() }, "expected at least one digit in [$s]")
        assertTrue(s.any { !it.isDigit() }, "expected a non-digit separator in [$s]")
    }
}
