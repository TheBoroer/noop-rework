package com.noop.analytics

import com.noop.util.optDouble
import com.noop.util.optLong
import com.noop.util.optString
import com.noop.util.orgJsonDoubleString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Reshape a sleep session's stored stage breakdown to a hand-corrected [newStart]..[newEnd] window, so a
 * bed-time (onset) and/or wake-time edit updates the hypnogram and the stage footer, not just the
 * displayed "Woke" / "Bed" label. Pure + deterministic (no store, no raw signals, no I/O). Port of
 * SleepWindowReclip.swift.
 *
 * START-AWARE on BOTH ends (#0): a pure onset edit (newStart moves, newEnd unchanged) must drop the
 * stages BEFORE the corrected bed time, not leave them in place. Otherwise an imported / pre-sync night
 * keeps sleep that happened before the user got into bed while the displayed window shrank.
 *
 * Two stagesJSON formats (matching the two writers):
 *   • Segment array `[{"start":epoch,"end":epoch,"stage":"wake"|"light"|"deep"|"rem"}]` — computed
 *     nights. Clip to [newStart]..[newEnd]: drop segments wholly outside it, clip a straddling
 *     segment's start up to [newStart] and end down to [newEnd]; if the window grew at the tail,
 *     append a trailing "wake" segment (extra time in bed reads as awake).
 *   • Minute dict `{"awake":…,"light":…,"deep":…,"rem":…}` — imported nights. No timeline, so
 *     shift by the duration delta `(newEnd - newStart) - (oldEnd - sessionStart)`: trim from the
 *     tail-most stages (awake→light→rem→deep) when shortened, add to awake when lengthened.
 *
 * Returns re-encoded JSON in the SAME shape received, or null when there is nothing usable to
 * reclip (callers then keep the existing JSON).
 */
object SleepWindowReclip {

    fun reclip(stagesJSON: String?, sessionStart: Long, oldEnd: Long, newStart: Long, newEnd: Long): String? {
        stagesJSON ?: return null
        return try {
            // Parse ONCE and branch on the decoded shape (JsonArray vs JsonObject), rather than the
            // original's string-prefix sniff + a second, shape-specific parse: `Json.parseToJsonElement`
            // rejects malformed input the same way `JSONArray(...)`/`JSONObject(...)` did (caught below,
            // -> null), and a well-formed-but-neither-array-nor-object payload (e.g. a bare number) falls
            // into the same `else -> null` the original's prefix check would have taken without parsing.
            when (val element = Json.parseToJsonElement(stagesJSON)) {
                is JsonArray -> reclipSegments(element, newStart, newEnd)
                is JsonObject -> reclipMinutes(element, (newEnd - newStart) - (oldEnd - sessionStart))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun reclipSegments(arr: JsonArray, newStart: Long, newEnd: Long): String? {
        val out = mutableListOf<JsonObject>()
        var maxEnd = newStart
        for (element in arr) {
            val seg = element as? JsonObject ?: continue
            val start = seg.optLong("start", -1)
            val end = seg.optLong("end", -1)
            val stage = seg.optString("stage", "")
            if (start < 0 || end <= start || stage.isEmpty()) continue
            if (start >= newEnd) continue                        // wholly after new wake → drop
            if (end <= newStart) continue                        // wholly before the new bed time → drop
            val clippedStart = maxOf(start, newStart)            // clip the segment spanning the new bed time
            val clippedEnd = minOf(end, newEnd)
            out.add(buildJsonObject { put("start", clippedStart); put("end", clippedEnd); put("stage", stage) })
            if (clippedEnd > maxEnd) maxEnd = clippedEnd
        }
        if (newEnd > maxEnd && maxEnd >= newStart) {             // window grew → trailing awake
            out.add(buildJsonObject { put("start", maxEnd); put("end", newEnd); put("stage", "wake") })
        }
        // If every segment trimmed away, emit a single wake covering the corrected window so the
        // store's COALESCE doesn't keep the old segments extending past the new wake time.
        if (out.isEmpty() && newEnd > newStart) {
            out.add(buildJsonObject { put("start", newStart); put("end", newEnd); put("stage", "wake") })
        }
        // Key order here (start, end, stage) is the insertion order this function itself chooses; the
        // OLD org.json output's order was HashMap-arbitrary, never a real contract (JSON objects are
        // order-independent by spec) -- confirmed structurally (not byte) equal to the pre-port output
        // in StreamPersistenceGoldenTest. Longs need no org.json-parity formatter (Long.toString() and
        // org.json's integer `put` already agree byte-for-byte); "stage" is a closed 4-value vocabulary
        // that never hits either encoder's escaping rules, so kotlinx's default JsonPrimitive(String) is
        // already byte-identical to org.json's `quote()` for every value this field ever holds.
        return if (out.isNotEmpty()) JsonArray(out).toString() else null
    }

    private fun reclipMinutes(dict: JsonObject, deltaSeconds: Long): String? {
        var awake = dict.optDouble("awake", 0.0)
        var light = dict.optDouble("light", 0.0)
        var deep = dict.optDouble("deep", 0.0)
        var rem = dict.optDouble("rem", 0.0)
        val deltaMin = deltaSeconds / 60.0
        if (deltaMin >= 0) {
            awake += deltaMin                                     // extra time in bed = awake
        } else {
            var trim = -deltaMin
            fun cut(v: Double): Double { val c = minOf(v, maxOf(trim, 0.0)); trim -= c; return v - c }
            awake = cut(awake); light = cut(light); rem = cut(rem); deep = cut(deep)
        }
        val total = awake + light + deep + rem
        if (total <= 0.0) return null
        // Manual string build, NOT buildJsonObject/JsonPrimitive(Double): kotlinx's default Double
        // serialization keeps a trailing ".0" for an integral value ("80.0"), diverging from org.json's
        // `JSONObject.put(String, double)` ("80") -- a byte-for-byte legacy-parity gap the golden test
        // caught (see StreamPersistenceGoldenTest / JsonCompat.kt). [orgJsonDoubleString] reproduces
        // org.json's exact trim algorithm so an integral minute total stays "80", not "80.0", matching
        // every existing stored row and every old (org.json) reader. Key order (awake, light, deep, rem)
        // mirrors this function's own field order; the OLD org.json order was HashMap-arbitrary (never a
        // real contract), confirmed structurally equal to the pre-port output in the golden test.
        return "{\"awake\":${orgJsonDoubleString(awake)}," +
            "\"light\":${orgJsonDoubleString(light)}," +
            "\"deep\":${orgJsonDoubleString(deep)}," +
            "\"rem\":${orgJsonDoubleString(rem)}}"
    }
}
