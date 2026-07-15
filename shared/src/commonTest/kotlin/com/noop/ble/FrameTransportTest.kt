package com.noop.ble

import com.noop.data.OutboxBatchMeta
import com.noop.protocol.CommandNumber
import com.noop.protocol.DeviceFamily
import com.noop.protocol.ParsedFrame
import com.noop.protocol.Streams
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Flow-3 transport tests: the invariants ported from Swift `CollectorTests` plus the
 * flow-3 routing/executor gates. All seams injected — no Kable peripheral needed.
 */
class FrameTransportTest {

    /** A REALTIME_DATA parse carrying one HR sample at device-epoch [deviceTs]. */
    private fun realtimeParsed(deviceTs: Int, bpm: Int = 60): ParsedFrame = ParsedFrame(
        ok = true,
        crcOk = true,
        typeName = "REALTIME_DATA",
        parsed = mapOf("timestamp" to deviceTs, "heart_rate" to bpm),
    )

    private class Recorder {
        val inserted = mutableListOf<Pair<Streams, String>>()
        val enqueued = mutableListOf<Pair<OutboxBatchMeta, ByteArray>>()
        var failInsert = false

        val insert: suspend (Streams, String) -> Unit = { s, id ->
            if (failInsert) error("simulated insert failure")
            inserted += s to id
        }
        val enqueue: suspend (OutboxBatchMeta, ByteArray) -> Unit = { m, b -> enqueued += m to b }
    }

    private fun transport(
        rec: Recorder,
        family: DeviceFamily = DeviceFamily.WHOOP4,
        policy: FrameTransportPolicy = FrameTransportPolicy(maxFrames = 3, maxIntervalSeconds = 1_000.0),
        enableRawCapture: Boolean = false,
        backfilling: () -> Boolean = { false },
        monotonic: (() -> Double)? = { 0.0 },
        now: () -> Long = { 2_000L },
    ) = FrameTransport(
        family = family,
        deviceId = "my-whoop",
        insert = rec.insert,
        enqueueRaw = rec.enqueue,
        policy = policy,
        enableRawCapture = enableRawCapture,
        backfilling = backfilling,
        now = now,
        monotonic = monotonic,
        newBatchId = { "batch-1" },
    )

    // MARK: pre-clock buffering

    @Test
    fun preClockFramesBufferUnpersistedAndCapDropsOldest() = runTest {
        val rec = Recorder()
        val t = transport(rec, policy = FrameTransportPolicy(maxFrames = 2, maxIntervalSeconds = 1_000.0, maxPreClockFrames = 3))
        repeat(5) { i -> t.ingest(byteArrayOf(i.toByte()), realtimeParsed(deviceTs = 100 + i)) }
        // No clock ref: nothing persisted even though maxFrames (2) was exceeded.
        assertTrue(rec.inserted.isEmpty())
        // Cap keeps the MOST RECENT 3 (oldest dropped).
        assertEquals(3, t.bufferedCount)

        // Clock lands → next cadence flush persists the retained frames.
        t.setClockRef(device = 100, wall = 1_000)
        t.ingest(byteArrayOf(9), realtimeParsed(deviceTs = 109))
        assertEquals(1, rec.inserted.size)
        val (streams, deviceId) = rec.inserted.single()
        assertEquals("my-whoop", deviceId)
        // toWall: wall + (ts - device) → device 102..104,109 → wall 1002..1004,1009.
        assertEquals(listOf(1_002, 1_003, 1_004, 1_009), streams.hr.map { it.ts })
        assertEquals(0, t.bufferedCount)
    }

    // MARK: decoded-first + re-buffer on failure

    @Test
    fun failedInsertRebuffersAtFrontAndRetriesNextFlush() = runTest {
        val rec = Recorder()
        val t = transport(rec, enableRawCapture = true)
        t.setClockRef(device = 0, wall = 0)
        rec.failInsert = true
        t.ingest(byteArrayOf(1), realtimeParsed(10))
        t.ingest(byteArrayOf(2), realtimeParsed(11))
        t.ingest(byteArrayOf(3), realtimeParsed(12)) // hits maxFrames=3 → flush attempt fails
        assertTrue(rec.inserted.isEmpty())
        // RAW NEVER RUNS when decoded failed (decoded-before-raw ordering).
        assertTrue(rec.enqueued.isEmpty())
        assertEquals(3, t.bufferedCount) // re-buffered, nothing lost

        rec.failInsert = false
        t.flush()
        assertEquals(1, rec.inserted.size)
        assertEquals(listOf(10, 11, 12), rec.inserted.single().first.hr.map { it.ts })
        assertEquals(1, rec.enqueued.size)
    }

