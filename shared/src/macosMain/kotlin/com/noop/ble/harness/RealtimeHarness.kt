package com.noop.ble.harness

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import com.noop.protocol.StandardHeartRate
import com.noop.ble.BleSession
import com.noop.ble.FrameTransport
import com.noop.ble.FrameTransportPolicy
import com.noop.ble.RealtimePolicy
import com.noop.ble.WhoopAdvertisementFilter
import com.noop.ble.WhoopModel
import com.noop.protocol.DeviceFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Phase 2c-2 Task 11 manual-gate harness: live realtime streams (flow 3) over Kable.
 *
 *   ./gradlew :shared:linkDebugExecutableMacosArm64
 *   shared/build/bin/macosArm64/debugExecutable/scan-harness.kexe realtime \
 *       [--seconds N] [--whoop4|--whoop5]
 *
 * Flow: scan → connect (Task 10 session layer) → attach [FrameTransport] → arm realtime via
 * [RealtimePolicy.startRealtime] + [FrameTransport.applyRealtimeActions] (TOGGLE_REALTIME_HR
 * [0x01]; SEND_R10_R11_REALTIME [0x01] on WHOOP4) → hold for N seconds (default 90) printing
 * every flushed [insert] batch. The insert/enqueueRaw seams are print-only stand-ins for the
 * Room + outbox writers, so this verifies scan → connect → arm → decode → clock-correlate →
 * cadence-flush on real hardware without a database.
 *
 * Exit 0 when at least one decoded HR row flowed through the insert seam.
 */
