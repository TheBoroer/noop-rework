package com.noop.util

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Locale-independent fixed-decimal formatting, replacement for
 * String.format(Locale.US, "%.Nf", x) on JVM-only paths. HALF_UP rounding
 * (away from zero on ties), always exactly [decimals] fraction digits.
 * Finite inputs only; the callers this replaces never pass NaN/Inf.
 */
fun Double.toFixed(decimals: Int): String {
    require(decimals in 0..9) { "decimals out of range: $decimals" }
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val negative = this < 0.0 || (this == 0.0 && 1.0 / this < 0.0)
    val scaled = abs(this) * scale
    val units = scaled.roundToLong()
    val whole = units / scale
    val frac = units % scale
    val sign = if (negative && units != 0L) "-" else if (negative) "-" else ""
    return if (decimals == 0) "$sign$whole"
    else "$sign$whole.${frac.toString().padStart(decimals, '0')}"
}
