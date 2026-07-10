package com.noop.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Android actual: reproduces SleepMark's pre-port `clockFormatter()` recipe exactly -
 * `DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())` with the target zone applied.
 * `zoneId = null` uses `TimeZone.getDefault()`, matching the original's always-local-zone behaviour.
 * Pinned byte-for-byte by `SleepMarkLocaleFormatPinningTest` (against the original code, before this
 * port) and `LocalTimeFormatAndroidParityTest` (against this actual directly), both androidUnitTest,
 * both under a fixed Locale.US + a fixed zone.
 */
actual fun formatShortTime(epochSeconds: Long, zoneId: String?): String {
    val formatter = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
    formatter.timeZone = if (zoneId != null) TimeZone.getTimeZone(zoneId) else TimeZone.getDefault()
    return formatter.format(Date(epochSeconds * 1000L))
}