@OptIn(ExperimentalUuidApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
fun runRealtimeHarness(args: Array<String>) {
    val seconds = args.toList().zipWithNext()
        .firstOrNull { it.first == "--seconds" }?.second?.toLongOrNull() ?: 90L
    val models = when {
        args.contains("--whoop4") -> setOf(WhoopModel.WHOOP4)
        args.contains("--whoop5") -> setOf(WhoopModel.WHOOP5)
        else -> setOf(WhoopModel.WHOOP4, WhoopModel.WHOOP5)
    }
    println("realtime-harness: scanning ${models.joinToString()} for the first WHOOP …")

    var hrRows = 0
    var rrRows = 0
    var flushes = 0
    var framesSeen = 0
    var rawDumped = 0
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
            println("realtime-harness: no WHOOP discovered within 30s")
            return@runBlocking
        }
        val model = WhoopAdvertisementFilter.modelFor(adv.uuids.map { it.toString() })
            ?: models.first()
        val family = when (model) {
            WhoopModel.WHOOP4 -> DeviceFamily.WHOOP4
            WhoopModel.WHOOP5 -> DeviceFamily.WHOOP5
        }
        println(
            "realtime-harness: found ${adv.name ?: "(no name)"} rssi=${adv.rssi} " +
                "id=${adv.identifier} family=$family",
        )

        val peripheral = Peripheral(adv)
        val session = BleSession(peripheral, family, log = { println("  [session] $it") })
        val sessionScope = CoroutineScope(coroutineContext + SupervisorJob())

        // Print-only persistence seams. Tight cadence (8 frames / 5s) so batches land while a
        // human is watching instead of the production 64/30s.
        val transport = FrameTransport(
            family = family,
            deviceId = adv.identifier.toString(),
            insert = { streams, deviceId ->
                flushes += 1
                hrRows += streams.hr.size
                rrRows += streams.rr.size
                val lastHr = streams.hr.lastOrNull()
                println(
                    "  [insert] #$flushes hr=${streams.hr.size} rr=${streams.rr.size} " +
                        "events=${streams.events.size} battery=${streams.battery.size} " +
                        (lastHr?.let { "lastHr=${it.bpm}bpm@${it.ts} " } ?: "") +
                        "device=…${deviceId.takeLast(8)}",
                )
            },
            enqueueRaw = { meta, blob ->
                println("  [outbox] batch=${meta.batchId} blob=${blob.size}B (raw capture)")
            },
            policy = FrameTransportPolicy(maxFrames = 8, maxIntervalSeconds = 5.0),
            log = { println("  [transport] $it") },
        )
        transport.attach(session, sessionScope)

        // Frame ticker: prove airtime without flooding the terminal (R10/R11 is bursty).
        sessionScope.launch {
            session.frames.collect { frame ->
                framesSeen += 1
                val respCmd = frame.parsed.parsed["resp_cmd"]
                if (framesSeen <= 10 || framesSeen % 50 == 0 || respCmd != null) {
                    println(
                        "  [frame] #$framesSeen char=…${frame.characteristicUuid.takeLast(12)} " +
                            "type=${frame.parsed.typeName} crcOk=${frame.parsed.crcOk}" +
                            (respCmd?.let { " resp_cmd=$it clock=${frame.parsed.parsed["clock"]}" } ?: ""),
                    )
                }
                // Off-wrist vs decode-bug discriminator: dump the first few realtime payloads.
                // Genuine off-wrist frames carry zeroed HR/RR fields but live accel/status bytes;
                // a decode-offset bug shows nonzero bytes where we read zeros.
                if (frame.parsed.typeName == "REALTIME_RAW_DATA" && rawDumped < 5) {
                    rawDumped += 1
                    val hex = frame.raw.joinToString(" ") { b ->
                        (b.toInt() and 0xFF).toString(16).padStart(2, '0')
                    }
                    println("  [raw#$rawDumped] len=${frame.raw.size} $hex")
                }
            }
        }
        session.state
            .onEach { println("  [state] $it") }
            .launchIn(sessionScope)

        // Screen-want path bypasses the overnight window gate, so minuteOfDay is irrelevant here.
        val policy = RealtimePolicy(minuteOfDay = { 12 * 60 })

        try {
            session.connect(sessionScope)
            println("realtime-harness: connected + handshake sent; arming realtime …")
            // Standard HR profile (0x2A37) — the stream that actually feeds ingestStandardHr,
            // i.e. the hr rows this gate counts. BleSession deliberately skips the standard
            // profiles (flow-3 concern), and Swift subscribes them in enableLiveNotifications()
            // right before arming (BLEManager.swift:2365-2377) — mirror that here. Without this
            // subscription hr=0 is structural, wrist or no wrist (t11 run: 188 frames / 0 rows).
            val hrChar = characteristicOf(
                kotlin.uuid.Uuid.parse("0000180d-0000-1000-8000-00805f9b34fb"),
                kotlin.uuid.Uuid.parse("00002a37-0000-1000-8000-00805f9b34fb"),
            )
            var hrNotifies = 0
            peripheral.observe(hrChar)
                .onStart { println("realtime-harness: subscribing 0x2A37 (standard HR) …") }
                .onEach { data ->
                    hrNotifies += 1
                    if (hrNotifies <= 3) {
                        val hex = data.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                        println("  [2a37#$hrNotifies] len=${data.size} $hex")
                    }
                    StandardHeartRate.parse(data)?.let { m ->
                        transport.ingestStandardHr(m.hr, m.rr, ts = platform.posix.time(null).toInt())
                    }
                }
                .catch { e -> println("realtime-harness: 0x2A37 observe FAILED — ${e::class.simpleName}: ${e.message}") }
                .launchIn(sessionScope)
            transport.applyRealtimeActions(session, policy.startRealtime())
            println("realtime-harness: armed (screen-want); holding for ${seconds}s …")

            withTimeoutOrNull(seconds * 1000) {
                delay(seconds * 1000)
            }
            println("realtime-harness: time budget elapsed; disarming + flushing")
            transport.applyRealtimeActions(session, policy.stopRealtime())
            transport.flush()
            session.disconnect()
        } catch (t: Throwable) {
            println("realtime-harness: session failed — ${t::class.simpleName}: ${t.message}")
            var cause = t.cause
            var depth = 1
            while (cause != null && depth <= 8) {
                println("  cause[$depth]: ${cause::class.simpleName}: ${cause.message}")
                cause = cause.cause
                depth += 1
            }
        } finally {
            sessionScope.cancel()
        }
    }
    println(
        "realtime-harness: frames=$framesSeen flushes=$flushes hr=$hrRows rr=$rrRows → " +
            if (hrRows > 0) "OK" else "NO HR ROWS",
    )
    kotlin.system.exitProcess(if (hrRows > 0) 0 else 1)
}
