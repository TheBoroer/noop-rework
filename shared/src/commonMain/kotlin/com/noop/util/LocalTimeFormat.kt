package com.noop.util

/**
 * Locale-aware SHORT-style clock string for a moment in time - e.g. "11:42 PM" in a 12-hour locale,
 * "23:42" in a 24-hour one - the platform's own preferred short-time convention. This is the same
 * string SleepMark's log/toast lines showed before this Phase 2a KMP port
 * (`DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())`).
 *
 * This is genuinely a locale STYLE, not a fixed character pattern (contrast `ConnectionTrace.isoDate`,
 * which uses a fixed "yyyy-MM-dd HH:mm:ss" pattern and was mapped directly to kotlinx-datetime
 * formatting in commonMain, no expect/actual needed - see task-7-report.md). Neither kotlinx-datetime
 * nor the Kotlin stdlib expose locale-aware time formatting, so a real per-platform actual is
 * required: android reproduces `DateFormat.getTimeInstance(SHORT, Locale.getDefault())` exactly
 * (pinned byte-for-byte against the pre-port SleepMark code by SleepMarkLocaleFormatPinningTest /
 * LocalTimeFormatAndroidParityTest, androidUnitTest); iOS uses `NSDateFormatter` with
 * `timeStyle = .short`.
 *
 * [epochSeconds] is a unix instant (UTC seconds). [zoneId] is an IANA zone id (e.g.
 * "America/Toronto"); null uses the platform's current default zone (mirrors the pre-port
 * `TimeZone.getDefault()` behaviour).
 */
expect fun formatShortTime(epochSeconds: Long, zoneId: String?): String
