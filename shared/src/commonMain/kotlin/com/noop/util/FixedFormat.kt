package com.noop.util

/**
 * Locale-independent fixed-decimal formatting, replacement for
 * String.format(Locale.US, "%.Nf", x) on JVM-only paths. HALF_UP rounding
 * (away from zero on ties), always exactly [decimals] fraction digits.
 * Finite inputs only; the callers this replaces never pass NaN/Inf.
 *
 * Matches the JVM by construction rather than by coincidence: it rounds the
 * same input the JVM's formatter rounds, the shortest round-trip decimal
 * string ([Double.toString], JVM-identical on Kotlin/Native too), not the raw
 * binary value. A binary double such as 3.3499999999999996 has no exact
 * decimal form; scaling it and rounding the scaled binary result (this
 * function's previous implementation) double-rounds and can disagree with
 * the JVM, which rounds the decimal digits directly. This implementation
 * instead: (1) takes [Double.toString], (2) expands any exponent form
 * (e.g. "1.0E7") into a plain digit string, (3) applies HALF_UP (ties away
 * from zero) to that digit string at the requested position, with carry
 * propagation. The sign is read off the formatted string so a negative
 * value that rounds to zero still renders with a leading "-", matching the
 * JVM (e.g. "%.1f" of -0.0 is "-0.0").
 */
fun Double.toFixed(decimals: Int): String {
    require(decimals in 0..9) { "decimals out of range: $decimals" }

    var text = this.toString()
    val negative = text.startsWith("-")
    if (negative) text = text.substring(1)

    // Split off the exponent, if any (e.g. "1.0E7", "1.23456789E10").
    val expIndex = text.indexOfFirst { it == 'e' || it == 'E' }
    val mantissa = if (expIndex >= 0) text.substring(0, expIndex) else text
    val exponent = if (expIndex >= 0) text.substring(expIndex + 1).toInt() else 0

    val dotIndex = mantissa.indexOf('.')
    val intPart = if (dotIndex >= 0) mantissa.substring(0, dotIndex) else mantissa
    val fracPart = if (dotIndex >= 0) mantissa.substring(dotIndex + 1) else ""
    val digits = intPart + fracPart
    val pointPos = intPart.length + exponent

    // Expand to a plain-decimal (integer part, fraction part) pair, no exponent.
    var intStr: String
    var fracStr: String
    when {
        pointPos <= 0 -> {
            intStr = "0"
            fracStr = "0".repeat(-pointPos) + digits
        }
        pointPos >= digits.length -> {
            intStr = digits + "0".repeat(pointPos - digits.length)
            fracStr = ""
        }
        else -> {
            intStr = digits.substring(0, pointPos)
            fracStr = digits.substring(pointPos)
        }
    }

    // Pad so index [decimals] (the round-decision digit) is always safe to read.
    if (fracStr.length <= decimals) fracStr = fracStr.padEnd(decimals + 1, '0')

    var keptFrac = fracStr.substring(0, decimals)
    val roundUp = fracStr[decimals] >= '5'

    if (roundUp) {
        val combined = (intStr + keptFrac).toCharArray()
        var i = combined.size - 1
        var carry = true
        while (i >= 0 && carry) {
            if (combined[i] == '9') {
                combined[i] = '0'
            } else {
                combined[i] = combined[i] + 1
                carry = false
            }
            i--
        }
        val result = if (carry) "1" + combined.concatToString() else combined.concatToString()
        val newIntLen = result.length - decimals
        intStr = result.substring(0, newIntLen)
        keptFrac = result.substring(newIntLen)
    }

    intStr = intStr.trimStart('0').ifEmpty { "0" }

    val sign = if (negative) "-" else ""
    return if (decimals == 0) "$sign$intStr" else "$sign$intStr.$keptFrac"
}
