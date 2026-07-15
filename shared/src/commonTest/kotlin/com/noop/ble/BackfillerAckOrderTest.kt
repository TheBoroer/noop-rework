package com.noop.ble

import com.noop.data.InsertCounts
import com.noop.data.OutboxBatchMeta
import com.noop.data.StreamBatch
import com.noop.protocol.Crc
import com.noop.protocol.DeviceFamily
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 2c-2 Task 13 - the Kotlin twin of Task 5's `BackfillerPersistBeforeAckTests.swift`, the
 * highest-stakes regression test in the phase. Pins the Hard Invariant (persist-before-ack)
 * directly against [Backfiller]'s real `finishChunk` ordering:
 *
 *   decoded insert -> rejected-frame archive hold on failure (#57)
 *   -> outbox raw enqueue (only if raw capture is on) - hold ack on failure
 *   -> setCursor("strap_trim") - hold ack on failure
 *   -> ackTrim
 *
 * A regression here silently and permanently destroys data on the strap: acking trims the strap's
 * own flash copy, so an ack that outraces (or survives) a failed durability step means that data
 * now exists nowhere. Every failure-path assertion below therefore checks the ABSENCE of an ack,
 * not a returned value - `acker.acks` staying empty is the load-bearing assertion.
 *
 * Tests 1-6 mirror the six Swift methods one-for-one. Tests 7+ are Kotlin-only additions covering
 * ground the brief calls out explicitly that the Swift suite could not (its own docstring notes
 * the rejected-archive path was "not exercised... rejectedSink is nil and the v25 fixture frames
 * all decode cleanly"), plus direct coverage of the #364 cross-session high-water mark, the #57
 * reset-on-[begin], and the watchdog no-ack path.
 */
class BackfillerAckOrderTest {

    private enum class Step { INSERT, ENQUEUE_RAW, SET_CURSOR }

    /** Twin of Swift's `OrderingStore` mock: records ORDER, can be told to fail per-step, and can
     *  be told to genuinely SUSPEND inside enqueueRaw so a test can prove ackTrim cannot fire
     *  while a commit is still in flight (not just that the finished log ended up in order). */
    private class Recorder(
        var insertShouldThrow: Boolean = false,
        var enqueueShouldThrow: Boolean = false,
        var setCursorShouldThrow: Boolean = false,
        var holdEnqueue: Boolean = false,
    ) {
        val log = mutableListOf<Step>()
        val enteredEnqueue = CompletableDeferred<Unit>()
        private val releaseEnqueue = CompletableDeferred<Unit>()

        fun release() {
            releaseEnqueue.complete(Unit)
        }

        val insert: suspend (StreamBatch, String) -> InsertCounts = { batch, _ ->
            log.add(Step.INSERT)
            if (insertShouldThrow) throw RuntimeException("insert failed")
            InsertCounts(
                hr = batch.hr.size,
                rr = batch.rr.size,
                spo2 = batch.spo2.size,
                skinTemp = batch.skinTemp.size,
                resp = batch.resp.size,
                gravity = batch.gravity.size,
            )
        }

        val enqueueRaw: suspend (OutboxBatchMeta, ByteArray) -> Unit = { _, _ ->
            log.add(Step.ENQUEUE_RAW)
            if (holdEnqueue) {
                enteredEnqueue.complete(Unit)
                releaseEnqueue.await()
            }
            if (enqueueShouldThrow) throw RuntimeException("enqueue failed")
        }

        val setCursor: suspend (String, Long) -> Unit = { _, _ ->
            log.add(Step.SET_CURSOR)
            if (setCursorShouldThrow) throw RuntimeException("cursor failed")
        }
    }

    /** Stand-in for the strap link layer: records every `ackTrim` call plus a snapshot of the
     *  store's log length AT THE MOMENT the ack fires, so "ack only after all durability steps
     *  completed" is checkable by more than sequencing luck. */
    private class AckRecorder(private val log: List<Step>) {
        val acks = mutableListOf<Pair<Long, ByteArray>>()
        val logLengthAtAck = mutableListOf<Int>()
        val ackTrim: (Long, ByteArray) -> Unit = { trim, endData ->
            acks.add(trim to endData)
            logLengthAtAck.add(log.size)
        }
    }

    private fun hexBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    /** Three REAL WHOOP 4.0 v25 records (84 B, 1 Hz) that decode cleanly to gravity samples -
     *  reused verbatim from `BackfillerPersistBeforeAckTests.swift` so `insert` genuinely sees
     *  non-empty decoded streams and `enqueueRaw` genuinely sees non-empty frames (an empty chunk
     *  would skip both calls entirely, which is not what these tests need to exercise). */
    private val v25RecordFrames: List<ByteArray> = listOf(
        "aa50000c2f190013390000140d2b6a4075010068a2010032fdbcfd98fdd3fdccfd47ffb00366064f073e06c103d3016cffa2fc87fa2ffae5fdbe03140675060c0510012dff1bfec0018f3c500500010068dc8f44",
        "aa50000c2f190014390000150d2b6a487001003ab301008dfd6afdaffda9fdaffd68fddbfb0dfc09fd77fe89fe62febffec9fe91ff0bff81ff5fff3e00d600790078ff3dff4bff801d553c5005010000d7c016b3",
        "aa50000c2f190015390000160d2b6a586b01006d8f0100a3ff94ffc4ffbcffbeff22004a009400cb0048005d006b004400d700130115013301f20088001d0031ffd9fe5eff75ff0048933c50050001008bdf2c2c",
    ).map(::hexBytes)

    /** Real on-wrist WHOOP 4.0 v24 record whose HR byte is flipped so its CRC fails - genuinely
     *  undecodable, so `rejectedHistoricalRecords` flags it (same fixture shape as
     *  `RejectedHistoricalRecordsTest.crcFailedRecordIsRejected`). Its own CRC-invalid envelope
     *  means `classifyHistoricalMeta` also routes it to `.Other`, so it lands in the chunk buffer
     *  like any other historical-data frame, decodes to zero rows, and is the ONLY frame
     *  `rejectedHistoricalRecords` returns for the chunk. */
    private val rejectableFrame: ByteArray = hexBytes(
        "aa6400a12f18054c1c0a023ed0266a5037805418016d022b0234020000000000006b07ff00" +
            "85593c1f65cebed7b3e63eb85a5f3f000080401f65cebed7b3e63eb85a5f3f500264025d03" +
            "640229014009010c020c00000000000f0001c4020000000000008fdeb278",
    ).also { it[21] = (it[21].toInt() xor 0xFF).toByte() } // flip HR byte, leave CRC trailer stale -> CRC mismatch

    /** Build a CRC-valid frame for a given type/seq/cmd/payload, computing crc8(length) and
     *  crc32(inner) exactly as the strap would - same envelope-construction pattern as
     *  `FramingTest.whoop4CommandResponse`, generalized to an arbitrary type/cmd so it can build
     *  METADATA (49) frames too. */
    private fun frameFromPayload(payload: ByteArray, type: Int, seq: Int, cmd: Int): ByteArray {
        val inner = byteArrayOf(type.toByte(), seq.toByte(), cmd.toByte()) + payload
        val length = 4 + inner.size
        val out = ByteArray(length + 4)
        out[0] = 0xAA.toByte()
        out[1] = (length and 0xFF).toByte()
        out[2] = ((length ushr 8) and 0xFF).toByte()
        out[3] = Crc.crc8(byteArrayOf(out[1], out[2])).toByte()
        inner.copyInto(out, 4)
        val crc32 = Crc.crc32(inner)
        for (i in 0..3) out[length + i] = ((crc32 ushr (8 * i)) and 0xFFL).toByte()
        return out
    }

    /** A real WHOOP4 HISTORY_END frame (type 49, cmd 2) carrying the given trim. Payload layout
     *  is unix(4) + subsec(2) + unk0(4) + trim(4) - matches `BackfillerPersistBeforeAckTests.swift`'s
     *  `historyEndFrame`. */
    private fun historyEndFrame(trim: Long, unix: Long = 1_700_000_000L): ByteArray {
        fun le32(v: Long): ByteArray = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
        val payload = le32(unix) + byteArrayOf(0, 0) + le32(0) + le32(trim)
        return frameFromPayload(payload, type = 49, seq = 0, cmd = 2)
    }

    private fun makeBackfiller(
        recorder: Recorder,
        acker: AckRecorder,
        enableRawCapture: Boolean = true,
        rejectedSink: (suspend (List<ByteArray>, Long, DeviceFamily) -> Boolean)? = null,
    ): Backfiller = Backfiller(
        deviceId = "test",
        insert = recorder.insert,
        enqueueRaw = recorder.enqueueRaw,
        setCursor = recorder.setCursor,
        ackTrim = acker.ackTrim,
        enableRawCapture = enableRawCapture,
        rejectedSink = rejectedSink,
    )

    // MARK: - (1) raw capture ON: ack only fires once insert -> enqueueRaw -> setCursor ALL
    // completed (log-order form).

    @Test
    fun happyPathAcksOnlyAfterAllThreeDurabilityStepsComplete() = runTest {
        val recorder = Recorder()
        val acker = AckRecorder(recorder.log)
        val backfiller = makeBackfiller(recorder, acker, enableRawCapture = true)
        backfiller.begin(DeviceFamily.WHOOP4)
        for (f in v25RecordFrames) backfiller.ingest(f)
        backfiller.ingest(historyEndFrame(trim = 42))

        assertEquals(listOf(Step.INSERT, Step.ENQUEUE_RAW, Step.SET_CURSOR), recorder.log)
        assertEquals(1, acker.acks.size)
        assertEquals(42L, acker.acks.first().first)
        assertEquals(listOf(3), acker.logLengthAtAck, "ackTrim must fire only once insert+enqueueRaw+setCursor have ALL completed")
        assertFalse(backfiller.persistStalled)
    }

    // MARK: - (2, race form) raw capture ON: ackTrim genuinely cannot fire while the raw-batch
    // commit is still SUSPENDED (not merely "happens to run first" per the log). This is the
    // strongest form of "no trim ack before the enqueue commit callback returns success."

    @Test
    fun ackDoesNotFireWhileEnqueueCommitIsStillInFlight() = runTest {
        val recorder = Recorder(holdEnqueue = true)
        val acker = AckRecorder(recorder.log)
        val backfiller = makeBackfiller(recorder, acker, enableRawCapture = true)
        backfiller.begin(DeviceFamily.WHOOP4)
        for (f in v25RecordFrames) backfiller.ingest(f)

        val endTask = async { backfiller.ingest(historyEndFrame(trim = 47)) }

        withTimeout(2_000) { recorder.enteredEnqueue.await() }
        assertTrue(acker.acks.isEmpty(), "ackTrim must not fire while the raw-batch commit is still suspended in flight")
        assertEquals(listOf(Step.INSERT, Step.ENQUEUE_RAW), recorder.log, "setCursor must not run until the suspended enqueue commit resolves")

        recorder.release()
        endTask.await()

        assertEquals(1, acker.acks.size, "the held ack must fire once the commit resolves")
        assertEquals(listOf(Step.INSERT, Step.ENQUEUE_RAW, Step.SET_CURSOR), recorder.log)
    }

    // MARK: - (3) raw capture ON: enqueue failure -> no ack, persistStalled set, and a LATER empty
    // END still does not ack (#57 twin).

    @Test
    fun enqueueRawBatchFailureHoldsAckAndStallsSession() = runTest {
        val recorder = Recorder(enqueueShouldThrow = true)
        val acker = AckRecorder(recorder.log)
        val backfiller = makeBackfiller(recorder, acker, enableRawCapture = true)
        backfiller.begin(DeviceFamily.WHOOP4)
        for (f in v25RecordFrames) backfiller.ingest(f)
        backfiller.ingest(historyEndFrame(trim = 42))

        assertEquals(listOf(Step.INSERT, Step.ENQUEUE_RAW), recorder.log, "setCursor must never run when the raw enqueue commit fails")
        assertTrue(acker.acks.isEmpty(), "no ack may follow a failed raw-batch commit")
        assertTrue(backfiller.persistStalled, "#57: the session must be marked stalled")

        // #57 twin: a LATER empty END (no frames accumulated since the failed chunk) must ALSO not
        // ack - acking it would trim the strap past the un-stored records-carrying chunk. An empty
        // END skips the insert entirely and never throws, so this is the case a naive
        // "only guard around the throwing call" fix would miss.
        backfiller.ingest(historyEndFrame(trim = 43))
        assertTrue(acker.acks.isEmpty(), "a stalled session must not ack a later empty END either")
        assertEquals(listOf(Step.INSERT, Step.ENQUEUE_RAW), recorder.log, "the stalled-session empty END must not even attempt another durability step")
    }

    // MARK: - (4) raw capture ON: cursor-write failure -> no ack (decoded AND raw already durable).

    @Test
    fun cursorWriteFailureHoldsAck() = runTest {
        val recorder = Recorder(setCursorShouldThrow = true)
        val acker = AckRecorder(recorder.log)
        val backfiller = makeBackfiller(recorder, acker, enableRawCapture = true)
        backfiller.begin(DeviceFamily.WHOOP4)
        for (f in v25RecordFrames) backfiller.ingest(f)
        backfiller.ingest(historyEndFrame(trim = 44))

        assertEquals(listOf(Step.INSERT, Step.ENQUEUE_RAW, Step.SET_CURSOR), recorder.log, "the cursor write must still be attempted after a successful raw enqueue")
        assertTrue(acker.acks.isEmpty(), "no ack may follow a failed cursor write")
        assertTrue(backfiller.persistStalled)
    }

    // MARK: - (5) raw capture OFF: decoded-insert failure still holds the ack (the raw step is
    // simply never reached, but that must not be mistaken for "nothing to guard").

    @Test
    fun decodedInsertFailureHoldsAckWithRawCaptureOff() = runTest {
        val recorder = Recorder(insertShouldThrow = true)
        val acker = AckRecorder(recorder.log)
        val backfiller = makeBackfiller(recorder, acker, enableRawCapture = false)
        backfiller.begin(DeviceFamily.WHOOP4)
        for (f in v25RecordFrames) backfiller.ingest(f)
        backfiller.ingest(historyEndFrame(trim = 45))

        assertEquals(listOf(Step.INSERT), recorder.log, "raw capture is off AND insert failed - enqueueRaw/setCursor must never run")
        assertTrue(acker.acks.isEmpty(), "no ack may follow a failed decoded insert, even with raw capture off")
        assertTrue(backfiller.persistStalled)
    }

    // MARK: - (6) Baseline: raw capture OFF, no failures. Confirms the OFF toggle SKIPS enqueueRaw
    // (not fails it) and the ack still fires - so the ON-path failure tests above are pinning a
    // real guard, not one that would also block the OFF happy path.

    @Test
    fun happyPathWithRawCaptureOffSkipsEnqueueButStillAcks() = runTest {
        val recorder = Recorder()
        val acker = AckRecorder(recorder.log)
        val backfiller = makeBackfiller(recorder, acker, enableRawCapture = false)
        backfiller.begin(DeviceFamily.WHOOP4)
        for (f in v25RecordFrames) backfiller.ingest(f)
        backfiller.ingest(historyEndFrame(trim = 46))

        assertEquals(listOf(Step.INSERT, Step.SET_CURSOR), recorder.log, "raw capture OFF must skip enqueueRaw entirely, not fail it")
        assertEquals(1, acker.acks.size)
        assertFalse(backfiller.persistStalled)
    }

    // MARK: - (7) Kotlin-only addition: rejected-archive failure holds the ack. Not exercised by
    // the Swift suite (its fixtures all decode cleanly, so `rejected` there is always empty); the
    // brief explicitly calls out "archive fail" as required Kotlin test coverage.

    @Test
    fun rejectedArchiveFailureHoldsAckAndStallsSession() = runTest {
        val recorder = Recorder()
        val acker = AckRecorder(recorder.log)
        var sinkCalls = 0
        val backfiller = makeBackfiller(
            recorder, acker, enableRawCapture = true,
            rejectedSink = { _, _, _ -> sinkCalls += 1; false },
        )
        backfiller.begin(DeviceFamily.WHOOP4)
        backfiller.ingest(rejectableFrame)
        backfiller.ingest(historyEndFrame(trim = 50))

        assertEquals(1, sinkCalls)
        assertEquals(listOf(Step.INSERT), recorder.log, "enqueueRaw/setCursor must never run when the rejected-archive write fails")
        assertTrue(acker.acks.isEmpty(), "no ack may follow a failed rejected-frame archive write")
        assertTrue(backfiller.persistStalled)
    }

    // MARK: - (8) Kotlin-only addition: rejected-archive success twin - the sink is called with the
    // undecodable frame and the chunk still acks normally once every later step commits.

    @Test
    fun rejectedArchiveSuccessStillAcks() = runTest {
        val recorder = Recorder()
        val acker = AckRecorder(recorder.log)
        var archived: List<ByteArray>? = null
        val backfiller = makeBackfiller(
            recorder, acker, enableRawCapture = true,
            rejectedSink = { frames, _, _ -> archived = frames; true },
        )
        backfiller.begin(DeviceFamily.WHOOP4)
        backfiller.ingest(rejectableFrame)
        backfiller.ingest(historyEndFrame(trim = 51))

        assertEquals(1, archived?.size)
        assertTrue(archived!!.first().contentEquals(rejectableFrame))
        assertEquals(listOf(Step.INSERT, Step.ENQUEUE_RAW, Step.SET_CURSOR), recorder.log)
        assertEquals(1, acker.acks.size)
        assertFalse(backfiller.persistStalled)
    }

    // MARK: - (9) #364: lastAckedTrim is a cross-session high-water mark, NOT reset by begin().

    @Test
    fun lastAckedTrimTracksAcrossSessionsAndIsNotResetByBegin() = runTest {
        val recorder = Recorder()
        val acker = AckRecorder(recorder.log)
        val backfiller = makeBackfiller(recorder, acker, enableRawCapture = false)

        assertEquals(-1L, backfiller.lastAckedTrim, "no ack has happened yet")

        backfiller.begin(DeviceFamily.WHOOP4)
        for (f in v25RecordFrames) backfiller.ingest(f)
        backfiller.ingest(historyEndFrame(trim = 100))
        assertEquals(100L, backfiller.lastAckedTrim)

        // A fresh session begins (e.g. a reconnect); the high-water mark must survive it.
        backfiller.begin(DeviceFamily.WHOOP4)
        assertEquals(100L, backfiller.lastAckedTrim, "#364: lastAckedTrim must not reset on begin()")

        for (f in v25RecordFrames) backfiller.ingest(f)
        backfiller.ingest(historyEndFrame(trim = 200))
        assertEquals(200L, backfiller.lastAckedTrim)
    }

    // MARK: - (10) #57 reset: a fresh begin() clears a stalled session so the NEXT session's acks
    // are not permanently blocked by a previous connection's failure.

    @Test
    fun beginClearsPersistStalledForAFreshSession() = runTest {
        val recorder = Recorder(enqueueShouldThrow = true)
        val acker = AckRecorder(recorder.log)
        val backfiller = makeBackfiller(recorder, acker, enableRawCapture = true)
        backfiller.begin(DeviceFamily.WHOOP4)
        for (f in v25RecordFrames) backfiller.ingest(f)
        backfiller.ingest(historyEndFrame(trim = 60))
        assertTrue(backfiller.persistStalled)
        assertTrue(acker.acks.isEmpty())

        recorder.enqueueShouldThrow = false
        backfiller.begin(DeviceFamily.WHOOP4)
        assertFalse(backfiller.persistStalled, "a fresh begin() must clear the #57 stall")

        for (f in v25RecordFrames) backfiller.ingest(f)
        backfiller.ingest(historyEndFrame(trim = 61))
        assertEquals(1, acker.acks.size, "the new session must be able to ack once the underlying failure is gone")
        assertEquals(61L, acker.acks.first().first)
    }

    // MARK: - (11) Watchdog: a timeout with no HISTORY_END/COMPLETE observed drops the in-flight
    // chunk WITHOUT acking anything.

    @Test
    fun timeoutClearsStateWithoutAcking() = runTest {
        val recorder = Recorder()
        val acker = AckRecorder(recorder.log)
        val backfiller = makeBackfiller(recorder, acker, enableRawCapture = true)
        backfiller.begin(DeviceFamily.WHOOP4)
        for (f in v25RecordFrames) backfiller.ingest(f)

        backfiller.timeoutFired()

        assertTrue(acker.acks.isEmpty(), "a watchdog timeout must never ack")
        assertTrue(recorder.log.isEmpty(), "a watchdog timeout must never attempt any durability step")

        // Confirms the session is truly over: a stray END arriving after the timeout is ignored,
        // not treated as belonging to a still-open chunk.
        backfiller.ingest(historyEndFrame(trim = 70))
        assertTrue(acker.acks.isEmpty())
    }
}
