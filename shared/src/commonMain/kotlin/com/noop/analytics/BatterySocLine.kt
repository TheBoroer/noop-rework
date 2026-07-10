package com.noop.analytics

import com.noop.util.toFixed

/** Pure formatter for the Battery test mode's per-sample (t, soc) log line, kept out of the
 *  Android-bound BLE client so it is JVM-unit-testable. Matches the Swift "bank soc=.. t=..s" shape
 *  (#713, Test Centre). No em-dashes. */
object BatterySocLine {
    fun format(pct: Double, tSeconds: Long): String =
        "bank soc=${pct.toFixed(1)} t=${tSeconds}s"
}
