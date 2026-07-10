package com.noop.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * org.json byte-parity helpers for the Task 4 kotlinx-serialization port of the persisted-payload
 * paths (`StreamPersistence.encodePayload`, `SleepWindowReclip.reclip`, `SleepStageTotals.minutes`).
 * These stored strings are read back by OLD app versions (still on org.json) and, for
 * `encodePayload`, double as a cross-platform (Swift `JSONEncoder.sortedKeys`) natural-key dedupe
 * input, so the new kotlinx-backed encoder must reproduce org.json's exact byte output, not just
 * "valid equivalent JSON". kotlinx-serialization-json's own defaults do NOT match org.json in two
 * ways verified by decompiling the pinned `org.json:json:20240303` jar (not from memory):
 *   1. string escaping ([orgJsonQuote]): org.json additionally backslash-escapes '/' immediately
 *      after '<' (its `</`-breakout HTML guard) and every char in [U+0080,U+00A0) or the whole
 *      General Punctuation block [U+2000,U+2100) (which includes U+2028/U+2029 AND, incidentally,
 *      U+2014 em dash) as `\u%04x`; kotlinx's default JSON string writer does neither.
 *   2. double formatting ([orgJsonDoubleString]): org.json trims an integral double's trailing
 *      ".0" (`80.0` -> `"80"`); kotlinx's `JsonPrimitive(Double)` just calls `Double.toString()`
 *      verbatim (`"80.0"`).
 * Both are used only where a golden proved the divergence would otherwise change stored bytes;
 * see StreamPersistenceGoldenTest / SleepWindowReclip's own doc comments for which call sites need
 * them (string quoting: every free-text value in `StreamPersistence.encodePayload`; double
 * formatting: `SleepWindowReclip.reclipMinutes`'s four minute totals). Elsewhere (Long values, and
 * the closed-vocabulary "stage" string which never contains a char either algorithm treats
 * differently) kotlinx's own `buildJsonObject`/`JsonPrimitive` defaults already match org.json
 * byte-for-byte, so this file is deliberately NOT a general org.json shim.
 */

/**
 * Byte-identical Kotlin port of `org.json.JSONObject.quote(String)` (via its `quote(String, Writer)`
 * core, decompiled from the pinned jar). Returns the value already wrapped in `"..."`, matching the
 * org.json signature (`encodePayload` splices this directly after a `:`).
 */
fun orgJsonQuote(s: String): String {
    if (s.isEmpty()) return "\"\""
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    var prev = 0.toChar()
    for (c in s) {
        when (c) {
            '\b' -> sb.append("\\b")
            '\t' -> sb.append("\\t")
            '\n' -> sb.append("\\n")
            '\u000C' -> sb.append("\\f")
            '\r' -> sb.append("\\r")
            '"', '\\' -> {
                sb.append('\\')
                sb.append(c)
            }
            '/' -> {
                // org.json's "</"-breakout guard: only escape '/' when the PRECEDING char was '<'.
                if (prev == '<') sb.append('\\')
                sb.append(c)
            }
            else -> {
                val code = c.code
                if (code < 0x20 || code in 0x80 until 0xA0 || code in 0x2000 until 0x2100) {
                    sb.append("\\u")
                    val hex = code.toString(16)
                    repeat(4 - hex.length) { sb.append('0') }
                    sb.append(hex)
                } else {
                    sb.append(c)
                }
            }
        }
        prev = c
    }
    sb.append('"')
    return sb.toString()
}

/**
 * Byte-identical Kotlin port of `org.json.JSONObject.doubleToString(double)` (decompiled from the
 * pinned jar): `Double.toString(d)`, then, only when the result has a decimal point and no exponent,
 * strip trailing `'0'`s and a then-trailing `'.'` (`"80.0"` -> `"80"`, `"11.5"` unchanged). NaN/
 * Infinite -> `"null"` (org.json's own sentinel; never hit by a real minute total, kept for parity).
 */
fun orgJsonDoubleString(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return "null"
    var s = d.toString()
    if (s.indexOf('.') > 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
        while (s.endsWith("0")) s = s.substring(0, s.length - 1)
        if (s.endsWith(".")) s = s.substring(0, s.length - 1)
    }
    return s
}

/**
 * org.json `JSONObject.optLong(key, default)`-equivalent: [default] when [key] is absent, null, or
 * not coercible to a `Long`. Every writer in this codebase stores minute/second fields as genuine
 * JSON numbers, so the deeper org.json leniency (parsing a JSON STRING like `"5"` as a number) is
 * intentionally not ported here: no stored payload in this app has ever needed it. Also un-ported:
 * org.json's optLong truncates a decimal-formatted numeric value (e.g. `80.0` to `80`), while this
 * port returns [default] for it instead; inert for current writers, since none emit
 * decimal-formatted integer fields.
 */
fun JsonObject.optLong(key: String, default: Long = 0L): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: default

/** org.json `JSONObject.optDouble(key, default)`-equivalent. See [optLong] for the leniency scope. */
fun JsonObject.optDouble(key: String, default: Double = 0.0): Double =
    (this[key] as? JsonPrimitive)?.doubleOrNull ?: default

/**
 * org.json `JSONObject.optString(key, default)`-equivalent, with one documented divergence: for an
 * explicit JSON null (org.json's `JSONObject.NULL` sentinel), org.json returns [default], but this
 * port returns the literal string `"null"` (from [JsonPrimitive.content] on a `JsonNull` element).
 * [default] is otherwise returned only when [key] is absent or not a primitive at all (a nested
 * array/object); any other present primitive's [JsonPrimitive.content] is returned verbatim.
 */
fun JsonObject.optString(key: String, default: String = ""): String =
    (this[key] as? JsonPrimitive)?.content ?: default
