package com.noop.analytics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pure-function tests for [SleepWindowReclip], the Android twin of
 * Packages/StrandAnalytics/Tests/StrandAnalyticsTests/SleepWindowReclipTests.swift.
 *
 * Covers both the segment-array (computed) and minute-dict (imported) paths, and the START-AWARE
 * reclip (#0): a pure bed-time (onset) edit must drop / clip the stages BEFORE the corrected bed time,
 * not just trim the tail.
 */
class SleepWindowReclipTest {

    private data class Seg(val start: Long, val end: Long, val stage: String)

    private fun segments(json: String): List<Seg> {
        val arr = Json.parseToJsonElement(json) as JsonArray
        return arr.map { el ->
            val o = el as JsonObject
            Seg(
                (o.getValue("start") as JsonPrimitive).long,
                (o.getValue("end") as JsonPrimitive).long,
                (o.getValue("stage") as JsonPrimitive).content,
            )
        }
    }

    private fun minutes(json: String): Map<String, Double> {
        val o = Json.parseToJsonElement(json) as JsonObject
        return o.mapValues { (_, v) -> (v as JsonPrimitive).double }
    }

    // ── segment array (computed nights) ──────────────────────────────────────────────────────────

    @Test
    fun segmentTrimDropsAndClips() {
        val json = """
            [{"start":1000,"end":2000,"stage":"light"},
             {"start":2000,"end":3000,"stage":"deep"},
             {"start":3000,"end":4000,"stage":"wake"}]
        """.trimIndent()
        val out = SleepWindowReclip.reclip(json, 1000, 4000, 1000, 2500)!!
        val segs = segments(out)
        assertEquals(2, segs.size, "the wholly-after segment is dropped")
        assertEquals("light", segs[0].stage)
        assertEquals("deep", segs[1].stage)
        assertEquals(2500, segs[1].end, "the segment spanning the new wake is clipped to it")
    }

    @Test
    fun segmentExtendAppendsTrailingWake() {
        val json = """
            [{"start":1000,"end":2000,"stage":"light"},
             {"start":2000,"end":3000,"stage":"deep"}]
        """.trimIndent()
        val out = SleepWindowReclip.reclip(json, 1000, 3000, 1000, 3600)!!
        val segs = segments(out)
        assertEquals(3, segs.size)
        assertEquals("wake", segs.last().stage)
        assertEquals(3000, segs.last().start)
        assertEquals(3600, segs.last().end)
    }

    @Test
    fun segmentTrimBeforeAllSegmentsReturnsWakeFillNotNull() {
        // Corrected wake lands before every stage → emit a single wake covering the corrected window so
        // the store's COALESCE doesn't keep the OLD stages extending past the new wake time.
        val json = """[{"start":2000,"end":3000,"stage":"light"},{"start":3000,"end":4000,"stage":"deep"}]"""
        val out = SleepWindowReclip.reclip(json, 1000, 4000, 1000, 1500)!!
        val segs = segments(out)
        assertEquals(1, segs.size)
        assertEquals("wake", segs[0].stage)
        assertEquals(1500, segs.maxOf { it.end }, "no stage extends past the corrected wake")
    }

    // ── minute dict (imported nights) ────────────────────────────────────────────────────────────

    @Test
    fun minutesTrimCascadesFromAwakeThenLight() {
        // Shorten by 40 min: awake (30) → 0 and the remaining 10 comes off light.
        val json = """{"awake":30,"light":200,"deep":80,"rem":90}"""
        val out = SleepWindowReclip.reclip(json, 0, 8 * 3600, 0, 8 * 3600 - 40 * 60)!!
        val m = minutes(out)
        assertEquals(0.0, m["awake"]!!, 0.001)
        assertEquals(190.0, m["light"]!!, 0.001)
        assertEquals(80.0, m["deep"]!!, 0.001)
        assertEquals(90.0, m["rem"]!!, 0.001)
    }

    @Test
    fun minutesExtendAddsToAwake() {
        val json = """{"awake":30,"light":200,"deep":80,"rem":90}"""
        val out = SleepWindowReclip.reclip(json, 0, 8 * 3600, 0, 8 * 3600 + 20 * 60)!!
        val m = minutes(out)
        assertEquals(50.0, m["awake"]!!, 0.001)
        assertEquals(200.0, m["light"]!!, 0.001)
    }

    // ── bed (onset) edits: START-AWARE reclip (#0) ───────────────────────────────────────────────

    @Test
    fun bedOnlyEditSegmentsDropsStagesBeforeNewBed() {
        // A pure onset edit: bed time moves FORWARD 1000 → 2000, wake unchanged at 4000. The "light"
        // segment wholly before the new bed drops; the straddling "deep" clips its start UP to 2000; no
        // segment starts before the corrected bed; stage total == the corrected window (4000-2000).
        val json = """
            [{"start":1000,"end":2000,"stage":"light"},
             {"start":1800,"end":3000,"stage":"deep"},
             {"start":3000,"end":4000,"stage":"rem"}]
        """.trimIndent()
        val out = SleepWindowReclip.reclip(json, 1000, 4000, 2000, 4000)!!
        val segs = segments(out)
        assertEquals(2, segs.size, "the segment wholly before the new bed time is dropped")
        assertEquals(2000, segs.minOf { it.start }, "no segment starts before the new bed time")
        assertEquals("deep", segs[0].stage)
        assertEquals(2000, segs[0].start, "the straddling segment's start clips up to the new bed time")
        val total = segs.sumOf { it.end - it.start }
        assertEquals(4000L - 2000L, total, "stage total equals the corrected [newStart, newEnd] window")
    }

    @Test
    fun bedOnlyEditMinutesImportedNightShrinksByOnsetDelta() {
        // An imported (minute-dict) night, pure onset edit: session 0..8h, bed moved forward 40 min so the
        // window shrinks 40 min even though newEnd == oldEnd. The duration delta drives the trim
        // (awake 30 to 0, then 10 off light); the total trims by the 40 min onset delta to 360, not the
        // window (an imported minute-dict need not fill its whole window, so total != window here).
        val json = """{"awake":30,"light":200,"deep":80,"rem":90}"""
        val oldEnd = 8 * 3600L
        val newStart = 40 * 60L
        val out = SleepWindowReclip.reclip(json, 0, oldEnd, newStart, oldEnd)!!
        val m = minutes(out)
        assertEquals(0.0, m["awake"]!!, 0.001)
        assertEquals(190.0, m["light"]!!, 0.001)
        assertEquals(80.0, m["deep"]!!, 0.001)
        assertEquals(90.0, m["rem"]!!, 0.001)
        val total = m.values.sum()
        assertEquals(
            360.0, total, 0.001,
            "imported-night total trims by the onset delta (400 to 360), not the window",
        )
    }

    // ── degenerate input ─────────────────────────────────────────────────────────────────────────

    @Test
    fun nilAndGarbageReturnNull() {
        assertNull(SleepWindowReclip.reclip(null, 0, 1, 0, 1))
        assertNull(SleepWindowReclip.reclip("not json", 0, 1, 0, 1))
    }

    @Test
    fun reclipReturnsParseableJson() {
        val json = """[{"start":1000,"end":2000,"stage":"light"}]"""
        val out = SleepWindowReclip.reclip(json, 1000, 2000, 1000, 1800)
        assertNotNull(out)
        assertEquals(1, segments(out!!).size)
    }
}
