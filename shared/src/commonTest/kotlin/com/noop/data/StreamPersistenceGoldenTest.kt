package com.noop.data

import com.noop.analytics.SleepStageTotals
import com.noop.analytics.SleepWindowReclip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Golden-output tests for the Task 4 org.json -> kotlinx-serialization port. `StreamPersistence.
 * encodePayload`'s output is PERSISTED in Room and doubles as a cross-platform (Swift `JSONEncoder.
 * sortedKeys`) natural-key dedupe input (see its own KDoc), so a stored string an OLD (org.json) app
 * version wrote must still decode correctly under the new decoders here, and a string the new
 * encoders write must still be exactly what an old (org.json) reader expects. Every literal below was
 * harvested by running the PRE-PORT (still-androidMain, org.json-backed) implementations directly
 * (via a scratch androidUnitTest, `GoldenHarvestScratch.kt`, deleted once these literals were copied
 * over; the exact org.json:json:20240303 quoting/number-formatting algorithms were additionally
 * confirmed by decompiling the pinned jar, see JsonCompat.kt), not written from memory.
 *
 * Golden strategy per function, decided while harvesting (raw string vs structural, and why):
 *
 *   - [StreamPersistence.encodePayload]: RAW STRING equality. Its key order comes from a manual
 *     `Map.keys.sorted()` (this function never built the JSON via org.json's own `JSONObject`, which
 *     does NOT guarantee order -- confirmed by decompiling the pinned jar: `JSONObject` is backed by a
 *     plain `HashMap`), so the output order was ALREADY deterministic pre-port; only the per-value
 *     quoting changed hands (org.json's `JSONObject.quote` -> [com.noop.util.orgJsonQuote]).
 *
 *   - [SleepWindowReclip.reclip] (segment-array shape): STRUCTURAL compare on the encode side. Each
 *     emitted segment was a 3-key `JSONObject` built via org.json's `.put(...)` chain; decompiling
 *     confirmed `JSONObject`'s backing `HashMap` does NOT preserve insertion order (the harvested
 *     order was "stage","start","end" -- NOT the `.put()` call order "start","end","stage") -- an
 *     implementation accident, never a real contract, since JSON objects are order-independent by
 *     spec. [reclipSegmentsDecodesOldHashMapOrderedStoredString] additionally feeds the exact harvested
 *     (HashMap-ordered) raw string back in as INPUT, proving the new key-lookup decoder reads an old
 *     stored row identically regardless of that incidental order.
 *
 *   - [SleepWindowReclip.reclip] (minute-dict shape): STRUCTURAL compare, same HashMap-order reasoning,
 *     PLUS a numeric-formatting pin this golden caught: org.json's `JSONObject.put(String, double)`
 *     trims an integral double's trailing ".0" ("80.0" -> "80"); kotlinx-serialization's own
 *     `JsonPrimitive(Double)` does NOT (confirmed by decompiling `kotlinx-serialization-json-jvm-1.8.1
 *     .jar`: `JsonLiteral`'s constructor just calls `Object.toString()`). The port therefore uses
 *     [com.noop.util.orgJsonDoubleString] (a byte-identical port of org.json's `doubleToString`)
 *     instead of relying on kotlinx's default, and [reclipMinutesFracExtendPinsNumericFormatting] pins
 *     the exact new-output bytes (integral totals bare, "11.5" untouched) as a standalone regression
 *     guard, not just a structural compare.
 *
 *   - [SleepStageTotals.minutes]: DECODE golden (the brief's "one decode golden" requirement) in both
 *     shapes the function accepts: a stored segment-array string and a stored minute-dict string, each
 *     parsed to the expected [SleepStageTotals.Minutes].
 *
 * "unicode + quotes" representative input applies cleanly only to `encodePayload` (the only encoder
 * here that accepts free-text string VALUES; `SleepWindowReclip`'s only string field is the closed
 * "stage" vocabulary {wake,light,deep,rem}, which never exercises escaping under either encoder). For
 * `SleepWindowReclip` the third representative input is instead a structural edge case per shape
 * (segment shape: window GREW, trailing "wake" appended; minute shape: a fractional-minute growth),
 * documented on each test below.
 */
class StreamPersistenceGoldenTest {

    // ── StreamPersistence.encodePayload: RAW STRING (deterministic sorted-key order) ──────────────

    @Test
    fun encodePayloadTypical() {
        val payload = mapOf(
            "battery_pct" to 87,
            "charging" to true,
            "note" to null,
            "rr_intervals" to listOf(700, 712, 698),
            "label" to "wrist_on",
        )
        assertEquals(
            """{"battery_pct":87,"charging":true,"label":"wrist_on","note":null,"rr_intervals":[700,712,698]}""",
            StreamPersistence.encodePayload(payload),
        )
    }

    @Test
    fun encodePayloadEmpty() {
        assertEquals("{}", StreamPersistence.encodePayload(emptyMap()))
    }

    @Test
    fun encodePayloadUnicodeQuotesAndOrgJsonSpecialEscapes() {
        // Exercises: a literal quote + backslash, \n \t \r, the "</"-breakout guard (org.json escapes
        // a '/' immediately after '<'), U+2028/U+2029 (org.json's General Punctuation block escape,
        // which also nets U+2014 em dash, though none appears here), and an astral-plane emoji (passes
        // through raw as its UTF-16 surrogate pair under both org.json and this port).
        val payload = mapOf(
            "msg" to "hé \"quoted\" \\ line1\nline2\ttab\rcr </script>    😀",
        )
        val expected = """{"msg":"hé \"quoted\" \\ line1\nline2\ttab\rcr <\/script> \u2028\u2029 😀"}"""
        assertEquals(expected, StreamPersistence.encodePayload(payload))
    }

    // ── SleepWindowReclip.reclip, segment-array shape: STRUCTURAL (HashMap key order was never a
    //    contract) ───────────────────────────────────────────────────────────────────────────────

    private data class Seg(val start: Long, val end: Long, val stage: String)

    private fun segments(json: String?): List<Seg> {
        val arr = Json.parseToJsonElement(json!!) as JsonArray
        return arr.map { el ->
            val o = el as JsonObject
            Seg((o.getValue("start") as JsonPrimitive).long, (o.getValue("end") as JsonPrimitive).long, (o.getValue("stage") as JsonPrimitive).content)
        }
    }

    @Test
    fun reclipSegmentsTypicalTrimAndClip() {
        // Harvested pre-port raw output (HashMap order): [{"stage":"light","start":1000,"end":2000},
        // {"stage":"deep","start":2000,"end":2500}], same fixture as the pre-existing
        // SleepWindowReclipTest.segmentTrimDropsAndClips.
        val json = """
            [{"start":1000,"end":2000,"stage":"light"},
             {"start":2000,"end":3000,"stage":"deep"},
             {"start":3000,"end":4000,"stage":"wake"}]
        """.trimIndent()
        val out = SleepWindowReclip.reclip(json, 1000, 4000, 1000, 2500)
        assertEquals(
            listOf(Seg(1000, 2000, "light"), Seg(2000, 2500, "deep")),
            segments(out),
        )
    }

    @Test
    fun reclipSegmentsGrownWindowAppendsTrailingWake() {
        // Harvested pre-port raw output: [{"stage":"light","start":1000,"end":2000},
        // {"stage":"deep","start":2000,"end":3000},{"stage":"wake","start":3000,"end":3600}].
        val json = """
            [{"start":1000,"end":2000,"stage":"light"},
             {"start":2000,"end":3000,"stage":"deep"}]
        """.trimIndent()
        val out = SleepWindowReclip.reclip(json, 1000, 3000, 1000, 3600)
        assertEquals(
            listOf(Seg(1000, 2000, "light"), Seg(2000, 3000, "deep"), Seg(3000, 3600, "wake")),
            segments(out),
        )
    }

    @Test
    fun reclipSegmentsFullyTrimmedCollapsesToSingleWakeFill() {
        // Degenerate/edge case standing in for "empty" (no free-text field exists to vary here):
        // corrected wake lands before every stage. Harvested pre-port raw output:
        // [{"stage":"wake","start":1000,"end":1500}].
        val json = """[{"start":2000,"end":3000,"stage":"light"},{"start":3000,"end":4000,"stage":"deep"}]"""
        val out = SleepWindowReclip.reclip(json, 1000, 4000, 1000, 1500)
        assertEquals(listOf(Seg(1000, 1500, "wake")), segments(out))
    }

    @Test
    fun reclipGarbageInputReturnsNull() {
        assertNull(SleepWindowReclip.reclip("not json", 0, 1, 0, 1))
        assertNull(SleepWindowReclip.reclip(null, 0, 1, 0, 1))
    }

    @Test
    fun reclipSegmentsDecodesOldHashMapOrderedStoredString() {
        // The harvested pre-port raw output IS a real "old stored string": org.json's HashMap put
        // "stage" before "start" before "end". Feed it back in as INPUT (not just compare against it)
        // to prove the new key-lookup decoder (JsonObject.get, not positional) reads a genuinely
        // old-shaped row identically regardless of that incidental order.
        val oldStored = """[{"stage":"light","start":1000,"end":2000},{"stage":"deep","start":2000,"end":2500}]"""
        val out = SleepWindowReclip.reclip(oldStored, 1000, 2500, 1000, 2500)
        assertEquals(listOf(Seg(1000, 2000, "light"), Seg(2000, 2500, "deep")), segments(out))
    }

    // ── SleepWindowReclip.reclip, minute-dict shape: STRUCTURAL + a numeric-formatting pin ────────

    private fun minutesMap(json: String?): Map<String, Double> {
        val o = Json.parseToJsonElement(json!!) as JsonObject
        return o.mapValues { (_, v) -> (v as JsonPrimitive).double }
    }

    @Test
    fun reclipMinutesShrinkCascadesFromAwakeThenLight() {
        // Harvested pre-port raw output (HashMap order): {"deep":80,"light":190,"awake":0,"rem":90}:
        // same fixture as the pre-existing SleepWindowReclipTest.minutesTrimCascadesFromAwakeThenLight.
        // Pins the org.json integral-double trim too: "awake":0 (not "0.0"), "deep":80 (not "80.0").
        val json = """{"awake":30,"light":200,"deep":80,"rem":90}"""
        val out = SleepWindowReclip.reclip(json, 0, 8 * 3600, 0, 8 * 3600 - 40 * 60)
        assertEquals(mapOf("awake" to 0.0, "light" to 190.0, "deep" to 80.0, "rem" to 90.0), minutesMap(out))
    }

    @Test
    fun reclipMinutesExtendAddsToAwake() {
        // Harvested pre-port raw output: {"deep":80,"light":200,"awake":50,"rem":90}.
        val json = """{"awake":30,"light":200,"deep":80,"rem":90}"""
        val out = SleepWindowReclip.reclip(json, 0, 8 * 3600, 0, 8 * 3600 + 20 * 60)
        assertEquals(mapOf("awake" to 50.0, "light" to 200.0, "deep" to 80.0, "rem" to 90.0), minutesMap(out))
    }

    @Test
    fun reclipMinutesFracExtendPinsNumericFormatting() {
        // A 90s (1.5 min) growth: a genuine FRACTIONAL double alongside integral ones in the same
        // object. Harvested pre-port raw output: {"deep":50,"light":100,"awake":11.5,"rem":40}: the
        // structural compare below proves cross-version VALUE equivalence; the raw-string assertion
        // pins the new port's exact BYTES (via orgJsonDoubleString) as a standalone regression guard,
        // since a structural JSON compare alone can't tell "80" from "80.0" (both parse to 80.0).
        val json = """{"awake":10,"light":100,"deep":50,"rem":40}"""
        val out = SleepWindowReclip.reclip(json, 0, 3600, 0, 3600 + 90)!!
        assertEquals(mapOf("awake" to 11.5, "light" to 100.0, "deep" to 50.0, "rem" to 40.0), minutesMap(out))
        assertEquals("""{"awake":11.5,"light":100,"deep":50,"rem":40}""", out)
    }

    // ── SleepStageTotals.minutes: DECODE golden (both accepted stagesJSON shapes) ──────────────────

    @Test
    fun minutesDecodesSegmentArrayShape() {
        val segJson = """[{"start":1000,"end":1600,"stage":"light"},{"start":1600,"end":2200,"stage":"deep"}]"""
        assertEquals(
            SleepStageTotals.Minutes(awake = 0.0, light = 10.0, deep = 10.0, rem = 0.0),
            SleepStageTotals.minutes(segJson),
        )
    }

    @Test
    fun minutesDecodesMinuteDictShape() {
        val dictJson = """{"awake":5,"light":300,"deep":90,"rem":75}"""
        assertEquals(
            SleepStageTotals.Minutes(awake = 5.0, light = 300.0, deep = 90.0, rem = 75.0),
            SleepStageTotals.minutes(dictJson),
        )
    }
}
