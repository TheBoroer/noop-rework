package com.noop.ble.harness

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.noop.ble.BleSession
import com.noop.ble.ReconnectPolicy
import com.noop.ble.WhoopAdvertisementFilter
import com.noop.ble.WhoopModel
import com.noop.protocol.DeviceFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Phase 2c-2 Task 10 manual-gate harness: live Kable connect/session from a bare macOS terminal.
 *
 *   ./gradlew :shared:linkDebugExecutableMacosArm64
 *   shared/build/bin/macosArm64/debugExecutable/scan-harness.kexe session \
 *       [--seconds N] [--whoop4|--whoop5]
 *
 * Flow: scan → connect to the first WHOOP seen → bond write → hello/version/clock handshake →
 * print every parsed reply → stay up for N seconds (default 60) printing connection-state
 * changes (walk out of range and back for the manual reconnect test; the harness re-runs
 * [BleSession.connect] after [ReconnectPolicy.RECONNECT_DELAY_SECONDS], mirroring the Swift
 * "rescanning in 3s" loop). Exit 0 when the handshake produced at least one decoded frame.
 */
@OptIn(ExperimentalUuidApi::class)
fun runSessionHarness(args: Array<String>) {
    val seconds = args.toList().zipWithNext()
        .firstOrNull { it.first == "--seconds" }?.second?.toLongOrNull() ?: 60L
    val models = when {
        args.contains("--whoop4") -> setOf(WhoopModel.WHOOP4)
        args.contains("--whoop5") -> setOf(WhoopModel.WHOOP5)
        else -> setOf(WhoopModel.WHOOP4, WhoopModel.WHOOP5)
    }
    println("session-harness: scanning ${models.joinToString()} for the first WHOOP …")

    var framesSeen = 0
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
            println("session-harness: no WHOOP discovered within 30s")
            return@runBlocking
        }
        val model = WhoopAdvertisementFilter.modelFor(adv.uuids.map { it.toString() })
            ?: models.first()
        val family = when (model) {
            WhoopModel.WHOOP4 -> DeviceFamily.WHOOP4
            WhoopModel.WHOOP5 -> DeviceFamily.WHOOP5
        }
        println(
            "session-harness: found ${adv.name ?: "(no name)"} rssi=${adv.rssi} " +
                "id=${adv.identifier} family=$family",
        )

        val peripheral = Peripheral(adv)
        val session = BleSession(peripheral, family, log = { println("  [session] $it") })
        val sessionScope = CoroutineScope(coroutineContext + SupervisorJob())

        // Print every decoded inbound frame — the hello/version/clock replies land here.
        sessionScope.launch {
            session.frames.collect { frame ->
                framesSeen += 1
                println(
                    "  [frame] char=…${frame.characteristicUuid.takeLast(12)} " +
                        "type=${frame.parsed.typeName} crcOk=${frame.parsed.crcOk} " +
                        "parsed=${frame.parsed.parsed}",
                )
            }
        }
        // Print raw state transitions for the manual out-of-range test.
        session.state
            .onEach { println("  [state] $it") }
            .launchIn(sessionScope)

        try {
            session.connect(sessionScope)
            println("session-harness: connected + handshake sent; holding for ${seconds}s …")

            // Hold the session open; on an unexpected drop, feed the reconnect policy and retry
            // after the Swift 3s delay (BLEManager.swift:2865) until the time budget runs out.
            withTimeoutOrNull(seconds * 1000) {
                while (true) {
                    val state = session.state.first { it is State.Disconnected }
                    val status = (state as State.Disconnected).status
                    // CoreBluetooth classifies the drop; treat a platform timeout as `timedOut`
                    // exactly like Swift's `CBError.connectionTimeout` check (2773).
                    val timedOut = status is State.Disconnected.Status.Timeout
                    val events = session.noteConnectionEnded(timedOut)
                    println(
                        "  [drop] status=$status timedOut=$timedOut " +
                            "marginalRadioTripped=${events.marginalRadioTripped} " +
                            "postBondLoopTripped=${events.postBondLoopTripped}",
                    )
                    delay((ReconnectPolicy.RECONNECT_DELAY_SECONDS * 1000).toLong())
                    println("session-harness: reconnecting …")
                    session.connect(sessionScope)
                }
            }
            println("session-harness: time budget elapsed; disconnecting")
            session.disconnect()
        } catch (t: Throwable) {
            println("session-harness: session failed — ${t::class.simpleName}: ${t.message}")
            // Print the full cause chain: Kable's awaitConnect wraps ANY IllegalStateException
            // as "Cannot connect peripheral that has been cancelled" (SharedRepeatableAction.
            // awaitConnect.kt:9), hiding the real failure.
            var cause = t.cause
            var depth = 1
            while (cause != null && depth <= 8) {
                println("  cause[$depth]: ${cause::class.simpleName}: ${cause.message}")
                cause = cause.cause
                depth += 1
            }
            println(t.stackTraceToString().lineSequence().take(30).joinToString("\n"))
        } finally {
            sessionScope.cancel()
        }
    }
    println(
        if (framesSeen > 0) "session-harness: OK — $framesSeen frame(s) decoded"
        else "session-harness: no frames decoded",
    )
    kotlin.system.exitProcess(if (framesSeen > 0) 0 else 1)
}
