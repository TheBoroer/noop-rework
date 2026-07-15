@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.noop.ble.harness

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.noop.ble.BleSession
import com.noop.ble.CommandChannel
import com.noop.ble.CommandPlanner
import com.noop.ble.WhoopAdvertisementFilter
import com.noop.ble.WhoopModel
import com.noop.protocol.DeviceFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * Phase 2c-2 Task 14 manual-gate harness: live flow-5 commands from a bare macOS terminal.
 *
 *   ./gradlew :shared:linkDebugExecutableMacosArm64
 *   shared/build/bin/macosArm64/debugExecutable/scan-harness.kexe command \
 *       [--whoop4|--whoop5] [--skip-buzz] [--alarm-minutes N] [--wait-fire] [--keep-alarm] \
 *       [--rename NAME]
 *
 * Sequence: scan → connect + handshake → battery (W4: GET_BATTERY_LEVEL COMMAND_RESPONSE must
 * carry a plausible soc%, NOT the 0x2A19 stub's 100) → buzz (physical confirmation!) → arm the
 * wake alarm N minutes out (default 3) → GET_ALARM_TIME readback → optionally hold for the
 * STRAP_DRIVEN_ALARM_EXECUTED(57) fire event → DISABLE_ALARM (unless --keep-alarm).
 *
 * Exit 0 when every attempted step got its expected reply frame (buzz counts as sent — the
 * vibration itself is the human part of the gate).
 */
fun runCommandHarness(args: Array<String>) {
    val models = when {
        args.contains("--whoop4") -> setOf(WhoopModel.WHOOP4)
        args.contains("--whoop5") -> setOf(WhoopModel.WHOOP5)
        else -> setOf(WhoopModel.WHOOP4, WhoopModel.WHOOP5)
    }
    val skipBuzz = args.contains("--skip-buzz")
    val waitFire = args.contains("--wait-fire")
    val keepAlarm = args.contains("--keep-alarm")
    val alarmMinutes = args.toList().zipWithNext()
        .firstOrNull { it.first == "--alarm-minutes" }?.second?.toLongOrNull() ?: 3L
    val renameTo = args.toList().zipWithNext().firstOrNull { it.first == "--rename" }?.second

    println("command-harness: scanning ${models.joinToString()} for the first WHOOP …")

    var batteryPct: Double? = null
    var alarmReadbackSeen = false
    var alarmFired = false
    var failures = 0

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
            println("command-harness: no WHOOP discovered within 30s")
            return@runBlocking
        }
        val model = WhoopAdvertisementFilter.modelFor(adv.uuids.map { it.toString() })
            ?: models.first()
        val family = when (model) {
            WhoopModel.WHOOP4 -> DeviceFamily.WHOOP4
            WhoopModel.WHOOP5 -> DeviceFamily.WHOOP5
        }
        println(
            "command-harness: found ${adv.name ?: "(no name)"} rssi=${adv.rssi} " +
                "id=${adv.identifier} family=$family",
        )

        val peripheral = Peripheral(adv)
        val session = BleSession(peripheral, family, log = { println("  [session] $it") })
        val sessionScope = CoroutineScope(coroutineContext + SupervisorJob())

        // Watch every decoded frame: battery COMMAND_RESPONSE, GET_ALARM_TIME readback, alarm event.
        sessionScope.launch {
            session.frames.collect { frame ->
                val p = frame.parsed.parsed
                val respCmd = p["resp_cmd"]?.toString() ?: ""
                val event = p["event"]?.toString() ?: ""
                when {
                    respCmd.contains("GET_BATTERY_LEVEL") -> {
                        batteryPct = p["battery_pct"] as? Double
                        println("  [reply] battery COMMAND_RESPONSE: pct=$batteryPct parsed=$p")
                    }
                    respCmd.contains("GET_ALARM_TIME") -> {
                        alarmReadbackSeen = true
                        println("  [reply] GET_ALARM_TIME readback: parsed=$p")
                    }
                    respCmd.isNotEmpty() ->
                        println("  [reply] ${frame.parsed.typeName}: parsed=$p")
                    event.contains("ALARM_EXECUTED") || event.contains("HAPTICS_FIRED") -> {
                        if (event.contains("STRAP_DRIVEN")) alarmFired = true
                        println("  [event] $event parsed=$p")
                    }
                }
            }
        }

        try {
            session.connect(sessionScope)
            println("command-harness: connected + handshake sent")
            delay(2_000) // let the handshake replies drain before flow-5 traffic

            val channel = CommandChannel(session, family, log = { println("  [cmd] $it") })

            // ── 1. battery ────────────────────────────────────────────────────────────────
            println("command-harness: STEP 1 — battery")
            if (channel.readBattery()) {
                withTimeoutOrNull(10_000) { while (batteryPct == null) delay(200) }
                if (batteryPct == null) {
                    println("  FAIL: no GET_BATTERY_LEVEL COMMAND_RESPONSE within 10s"); failures++
                } else {
                    println("  OK: battery=$batteryPct% (proprietary command, not the 0x2A19 stub)")
                }
            } else {
                println("  SKIP: $family reads GATT 0x2A19 instead (no battery command)")
            }

            // ── 2. buzz ───────────────────────────────────────────────────────────────────
            if (skipBuzz) {
                println("command-harness: STEP 2 — buzz SKIPPED (--skip-buzz)")
            } else {
                println("command-harness: STEP 2 — buzz (strap should vibrate NOW)")
                try {
                    channel.buzz()
                    println("  OK: buzz writes acked (.withResponse) — confirm the strap vibrated!")
                } catch (t: Throwable) {
                    println("  FAIL: buzz write — ${t::class.simpleName}: ${t.message}"); failures++
                }
            }

            // ── 3. rename (optional, W4 only, strap may reboot) ──────────────────────────
            if (renameTo != null) {
                println("command-harness: STEP 3 — rename to \"$renameTo\" (strap may reboot!)")
                if (channel.rename(renameTo)) println("  OK: rename write acked")
                else { println("  FAIL: rename refused (5/MG or blank)"); failures++ }
            }

            // ── 4. alarm arm + readback ───────────────────────────────────────────────────
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val wakeEpochMs = ((nowMs / 60_000L) + alarmMinutes) * 60_000L // round to minute
            val wakeInS = (wakeEpochMs - nowMs) / 1000
            println("command-harness: STEP 4 — arm alarm ${wakeInS}s out (epoch ${wakeEpochMs / 1000})")
            channel.armAlarm(wakeEpochMs, nowUnixSeconds = nowMs / 1000L)
            withTimeoutOrNull(10_000) { while (!alarmReadbackSeen) delay(200) }
            if (family == DeviceFamily.WHOOP4 && !alarmReadbackSeen) {
                // The arm plan already ends with GET_ALARM_TIME; poke once more before failing.
                channel.getAlarm()
                withTimeoutOrNull(5_000) { while (!alarmReadbackSeen) delay(200) }
            }
            if (alarmReadbackSeen) println("  OK: GET_ALARM_TIME readback received")
            else { println("  FAIL: no GET_ALARM_TIME readback within 15s"); failures++ }

            // ── 5. optional: hold for the strap-driven fire ───────────────────────────────
            if (waitFire) {
                val holdMs = (wakeEpochMs - Clock.System.now().toEpochMilliseconds()) + 90_000
                println("command-harness: STEP 5 — holding ${holdMs / 1000}s for STRAP_DRIVEN_ALARM_EXECUTED(57) …")
                withTimeoutOrNull(holdMs) { while (!alarmFired) delay(500) }
                if (alarmFired) println("  OK: strap-driven alarm FIRED")
                else { println("  FAIL: no STRAP_DRIVEN_ALARM_EXECUTED event"); failures++ }
            }

            // ── 6. disarm ─────────────────────────────────────────────────────────────────
            if (keepAlarm) {
                println("command-harness: leaving the alarm ARMED (--keep-alarm) — it WILL buzz at wake time")
            } else {
                println("command-harness: STEP 6 — DISABLE_ALARM (cleanup)")
                channel.disableAlarm()
                delay(1_000)
                println("  sent (${if (family == DeviceFamily.WHOOP4) "[0x01]" else "rev2 [0x02,0xFF]"})")
            }

            delay(2_000) // let trailing replies land
            session.disconnect()
        } catch (t: Throwable) {
            println("command-harness: FAILED — ${t::class.simpleName}: ${t.message}")
            var cause = t.cause
            var depth = 1
            while (cause != null && depth <= 8) {
                println("  cause[$depth]: ${cause::class.simpleName}: ${cause.message}")
                cause = cause.cause
                depth += 1
            }
            failures++
        } finally {
            sessionScope.cancel()
        }
    }

    println(
        if (failures == 0) "command-harness: OK — battery=${batteryPct ?: "n/a"}% " +
            "alarmReadback=$alarmReadbackSeen fired=$alarmFired"
        else "command-harness: $failures step(s) FAILED",
    )
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
