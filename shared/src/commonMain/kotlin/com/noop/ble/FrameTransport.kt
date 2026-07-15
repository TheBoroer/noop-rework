@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package com.noop.ble

import com.noop.data.OutboxBatchMeta
import com.noop.protocol.CommandNumber
import com.noop.protocol.DeviceFamily
import com.noop.protocol.HrSample
import com.noop.protocol.ParsedFrame
import com.noop.protocol.RrInterval
import com.noop.protocol.Streams
import com.noop.protocol.extractStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Flow 3: realtime streams (HR, raw, R10/R11) over Kable.
 *
 * Kotlin twin of the Swift `Collector` live seam (Strand/Collect/Collector.swift) plus the
 * BLEManager flow-3 frame routing:
 *
 *   [BleSession.frames] (already reassembled + parsed once, #47) →
 *   buffer (frame, parsed) pairs → cadence flush →
 *   `extractStreams(clockRef)` → [insert] (DECODED FIRST, durable) →
 *   raw outbox second ([enqueueRaw], transient) → clear buffer.
 *
 * Because decoded is committed before raw is queued, pruning raw never loses a metric —
 * the same persist-before-ack ordering invariant the outbox tasks (T2/T5) locked in.
 *
 * Family routing (BLEManager.swift:3505-3550):
 *  - WHOOP 4: every live data frame is ingested (when not backfilling — flow 4 owns offload).
 *  - WHOOP 5/MG: live puffin REALTIME_DATA is deliberately NOT ingested — the standard 0x2A37
 *    Heart-Rate profile is the single authoritative live HR/RR source (decoding HR a second
 *    time off the puffin stream stored a duplicate row per heartbeat). 5/MG live timestamps
 *    are already real-unix seconds, so the clock ref is the identity ([ClockRef.IDENTITY]).
 */

/**
 * The GET_CLOCK correlation pair: strap device-epoch second ↔ wall-clock unix second sampled
 * at the same instant. Twin of Swift `ClockRef` (`ref.device` / `ref.wall`).
 */
data class ClockRef(val device: Int, val wall: Int) {
    companion object {
        /** 5/MG: live puffin timestamps are already real-unix seconds — `toWall` is a no-op. */
        val IDENTITY = ClockRef(0, 0)
    }
}

/**
 * On-demand bounded raw-capture window (Strand/Collect/RawCaptureWindow.swift). ORs into the
 * raw-persist gate so a "capture activity sample" action can persist raw even when
 * `enableRawCapture` is off. The monotonic deadline auto-expires so a dropped stop callback
 * can't leak raw forever.
 */
class RawCaptureWindow {
    companion object {
        const val MIN_SECONDS: Double = 1.0
        const val MAX_SECONDS: Double = 300.0
        fun clamp(s: Double): Double = minOf(maxOf(s, MIN_SECONDS), MAX_SECONDS)
    }

    /** Monotonic deadline; null = inactive. */
    private var deadline: Double? = null

    /** True while the window is open, inclusive of the deadline instant (`t <= deadline`). */
    fun isActive(at: Double): Boolean = deadline?.let { at <= it } ?: false

    fun open(at: Double, durationSeconds: Double) {
        deadline = at + clamp(durationSeconds)
    }

    fun close() {
        deadline = null
    }
}

/**
 * Cadence: flush after this many buffered frames OR this many seconds since the last flush —
 * whichever first. Also flushed explicitly on disconnect/foreground. Twin of `CollectorPolicy`.
 */
data class FrameTransportPolicy(
    val maxFrames: Int = 64,
    val maxIntervalSeconds: Double = 30.0,
    /**
     * Defensive cap on the PRE-CLOCK buffer only (see [FrameTransport.ingest]). Generous —
     * ~4096 frames at ~60 bytes/frame is ~240KB, far beyond the handful seen pre-clock normally.
     */
    val maxPreClockFrames: Int = 4096,
) {
    companion object {
        val DEFAULT = FrameTransportPolicy()
    }
}

class FrameTransport(
    val family: DeviceFamily,
    /**
     * Device id new samples persist under. MUTABLE so a WHOOP↔WHOOP switch re-attributes the
     * next flush/standard-HR persist immediately (Swift `Collector.deviceId`).
     */
    var deviceId: String,
    /**
     * Decoded persistence seam — production wiring is `StreamPersistence.toBatch` +
     * `WhoopRepository.insert` (the 2c-1 path). Throwing = insert failed → frames re-buffer.
     */
    private val insert: suspend (Streams, String) -> Unit,
    /**
     * Raw outbox seam — production wiring is `OutboxStore.enqueue` (Task 4 seam). The blob is
     * `OutboxCodec.zlibCompressWithLength(packFrames(frames))`, byte-identical to the Swift
     * `RawOutbox` writers so either side can decode either side's batches (T12 parity).
     */
    private val enqueueRaw: suspend (OutboxBatchMeta, ByteArray) -> Unit,
    private val policy: FrameTransportPolicy = FrameTransportPolicy.DEFAULT,
    /**
     * Research toggle. When false (DEFAULT) no raw frames are persisted at all — the app is
     * decoded-only. Backed by the same setting the Swift Collector read.
     */
    private val enableRawCapture: Boolean = false,
    /** Flow-4 seam: while the Backfiller is draining an offload, live ingest is suspended. */
    private val backfilling: () -> Boolean = { false },
    private val now: () -> Long = { Clock.System.now().epochSeconds },
    /** Monotonic seconds seam (Swift `monotonic()`); injectable for cadence tests. */
    monotonic: (() -> Double)? = null,
    /** Batch id seam so tests can pin ids (Swift `UUID().uuidString`). */
    private val newBatchId: () -> String = { Uuid.random().toString() },
    private val log: (String) -> Unit = {},
) {
    private val monotonicOrigin = TimeSource.Monotonic.markNow()
    private val monotonic: () -> Double =
        monotonic ?: { monotonicOrigin.elapsedNow().toDouble(DurationUnit.SECONDS) }

    /** Set once the GET_CLOCK correlation lands. Until then, frames buffer un-persisted. */
    var clockRef: ClockRef? = if (family == DeviceFamily.WHOOP5) ClockRef.IDENTITY else null
        private set

    /** #47: buffer the (raw frame, pre-parsed) pair — `flush` never re-decodes the batch. */
    private val buffer = ArrayDeque<Pair<ByteArray, ParsedFrame>>()

    /** Standard 0x2A37 HR/RR buffer — the reliable, always-on stream. */
    private val stdHr = mutableListOf<HrSample>()
    private val stdRr = mutableListOf<RrInterval>()

    private var batchStartedAt: Double = this.monotonic()
    private val rawCapture = RawCaptureWindow()

    /** Serialises buffer access — the frames collector and explicit flush callers can race. */
    private val mutex = Mutex()

    val bufferedCount: Int get() = buffer.size

    /** GET_CLOCK correlation landed (E1). Also re-set on drift correction re-reads. */
    fun setClockRef(device: Int, wall: Int) {
        clockRef = ClockRef(device, wall)
        log("clockRef set device=$device wall=$wall")
    }

    fun openRawCaptureWindow(durationSeconds: Double) = rawCapture.open(monotonic(), durationSeconds)

    fun closeRawCaptureWindow() = rawCapture.close()

    /**
     * Subscribe to the session's reassembled frames and route them (BLEManager.swift:3505-3550).
     * Cancel the returned [Job] (or the scope) to detach.
     */
    fun attach(session: BleSession, scope: CoroutineScope): Job = scope.launch {
        session.frames.collect { route(it.raw, it.parsed) }
    }

    /**
     * Route one reassembled frame. Internal (not private) so tests can drive it without a live
     * Kable peripheral.
     */
    internal suspend fun route(frame: ByteArray, parsed: ParsedFrame) {
        // Clock correlation: a GET_CLOCK COMMAND_RESPONSE carries the strap's device-epoch
        // second; pairing it with receive-time wall clock forms the ref. WHOOP4 only — 5/MG
        // runs the identity ref (live puffin timestamps are already real-unix seconds).
        if (family == DeviceFamily.WHOOP4 &&
            (parsed.parsed["resp_cmd"] as? String)?.startsWith("GET_CLOCK") == true
        ) {
            (parsed.parsed["clock"] as? Int)?.let { setClockRef(it, now().toInt()) }
        }
        when (family) {
            DeviceFamily.WHOOP4 -> {
                // Live path only: offload frames are flow-4 (Backfiller) traffic.
                if (!backfilling()) ingest(frame, parsed)
            }
            DeviceFamily.WHOOP5 -> {
                // Deliberately NOT ingested (BLEManager.swift:3540): the standard 0x2A37 profile
                // is the single authoritative live HR/RR source for 5/MG — decoding HR a second
                // time off the puffin stream duplicated a row per heartbeat.
            }
        }
    }

    /**
     * Buffer one complete frame + its pre-parsed decode. Cadence-flushes inline (the caller is
     * the single frames collector, so arrival order is preserved without a detached task).
     */
    suspend fun ingest(frame: ByteArray, parsed: ParsedFrame) {
        var doFlush = false
        mutex.withLock {
            buffer.addLast(frame to parsed)
            if (clockRef == null) {
                // Pre-clock only: bound memory if GET_CLOCK never lands while data keeps
                // flowing. Drop OLDEST beyond the cap (keep most recent).
                while (buffer.size > policy.maxPreClockFrames) buffer.removeFirst()
            } else {
                doFlush = buffer.size >= policy.maxFrames ||
                    (monotonic() - batchStartedAt) >= policy.maxIntervalSeconds
            }
        }
        if (doFlush) flush()
    }

    /**
     * Persist + queue everything buffered. No-op when empty or before a clock ref exists.
     * The buffer is snapshotted and cleared BEFORE the first suspension point so concurrent
     * [ingest] calls during persistence accumulate into the NEXT batch cleanly.
     */
    suspend fun flush() {
        val ref: ClockRef
        val batch: List<Pair<ByteArray, ParsedFrame>>
        mutex.withLock {
            ref = clockRef ?: return
            if (buffer.isEmpty()) return
            batch = buffer.toList()
            buffer.clear()
        }

        val frames = batch.map { it.first } // still needed for the raw-capture outbox
        val parsed = batch.map { it.second } // #47: already decoded — don't re-parse
        val streams = extractStreams(parsed, deviceClockRef = ref.device, wallClockRef = ref.wall)
        try {
            insert(streams, deviceId) // DECODED FIRST (durable)
        } catch (t: Throwable) {
            // Re-buffer at the front so these frames (and their parses) are retried on the
            // next cadence. batchStartedAt must NOT advance on a failed drain.
            mutex.withLock { buffer.addAll(0, batch) }
            log("decoded insert failed (${batch.size} frames re-buffered): ${t.message}")
            return
        }
        batchStartedAt = monotonic()

        // RAW SECOND (transient outbox), only when the research toggle or an explicit capture
        // window is on. Default OFF → decoded-only, no raw is stored.
        if (!enableRawCapture && !rawCapture.isActive(monotonic())) return
        val wall = now()
        val tsValues = streams.hr.map { it.ts } + streams.rr.map { it.ts } +
            streams.events.map { it.ts } + streams.battery.map { it.ts }
        val meta = OutboxBatchMeta(
            batchId = newBatchId(),
            deviceId = deviceId,
            capturedAt = wall,
            deviceClockRef = ref.device.toLong(),
            wallClockRef = ref.wall.toLong(),
            startTs = tsValues.minOrNull()?.toLong() ?: wall,
            endTs = tsValues.maxOrNull()?.toLong() ?: wall,
            frameCount = frames.size,
            byteSize = frames.sumOf { it.size },
            syncedAt = null,
        )
        val blob = OutboxCodec.zlibCompressWithLength(OutboxCodec.packFrames(frames))
        try {
            enqueueRaw(meta, blob) // failure non-fatal — decoded is already durable
        } catch (t: Throwable) {
            log("raw enqueue failed (non-fatal, decoded durable): ${t.message}")
        }
    }

    /**
     * Buffer one standard Heart-Rate-Measurement reading (0x2A37, parsed upstream by
     * `StandardHeartRate.parse`). No clock correlation needed — these carry a wall-clock `ts`
     * directly. Auto-flushes ~every 30 readings (~30s).
     */
    suspend fun ingestStandardHr(hr: Int, rr: List<Int>, ts: Int) {
        var doFlush = false
        mutex.withLock {
            if (hr in 30..220) stdHr += HrSample(ts = ts, bpm = hr)
            for (r in rr) if (r in 250..3000) stdRr += RrInterval(ts = ts, rrMs = r)
            doFlush = stdHr.size + stdRr.size >= 30
        }
        if (doFlush) flushStandardHr()
    }

    /** Persist the buffered standard HR/RR. Re-buffers on failure so nothing is lost. */
    suspend fun flushStandardHr() {
        val hr: List<HrSample>
        val rr: List<RrInterval>
        mutex.withLock {
            if (stdHr.isEmpty() && stdRr.isEmpty()) return
            hr = stdHr.toList()
            rr = stdRr.toList()
            stdHr.clear()
            stdRr.clear()
        }
        try {
            insert(Streams(hr = hr.toMutableList(), rr = rr.toMutableList()), deviceId)
        } catch (t: Throwable) {
            mutex.withLock {
                stdHr.addAll(0, hr)
                stdRr.addAll(0, rr)
            }
            log("standard HR insert failed (${hr.size}+${rr.size} re-buffered): ${t.message}")
        }
    }

    /**
     * Execute [RealtimePolicy] reconcile actions against the session.
     * [RealtimeAction.ToggleRealtimeHr] → TOGGLE_REALTIME_HR(3) `[0x01]`/`[0x00]` (both
     * families; puffin-framed automatically on 5/MG). [RealtimeAction.HeavyStream] →
     * SEND_R10_R11_REALTIME(63) — WHOOP 4.0 ONLY: the R10/R11 burst is WHOOP4-framed, and the
     * Swift router deliberately dropped it for 5/MG (BLEManager.swift:2206).
     */
    suspend fun applyRealtimeActions(session: BleSession, actions: List<RealtimeAction>) =
        applyRealtimeActions(
            family = family,
            actions = actions,
            send = { cmd, payload -> session.send(cmd, payload) },
            noteArmed = session::noteRealtimeArmed,
        )

    companion object {
        /** Seam-based core of [applyRealtimeActions] — pure enough for commonTest. */
        suspend fun applyRealtimeActions(
            family: DeviceFamily,
            actions: List<RealtimeAction>,
            send: suspend (CommandNumber, ByteArray) -> Unit,
            noteArmed: () -> Unit,
        ) {
            for (a in actions) when (a) {
                is RealtimeAction.ToggleRealtimeHr -> {
                    send(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(if (a.on) 0x01 else 0x00))
                    // #711/#86 marginal-radio detection measures from the arm instant.
                    if (a.on) noteArmed()
                }
                is RealtimeAction.HeavyStream ->
                    if (family == DeviceFamily.WHOOP4) {
                        send(CommandNumber.SEND_R10_R11_REALTIME, byteArrayOf(if (a.on) 0x01 else 0x00))
                    }
            }
            // Arm-time GET_CLOCK re-read (WHOOP4 only — 5/MG rides ClockRef.IDENTITY): a #120
            // drift re-correlation, and the self-heal for a handshake GET_CLOCK reply lost to
            // the CCCD-subscribe race — without a clock ref every realtime frame buffers
            // pre-clock and no HR row ever persists (t11 gate run: 284 frames / 0 rows).
            val arming = actions.any {
                (it is RealtimeAction.ToggleRealtimeHr && it.on) ||
                    (it is RealtimeAction.HeavyStream && it.on)
            }
            if (arming && family == DeviceFamily.WHOOP4) {
                ClockSync.getClockPayloads(family).forEach { send(CommandNumber.GET_CLOCK, it) }
            }
        }
    }
}