    // MARK: raw capture gating + blob parity

    @Test
    fun rawCaptureOffByDefaultPersistsDecodedOnly() = runTest {
        val rec = Recorder()
        val t = transport(rec)
        t.setClockRef(device = 0, wall = 0)
        repeat(3) { i -> t.ingest(byteArrayOf(i.toByte()), realtimeParsed(10 + i)) }
        assertEquals(1, rec.inserted.size)
        assertTrue(rec.enqueued.isEmpty())
    }

    @Test
    fun rawBlobRoundTripsThroughOutboxCodecWithCorrectMeta() = runTest {
        val rec = Recorder()
        val frames = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), byteArrayOf(6))
        val t = transport(rec, enableRawCapture = true, now = { 2_000L })
        t.setClockRef(device = 100, wall = 1_000)
        frames.forEachIndexed { i, f -> t.ingest(f, realtimeParsed(deviceTs = 110 + i)) }

        val (meta, blob) = rec.enqueued.single()
        assertEquals("batch-1", meta.batchId)
        assertEquals("my-whoop", meta.deviceId)
        assertEquals(2_000L, meta.capturedAt)
        assertEquals(100L, meta.deviceClockRef)
        assertEquals(1_000L, meta.wallClockRef)
        // startTs/endTs from decoded stream ts (wall): 1010..1012.
        assertEquals(1_010L, meta.startTs)
        assertEquals(1_012L, meta.endTs)
        assertEquals(3, meta.frameCount)
        assertEquals(6, meta.byteSize)
        assertNull(meta.syncedAt)
        // Blob is zlib(packFrames(...)) — decodable by the shared codec (and thus Swift, T12 parity).
        val unpacked = OutboxCodec.unpackFrames(OutboxCodec.zlibDecompressWithLength(blob))
        assertEquals(3, unpacked.size)
        frames.forEachIndexed { i, f -> assertContentEquals(f, unpacked[i]) }
    }

    @Test
    fun rawCaptureWindowOrsIntoGateAndAutoExpires() = runTest {
        var mono = 0.0
        val rec = Recorder()
        val t = transport(rec, monotonic = { mono })
        t.setClockRef(device = 0, wall = 0)
        t.openRawCaptureWindow(durationSeconds = 10.0)
        repeat(3) { i -> t.ingest(byteArrayOf(i.toByte()), realtimeParsed(10 + i)) }
        assertEquals(1, rec.enqueued.size) // window open → raw persisted despite toggle off

        mono = 11.0 // past the deadline → auto-expired
        repeat(3) { i -> t.ingest(byteArrayOf(i.toByte()), realtimeParsed(20 + i)) }
        assertEquals(2, rec.inserted.size)
        assertEquals(1, rec.enqueued.size) // no new raw batch
    }

    @Test
    fun rawCaptureWindowClampsDuration() {
        val w = RawCaptureWindow()
        w.open(at = 0.0, durationSeconds = 9_999.0) // clamped to 300
        assertTrue(w.isActive(at = 300.0))
        assertTrue(!w.isActive(at = 300.1))
        w.close()
        assertTrue(!w.isActive(at = 0.0))
    }

    // MARK: standard 0x2A37 HR/RR

    @Test
    fun standardHrGatesRangesAndFlushesAtThreshold() = runTest {
        val rec = Recorder()
        val t = transport(rec)
        // Out-of-range readings are dropped entirely.
        t.ingestStandardHr(hr = 20, rr = listOf(100, 5_000), ts = 50)
        assertTrue(rec.inserted.isEmpty())
        // 15 readings × (1 hr + 1 rr) = 30 buffered → auto-flush.
        repeat(15) { i -> t.ingestStandardHr(hr = 60 + i, rr = listOf(800), ts = 100 + i) }
        assertEquals(1, rec.inserted.size)
        val streams = rec.inserted.single().first
        assertEquals(15, streams.hr.size)
        assertEquals(15, streams.rr.size)
        assertEquals(60, streams.hr.first().bpm)
        assertEquals(800, streams.rr.first().rrMs)
    }

    @Test
    fun standardHrRebuffersOnFailure() = runTest {
        val rec = Recorder()
        val t = transport(rec)
        rec.failInsert = true
        repeat(15) { i -> t.ingestStandardHr(hr = 60, rr = listOf(800), ts = i) }
        assertTrue(rec.inserted.isEmpty())
        rec.failInsert = false
        t.flushStandardHr()
        assertEquals(1, rec.inserted.size)
        assertEquals(15, rec.inserted.single().first.hr.size)
    }

    // MARK: flow-3 routing

    @Test
    fun whoop5LiveFramesAreNeverIngestedIntoRawPath() = runTest {
        val rec = Recorder()
        val t = transport(rec, family = DeviceFamily.WHOOP5, enableRawCapture = true)
        // 5/MG starts on the identity clock ref — no GET_CLOCK gate.
        assertEquals(ClockRef.IDENTITY, t.clockRef)
        repeat(5) { i -> t.route(byteArrayOf(i.toByte()), realtimeParsed(1_000 + i)) }
        assertEquals(0, t.bufferedCount)
        assertTrue(rec.inserted.isEmpty())
        assertTrue(rec.enqueued.isEmpty())
    }

    @Test
    fun whoop4RouteSkipsIngestWhileBackfilling() = runTest {
        val rec = Recorder()
        var backfilling = true
        val t = transport(rec, backfilling = { backfilling })
        t.setClockRef(device = 0, wall = 0)
        repeat(3) { i -> t.route(byteArrayOf(i.toByte()), realtimeParsed(10 + i)) }
        assertEquals(0, t.bufferedCount) // offload frames are flow-4 traffic
        backfilling = false
        repeat(3) { i -> t.route(byteArrayOf(i.toByte()), realtimeParsed(20 + i)) }
        assertEquals(1, rec.inserted.size)
    }

    @Test
    fun getClockResponseSetsClockRefOnWhoop4() = runTest {
        val rec = Recorder()
        val t = transport(rec, now = { 5_000L })
        assertNull(t.clockRef)
        val resp = ParsedFrame(
            ok = true,
            crcOk = true,
            typeName = "COMMAND_RESPONSE",
            parsed = mapOf("resp_cmd" to "GET_CLOCK(11)", "clock" to 4_900),
        )
        t.route(byteArrayOf(0x24), resp)
        assertEquals(ClockRef(device = 4_900, wall = 5_000), t.clockRef)
    }

    // MARK: realtime action executor

    @Test
    fun executorSendsToggleAndNotesArmOnlyOnArm() = runTest {
        val sent = mutableListOf<Pair<CommandNumber, List<Byte>>>()
        var armed = 0
        FrameTransport.applyRealtimeActions(
            family = DeviceFamily.WHOOP5,
            actions = listOf(RealtimeAction.ToggleRealtimeHr(on = true), RealtimeAction.ToggleRealtimeHr(on = false)),
            send = { c, p -> sent += c to p.toList() },
            noteArmed = { armed += 1 },
        )
        assertEquals(
            listOf(
                CommandNumber.TOGGLE_REALTIME_HR to listOf<Byte>(0x01),
                CommandNumber.TOGGLE_REALTIME_HR to listOf<Byte>(0x00),
            ),
            sent,
        )
        assertEquals(1, armed) // disarm does not re-note
    }

    @Test
    fun executorDropsHeavyStreamForWhoop5ButSendsForWhoop4() = runTest {
        val sent = mutableListOf<CommandNumber>()
        FrameTransport.applyRealtimeActions(
            family = DeviceFamily.WHOOP5,
            actions = listOf(RealtimeAction.HeavyStream(on = true)),
            send = { c, _ -> sent += c },
            noteArmed = {},
        )
        assertTrue(sent.isEmpty()) // WHOOP4-framed R10/R11 burst is dropped for 5/MG

        FrameTransport.applyRealtimeActions(
            family = DeviceFamily.WHOOP4,
            actions = listOf(RealtimeAction.HeavyStream(on = true), RealtimeAction.HeavyStream(on = false)),
            send = { c, _ -> sent += c },
            noteArmed = {},
        )
        assertEquals(listOf(CommandNumber.SEND_R10_R11_REALTIME, CommandNumber.SEND_R10_R11_REALTIME), sent)
    }
}
