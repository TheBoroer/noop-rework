package com.noop.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * PHASE2 Task 7, Step 2: [formatShortTime]'s android actual must reproduce exactly what
 * SleepMark's pre-port `DateFormat.getTimeInstance(SHORT, Locale.getDefault())` call produced -
 * pinned against the original code by `SleepMarkLocaleFormatPinningTest` before this port. Same
 * fixed instant, same forced Locale.US + America/New_York zone, so the literals below are the exact
 * ones that test already established as correct for this JVM test target.
 *
 * The hour:minute / AM-PM separator in the literals below is U+202F (NARROW NO-BREAK SPACE),
 * not a plain ASCII space - this JDK's CLDR locale data uses it for Locale.US SHORT time
 * (confirmed empirically before writing this test, not assumed; see
 * SleepMarkLocaleFormatPinningTest's note).
 */
class LocalTimeFormatAndroidParityTest {

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

    // Same fixed instant as SleepMarkLocaleFormatPinningTest: 2026-06-26 12:02:00 UTC.
    private val fixedEpochSeconds = 1_782_475_320L

    @Test
    fun nullZoneIdUsesJvmDefaultZone() {
        assertEquals("8:02 AM", formatShortTime(fixedEpochSeconds, zoneId = null))
    }

    @Test
    fun explicitZoneIdOverridesDefault() {
        assertEquals("12:02 PM", formatShortTime(fixedEpochSeconds, zoneId = "UTC"))
    }
}
