@file:OptIn(ExperimentalTime::class)

package com.noop.ble

import com.noop.data.InsertCounts
import com.noop.data.OutboxBatchMeta
import com.noop.data.StreamBatch
import com.noop.protocol.DeviceFamily
import com.noop.protocol.Framing
import com.noop.protocol.HistoricalMeta
import com.noop.protocol.classifyHistoricalMeta
import com.noop.protocol.extractHistoricalStreams
import com.noop.protocol.rejectedHistoricalRecords
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Phase 2c-2 Task 13: Kotlin port of `Strand/Collect/Backfiller.swift` (flow 4, historical
 * offload). Owns the chunked HISTORY_START/…/HISTORY_END/…/HISTORY_COMPLETE state machine and
 * the persist-before-ack invariant: a trim cursor is only ever acked (which lets the strap free
 * its own flash copy of that chunk) AFTER every durability step for that chunk has committed, in
 * this exact order:
 *
 *   decoded insert → rejected-frame archive → outbox raw enqueue → local cursor write → trim ack
 *
 * Any failure in that chain sets [persistStalled], which holds the ack for THIS chunk and every
 * later chunk in the same session (including metadata-only/empty ENDs with nothing new to
 * persist) — mirrors Swift issue #57. `persistStalled` only clears on a fresh [begin].
 *
 * Constructor-injected suspend/sync lambda seams (not a Swift-style protocol), matching the style
 * established by [FrameTransport]/[BleSession]: [insert] mirrors `WhoopRepository.insert`,
 * [enqueueRaw] mirrors `OutboxStore.enqueue` (Unit-returning, throws on failure — the blob itself
 * is packed+compressed here via [OutboxCodec] before the call, exactly like `FrameTransport.flush`
 * does for realtime batches), [setCursor] mirrors `OutboxStore.setCursor`, [ackTrim] is the
 * synchronous "send HISTORICAL_DATA_RESULT to the strap" callback, and [rejectedSink] is the
 * optional `rejected_history.jsonl` archive writer (record shape owned by the platform-native
 * `RawHistoryArchive` type, NOT this file — Backfiller only decides WHEN to call it).
 *
 * Deliberately NOT ported from the Swift file (coordinator-layer or pure-diagnostic concerns,
 * confirmed absent from this task's brief): the #773 future-RTC corrupt-clock check, the various
 * `session*` diagnostic-only tallies/logs (#67 clock diag, #547 dropped-implausible tally,
 * unmapped-firmware-version log, layout-version log), the SpO2 re-dump loop (needs an unported
 * `Spo2ReTrace`), the `connectionActive`/`emitConnection(...)` BLEManager-status seam (needs an
 * unported `ConnectionTrace`), the per-frame rejected-hex-dump loop (kept only a summary log
 * line), and `sessionSummaryLine`/`sessionClockDiagLine` (confirmed never called from within
 * `Backfiller.swift` itself — only read externally by BLEManager at session end, so they belong
 * to the coordinator, not this state machine). The `BackfillContinuation.shouldAutoContinue`
 * decision itself also stays out of scope (lives in `BLEManager.swift`, not `Backfiller.swift`);
 * this class only tracks the [lastAckedTrim] high-water mark (#364) that decision needs.
 */
class Backfiller(
    var deviceId: String,
    private val insert: suspend (StreamBatch, String) -> InsertCounts,
    private val enqueueRaw: suspend (OutboxBatchMeta, ByteArray) -> Unit,
    private val setCursor: suspend (String, Long) -> Unit,
    private val ackTrim: (trim: Long, endData: ByteArray) -> Unit,
    private val enableRawCapture: Boolean = true,
    private val rejectedSink: (suspend (frames: List<ByteArray>, trim: Long, family: DeviceFamily) -> Boolean)? = null,
    private val log: ((String) -> Unit)? = null,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    private var family: DeviceFamily = DeviceFamily.WHOOP4
    private var isBackfilling: Boolean = false
    private var chunk: MutableList<ByteArray> = mutableListOf()
    private var chunkOpen: Boolean = false

    /** #57: once any chunk's persist step fails in a session, every later ack (including
     *  metadata-only/empty ENDs) is held for the rest of that session. Cleared only by [begin].
     *  Public (read-only) so a coordinator/test can observe the stall without inferring it from
     *  ack silence alone. */
    var persistStalled: Boolean = false
        private set

    private var sessionRowsPersisted: Int = 0
    private var continuedAfterRows: Boolean = false

    /** Device/wall clock correlation for [extractHistoricalStreams]; set by the BLE coordinator
     *  layer (out of scope here), defaults to the identity mapping. Reset on [begin]. */
    var clockRef: ClockRef = ClockRef.IDENTITY

    private var sessionOldestUnix: Long? = null
    private var sessionNewestUnix: Long? = null

    /** #364 auto-continuation spin guard: a cross-session high-water mark of the last trim we
     *  actually acked. Deliberately NOT reset by [begin] — the `BackfillContinuation` decision
     *  that consumes this (BLEManager.swift, out of scope) needs it to survive session
     *  boundaries. Set on every successful ack. Starts at -1 (never acked). */
    var lastAckedTrim: Long = -1
        private set

    /** Starts (or restarts) a backfill session. Chunk-open immediately (a high-freq-sync strap
     *  sends ONE HISTORY_START then MANY HISTORY_ENDs, so buffering must be armed right away, not
     *  only once a START frame is observed). [continuedAfterRows] flags a session that resumed
     *  after a prior session in the same connection already persisted rows (feeds the
     *  #150/#783/#1 no-cursor log line). */
    fun begin(family: DeviceFamily, continuedAfterRows: Boolean = false) {
        this.family = family
        isBackfilling = true
        chunk = mutableListOf()
        chunkOpen = true
        persistStalled = false
        sessionRowsPersisted = 0
        this.continuedAfterRows = continuedAfterRows
        clockRef = ClockRef.IDENTITY
        sessionOldestUnix = null
        sessionNewestUnix = null
    }

    /** Watchdog fired with no HISTORY_END/COMPLETE observed in time: drop the in-flight chunk
     *  WITHOUT acking (whatever was buffered stays on the strap for a future retry/reconnect). */
    fun timeoutFired() {
        isBackfilling = false
        chunk = mutableListOf()
        chunkOpen = false
    }

    /** Feed one raw notification frame from the historical-offload characteristic. Ignored
     *  outside an active session ([begin] not yet called, or already [timeoutFired]/finished). */
    suspend fun ingest(frame: ByteArray) {
        if (!isBackfilling) return
        val parsed = Framing.parseFrame(frame, family)
        when (val meta = classifyHistoricalMeta(parsed)) {
            HistoricalMeta.Start -> {
                chunkOpen = true
                chunk = mutableListOf()
            }
            is HistoricalMeta.End -> {
                val frames = chunk
                chunk = mutableListOf()
                finishChunk(frames, endMeta = meta, endFrame = frame)
            }
            HistoricalMeta.Complete -> {
                chunkOpen = false
            }
            HistoricalMeta.Other -> {
                // Raw HISTORICAL_DATA (type 47) records, and anything else classifyHistoricalMeta
                // doesn't recognize as METADATA, buffer into the open chunk verbatim — decoding
                // happens once per chunk, not once per frame (extractHistoricalStreams' job).
                if (chunkOpen) chunk.add(frame)
            }
        }
    }

    private suspend fun finishChunk(frames: List<ByteArray>, endMeta: HistoricalMeta.End, endFrame: ByteArray) {
        val trim = endMeta.trim
        val unix = endMeta.unix
        sessionOldestUnix = sessionOldestUnix?.let { minOf(it, unix) } ?: unix
        sessionNewestUnix = sessionNewestUnix?.let { maxOf(it, unix) } ?: unix

        if (frames.isNotEmpty()) {
            val decoded = extractHistoricalStreams(
                rawFrames = frames,
                deviceClockRef = clockRef.device,
                wallClockRef = clockRef.wall,
                family = family,
                wallNow = now(),
                sessionOldestUnix = sessionOldestUnix,
                sessionNewestUnix = sessionNewestUnix,
            )
            val rejected = rejectedHistoricalRecords(frames, family)

            // 1) decoded insert — first durability step; nothing else runs until this commits.
            val counts: InsertCounts
            try {
                counts = insert(decoded, deviceId)
            } catch (t: Throwable) {
                log?.invoke("Backfill: failed to persist decoded rows (trim=$trim): ${t.message} — holding ack")
                persistStalled = true
                return
            }
            sessionRowsPersisted += chunkTally(counts)

            // 2) rejected-frame archive — only if there's something to archive and a sink is wired.
            if (rejected.isNotEmpty()) {
                val sink = rejectedSink
                if (sink != null) {
                    val archived = sink(rejected, trim, family)
                    if (!archived) {
                        log?.invoke("Backfill: rejected-frame archive failed (trim=$trim) — holding ack")
                        persistStalled = true
                        return
                    }
                }
            }

            // 3) outbox raw enqueue — pack + compress here (same pattern as FrameTransport.flush),
            // then hand the blob to the caller's storage seam.
            if (enableRawCapture) {
                val blob = OutboxCodec.zlibCompressWithLength(OutboxCodec.packFrames(frames))
                val meta = OutboxBatchMeta(
                    batchId = "hist-$deviceId-$trim",
                    deviceId = deviceId,
                    capturedAt = now(),
                    deviceClockRef = clockRef.device.toLong(),
                    wallClockRef = clockRef.wall.toLong(),
                    startTs = unix,
                    endTs = unix,
                    frameCount = frames.size,
                    byteSize = blob.size,
                    syncedAt = null,
                )
                try {
                    enqueueRaw(meta, blob)
                } catch (t: Throwable) {
                    log?.invoke("Backfill: failed to enqueue raw batch (trim=$trim): ${t.message} — holding ack")
                    persistStalled = true
                    return
                }
            }
        }

        // #150/#783/#1: trim==0xFFFFFFFF is the strap's "no cursor" sentinel. Log-only — it does
        // NOT block the ack (the strap still needs a HISTORICAL_DATA_RESULT response to close out
        // this END), it only skips writing a (meaningless) local cursor value below.
        if (trim == TRIM_NO_CURSOR) {
            log?.invoke(noCursorLine())
        }

        // #57: a session-wide persist failure holds every remaining ack, including an otherwise
        // clean, empty (metadata-only) END.
        if (persistStalled) {
            log?.invoke("Backfill: session persist-stalled — holding ack (trim=$trim)")
            return
        }

        // 4) local cursor write — skipped for the no-cursor sentinel (nothing real to persist).
        if (trim != TRIM_NO_CURSOR) {
            try {
                setCursor(CURSOR_NAME, trim)
            } catch (t: Throwable) {
                log?.invoke("Backfill: failed to write cursor (trim=$trim): ${t.message} — holding ack")
                persistStalled = true
                return
            }
        }

        // 5) trim ack — only reached once every prior durability step committed.
        val data = endData(endFrame, family) ?: ByteArray(8)
        ackTrim(trim, data)
        lastAckedTrim = trim
    }

    private fun noCursorLine(): String =
        if (sessionRowsPersisted > 0 || continuedAfterRows) {
            "Backfill: strap reports no cursor (trim=0xFFFFFFFF) after persisting rows this " +
                "session (sessionRowsPersisted=$sessionRowsPersisted, continuedAfterRows=$continuedAfterRows) " +
                "— treating as end of available history"
        } else {
            "Backfill: strap reports no cursor (trim=0xFFFFFFFF) with no rows persisted this " +
                "session — likely nothing to offload"
        }

    companion object {
        /** Wire sentinel (u32-on-the-wire 0xFFFFFFFF) meaning "the strap has no real trim cursor
         *  to report" (#150/#783/#1). */
        const val TRIM_NO_CURSOR: Long = 0xFFFFFFFFL

        private const val CURSOR_NAME = "strap_trim"

        private fun chunkTally(counts: InsertCounts): Int =
            counts.hr + counts.rr + counts.events + counts.battery + counts.spo2 +
                counts.skinTemp + counts.steps + counts.resp + counts.gravity

        /** The verbatim 8-byte slice of the HISTORY_END frame echoed back in the
         *  HISTORICAL_DATA_RESULT ack payload (`[0x01] + endData`). Offset differs by family:
         *  WHOOP4 17, WHOOP5/MG 21. Null (caller falls back to zeros) if the frame is too short. */
        fun endData(frame: ByteArray, family: DeviceFamily): ByteArray? {
            val offset = if (family == DeviceFamily.WHOOP5) 21 else 17
            if (frame.size < offset + 8) return null
            return frame.copyOfRange(offset, offset + 8)
        }
    }
}
