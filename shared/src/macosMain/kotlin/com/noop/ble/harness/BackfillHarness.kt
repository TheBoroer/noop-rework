package com.noop.ble.harness

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.WriteType
import com.noop.ble.Backfiller
import com.noop.ble.BleSession
import com.noop.ble.WhoopAdvertisementFilter
import com.noop.ble.WhoopModel
import com.noop.data.InsertCounts
import com.noop.data.StreamBatch
import com.noop.protocol.CommandNumber
import com.noop.protocol.DeviceFamily
import com.noop.protocol.HistoricalMeta
import com.noop.protocol.classifyHistoricalMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Phase 2c-2 Task 13 manual-gate harness: live historical-offload (flow 4) over Kable.
 *
 *   ./gradlew :shared:linkDebugExecutableMacosArm64
 *   shared/build/bin/macosArm64/debugExecutable/scan-harness.kexe backfill \
 *       [--seconds N] [--whoop4|--whoop5]
 *
 * Flow: scan → connect (Task 10 session layer) → kick SEND_HISTORICAL_DATA ([0x00],
 * `.withResponse` — verified on-device that an empty payload serves 0 frames, Swift
 * `beginBackfill`, BLEManager.swift:1519-1523) → pipe every inbound frame into [Backfiller.ingest]
 * → print every HISTORY_END ack. The insert/enqueueRaw/setCursor/rejectedSink seams are
 * print-only stand-ins for the Room + outbox writers (mirrors [runRealtimeHarness]'s
 * [com.noop.ble.FrameTransport] stand-in), so this verifies scan → connect → offload-kick →
 * chunk-decode → persist-before-ack ordering → trim-ack on real hardware without a database.
 *
 * Idle watchdog: re-armed on every inbound frame while backfilling (mirrors Swift's
 * `armBackfillTimeout`, BLEManager.swift:1585-1601, 60s — "genuine offload frames can arrive in
 * bursts with multi-second lulls between chunks"). On fire, calls [Backfiller.timeoutFired] (no
 * ack for the in-flight chunk) and ends the hold early rather than waiting out the full `--seconds`
 * budget. HISTORY_COMPLETE also ends the hold early (successful full drain).
 *
 * Exit 0 when at least one trim was acked (i.e. at least one HISTORY_END chunk cleared the full
 * persist-before-ack chain).
 */
