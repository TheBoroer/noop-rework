package com.noop.util

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.systemTimeZone
import platform.Foundation.timeZoneWithName

/**
 * Apple actual (iOS + macOS, generalized from iosMain to appleMain in Phase 2b Task 1):
 * `NSDateFormatter`'s own SHORT time style - the same "device's own 12-/24-hour convention"
 * contract as the Android actual. Foundation ships with every Kotlin/Native Apple target (no extra
 * cinterop config needed; this repo already depends on it elsewhere, e.g. `NSTemporaryDirectory` in
 * `WhoopDatabaseSmokeTest`). `dateStyle` is forced to `.none` so the output is time-only, matching
 * `DateFormat.getTimeInstance`'s time-only contract on the Android side. `zoneId = null` uses
 * `NSTimeZone.systemTimeZone`, the Apple analogue of `TimeZone.getDefault()`.
 */
actual fun formatShortTime(epochSeconds: Long, zoneId: String?): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble())
    val formatter = NSDateFormatter()
    formatter.dateStyle = NSDateFormatterNoStyle
    formatter.timeStyle = NSDateFormatterShortStyle
    formatter.timeZone = if (zoneId != null) {
        NSTimeZone.timeZoneWithName(zoneId) ?: NSTimeZone.systemTimeZone
    } else {
        NSTimeZone.systemTimeZone
    }
    return formatter.stringFromDate(date)
}
