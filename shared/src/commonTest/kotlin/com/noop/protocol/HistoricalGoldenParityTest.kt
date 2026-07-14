package com.noop.protocol

import com.noop.data.HrRow
import com.noop.data.RrRow
import com.noop.data.fixturesDir
import com.noop.data.platformFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.Path.Companion.toPath

/**
 * Kotlin twin of the Swift `HistoricalStreamsParityTests.testSwiftHistoricalMatchesPythonGolden`
 * (Phase 2c-2 Task 12): decodes the SAME committed fixture frames
 * (`historical_frames.json`, copied verbatim from
 * `Packages/WhoopProtocol/Tests/WhoopProtocolTests/Resources/`) with the SAME clock refs, and
 * asserts the hr/rr streams equal the same golden (`historical_golden.json`, produced by
 * `scripts/gen_golden.py`). Because the Swift test pins Swift == golden and this test pins
 * Kotlin == golden, together they pin Kotlin == Swift without either runtime having to host the
 * other. Fixtures are found via NOOP_FIXTURES exactly like [com.noop.data.BackupRestoreTest].
 */
class HistoricalGoldenParityTest {
    // Same constants as HistoricalStreamsParityTests.swift.
    private val deviceClockRef = 31_538_447
    private val wallClockRef = 1_736_365_593

    private fun readFixture(name: String): String =
        platformFileSystem().read((fixturesDir() + "/" + name).toPath()) { readUtf8() }

    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) {
            ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
        }

    @Test
    fun kotlinHistoricalMatchesPythonGolden() {
        val frames = Json.parseToJsonElement(readFixture("historical_frames.json")).jsonArray
            .map { bytes(it.jsonObject["hex"]!!.jsonPrimitive.content) }
        val gold = Json.parseToJsonElement(readFixture("historical_golden.json")).jsonObject

        val streams = extractHistoricalStreams(frames, deviceClockRef = deviceClockRef, wallClockRef = wallClockRef)

        val expHr = gold["hr"]!!.jsonArray.map {
            HrRow(ts = it.jsonObject["ts"]!!.jsonPrimitive.long, bpm = it.jsonObject["bpm"]!!.jsonPrimitive.int)
        }
        val expRr = gold["rr"]!!.jsonArray.map {
            RrRow(ts = it.jsonObject["ts"]!!.jsonPrimitive.long, rrMs = it.jsonObject["rr_ms"]!!.jsonPrimitive.int)
        }
        assertEquals(expHr, streams.hr)
        assertEquals(expRr, streams.rr)
        assertTrue(streams.hr.isNotEmpty(), "golden decode must yield hr samples — an empty match proves nothing")
    }
}