@OptIn(ExperimentalUuidApi::class)
fun runBackfillHarness(args: Array<String>) {
    val seconds = args.toList().zipWithNext()
        .firstOrNull { it.first == "--seconds" }?.second?.toLongOrNull() ?: 180L
    val models = when {
        args.contains("--whoop4") -> setOf(WhoopModel.WHOOP4)
        args.contains("--whoop5") -> setOf(WhoopModel.WHOOP5)
        else -> setOf(WhoopModel.WHOOP4, WhoopModel.WHOOP5)
    }
    println("backfill-harness: scanning ${models.joinToString()} for the first WHOOP …")

    var framesSeen = 0
    var chunksAcked = 0
    var rowsDecoded = 0
    var rejectedArchived = 0
    var lastTrimAcked: Long = -1
    var completed = false
    var timedOut = false
    runBlocking {
        val adv = withTimeoutOrNull(30_000) {
            Scanner {
                filters {
                    models.forEach { model ->
                        match { services = listOf(kotlin.uuid.Uuid.parse(model.scanServiceUuid)) }
                    }
                }
            }.advertisements.first()
        }
        if (adv == null) {
            println("backfill-harness: no WHOOP discovered within 30s")
            return@runBlocking
        }
        val model = WhoopAdvertisementFilter.modelFor(adv.uuids.map { it.toString() })
            ?: models.first()
        val family = when (model) {
            WhoopModel.WHOOP4 -> DeviceFamily.WHOOP4
            WhoopModel.WHOOP5 -> DeviceFamily.WHOOP5
        }
        println(
            "backfill-harness: found ${adv.name ?: "(no name)"} rssi=${adv.rssi} " +
                "id=${adv.identifier} family=$family",
        )

        val deviceId = adv.identifier.toString()
        val peripheral = Peripheral(adv)
        val session = BleSession(peripheral, family, log = { println("  [session] $it") })
        val sessionScope = CoroutineScope(coroutineContext + SupervisorJob())

        val doneSignal = CompletableDeferred<String>()
        var watchdogJob: Job? = null
        fun rearmWatchdog(backfiller: Backfiller) {
            watchdogJob?.cancel()
            watchdogJob = sessionScope.launch {
                kotlinx.coroutines.delay(60_000)
                println("  [watchdog] 60s idle — timeoutFired()")
                backfiller.timeoutFired()
                timedOut = true
                doneSignal.complete("watchdog")
            }
        }

        // Print-only persistence seams, mirroring runRealtimeHarness's FrameTransport stand-in.
        val backfiller = Backfiller(
            deviceId = deviceId,
            insert = { streams: StreamBatch, devId: String ->
                val counts = InsertCounts(
                    hr = streams.hr.size,
                    rr = streams.rr.size,
                    events = streams.events.size,
                    battery = streams.battery.size,
                    spo2 = streams.spo2.size,
                    skinTemp = streams.skinTemp.size,
                    steps = streams.steps.size,
                    resp = streams.resp.size,
                    gravity = streams.gravity.size,
                )
                rowsDecoded += counts.hr + counts.rr + counts.events + counts.battery +
                    counts.spo2 + counts.skinTemp + counts.steps + counts.resp + counts.gravity
                println(
                    "  [insert] hr=${counts.hr} rr=${counts.rr} events=${counts.events} " +
                        "battery=${counts.battery} spo2=${counts.spo2} skinTemp=${counts.skinTemp} " +
                        "steps=${counts.steps} resp=${counts.resp} gravity=${counts.gravity} " +
                        "device=…${devId.takeLast(8)}",
                )
                counts
            },
            enqueueRaw = { meta, blob ->
                println("  [outbox] batch=${meta.batchId} frames=${meta.frameCount} blob=${blob.size}B (raw capture)")
            },
            setCursor = { name, trim ->
                println("  [cursor] $name=$trim")
            },
            ackTrim = { trim, endData ->
                chunksAcked += 1
                lastTrimAcked = trim
                val hex = endData.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                println("  [ack] #$chunksAcked trim=$trim endData=$hex")
                sessionScope.launch {
                    session.send(
                        CommandNumber.HISTORICAL_DATA_RESULT,
                        byteArrayOf(0x01) + endData,
                        WriteType.WithResponse,
                    )
                }
            },
            rejectedSink = { frames, trim, fam ->
                rejectedArchived += frames.size
                println("  [rejected] count=${frames.size} trim=$trim family=$fam (archive stand-in, not written to disk)")
                true
            },
            log = { println("  [backfiller] $it") },
        )
        backfiller.begin(family)
        rearmWatchdog(backfiller)

        sessionScope.launch {
            session.frames.collect { frame ->
                framesSeen += 1
                if (framesSeen <= 10 || framesSeen % 100 == 0) {
                    println(
                        "  [frame] #$framesSeen char=…${frame.characteristicUuid.takeLast(12)} " +
                            "type=${frame.parsed.typeName} crcOk=${frame.parsed.crcOk}",
                    )
                }
                rearmWatchdog(backfiller)
                backfiller.ingest(frame.raw)
                when (classifyHistoricalMeta(frame.parsed)) {
                    HistoricalMeta.Complete -> {
                        println("  [meta] HISTORY_COMPLETE")
                        completed = true
                        doneSignal.complete("complete")
                    }
                    else -> Unit
                }
            }
        }
        session.state
            .onEach { println("  [state] $it") }
            .launchIn(sessionScope)

        try {
            session.connect(sessionScope)
            println("backfill-harness: connected + handshake sent; kicking SEND_HISTORICAL_DATA …")
            // Payload MUST be [0x00], not empty (Swift beginBackfill, BLEManager.swift:1519-1523:
            // "verified on-device that this strap serves type-47 only with [0x00]").
            session.send(CommandNumber.SEND_HISTORICAL_DATA, byteArrayOf(0x00), WriteType.WithResponse)
            println("backfill-harness: offload requested; holding up to ${seconds}s (early-exit on COMPLETE/watchdog) …")

            withTimeoutOrNull(seconds * 1000) { doneSignal.await() }
            if (!completed && !timedOut) {
                println("backfill-harness: time budget elapsed without HISTORY_COMPLETE or watchdog fire")
            }
            session.disconnect()
        } catch (t: Throwable) {
            println("backfill-harness: session failed — ${t::class.simpleName}: ${t.message}")
            var cause = t.cause
            var depth = 1
            while (cause != null && depth <= 8) {
                println("  cause[$depth]: ${cause::class.simpleName}: ${cause.message}")
                cause = cause.cause
                depth += 1
            }
        } finally {
            watchdogJob?.cancel()
            sessionScope.cancel()
        }
    }
    println(
        "backfill-harness: frames=$framesSeen chunksAcked=$chunksAcked rowsDecoded=$rowsDecoded " +
            "rejectedArchived=$rejectedArchived lastTrimAcked=$lastTrimAcked completed=$completed " +
            "timedOut=$timedOut → " + if (chunksAcked > 0) "OK" else "NO CHUNKS ACKED",
    )
    kotlin.system.exitProcess(if (chunksAcked > 0) 0 else 1)
}
