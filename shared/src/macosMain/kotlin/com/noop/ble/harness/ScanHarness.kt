package com.noop.ble.harness

import com.juul.kable.Scanner
import com.noop.ble.BleScanner
import com.noop.ble.WhoopModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 2c-2 Task 9 manual-gate harness: live Kable scan from a bare macOS terminal.
 *
 *   ./gradlew :shared:linkDebugExecutableMacosArm64
 *   shared/build/bin/macosArm64/debugExecutable/scan-harness.kexe \
 *       [--seconds N] [--whoop4|--whoop5] [--all]
 *
 * Default: watch BOTH families for 30s and print every advertisement that passes the
 * WHOOP filter. `--all` drops the service filter and prints EVERY advertisement seen —
 * use it to distinguish "Bluetooth/TCC broken" (nothing at all) from "strap not
 * advertising" (other devices visible, no WHOOP).
 * Exit code 0 when at least one advertisement was printed, 1 otherwise.
 */
private fun stateName(s: Long): String = when (s) {
    0L -> "unknown"
    1L -> "resetting"
    2L -> "unsupported"
    3L -> "unauthorized"
    4L -> "poweredOff"
    5L -> "poweredOn"
    else -> "?($s)"
}

private fun authName(a: Long): String = when (a) {
    0L -> "notDetermined"
    1L -> "restricted"
    2L -> "denied"
    3L -> "allowedAlways"
    else -> "?($a)"
}

/** Prints raw CBCentralManager state transitions — TCC/power diagnostic. */
private fun printCentralState(seconds: Long) {
    println("  CBManager.authorization=${authName(platform.CoreBluetooth.CBManager.Companion.authorization())}")
    val queue = platform.darwin.dispatch_queue_create("scan-harness.state", null)
    val delegate = object : platform.darwin.NSObject(), platform.CoreBluetooth.CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: platform.CoreBluetooth.CBCentralManager) {
            println("  centralManagerDidUpdateState → ${stateName(central.state)}")
        }
    }
    val central = platform.CoreBluetooth.CBCentralManager(delegate, queue)
    platform.posix.sleep(seconds.toUInt())
    println("  final state=${stateName(central.state)}")
}

private fun printUsage() {
    println(
        """
        scan-harness — NOOP BLE hardware harness (one binary, argv dispatch)

        Usage: scan-harness.kexe [mode] [options]

        Modes:
          scan (default)  Scan for straps; print name/rssi/model/id per advertisement
          session         Connect + handshake, read battery/clock, hold session open
          realtime        Arm realtime streams; print HR/RR inserts until budget elapses
          backfill        Historical offload: chunks → outbox → ack; runs to HISTORY_COMPLETE
          command         Flow-5 commands (rename, alarm, buzz)
          --help | -h     This text

        Common options:
          --whoop4 | --whoop5   Filter by model (default: both)
          --seconds N           Time budget (defaults: scan 30, session 60, realtime 90, backfill 180)

        Mode-specific:
          scan:     --all (unfiltered), --state (CBCentralManager state probe)
          command:  --skip-buzz, --wait-fire, --keep-alarm, --alarm-minutes N, --rename NAME
        """.trimIndent()
    )
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun main(args: Array<String>) {
    // One binary, one entrypoint (gradle pins com.noop.ble.harness.main): argv dispatch.
    // `scan-harness.kexe session …` runs the Task 10 connect/session harness (SessionHarness.kt);
    // `scan-harness.kexe realtime …` runs the Task 11 realtime-streams harness (RealtimeHarness.kt);
    // `scan-harness.kexe backfill …` runs the Task 13 historical-offload harness
    // (BackfillHarness.kt); `scan-harness.kexe command …` runs the Task 14 flow-5 command harness
    // (CommandHarness.kt); everything else falls through to the Task 9 scan harness below. `scan`
    // is accepted as an explicit alias for the default.
    when (args.firstOrNull()) {
        "--help", "-h", "help" -> {
            printUsage()
            return
        }
        "session" -> {
            runSessionHarness(args.drop(1).toTypedArray())
            return
        }
        "realtime" -> {
            runRealtimeHarness(args.drop(1).toTypedArray())
            return
        }
        "backfill" -> {
            runBackfillHarness(args.drop(1).toTypedArray())
            return
        }
        "command" -> {
            runCommandHarness(args.drop(1).toTypedArray())
            return
        }
        "scan" -> return mainScan(args.drop(1).toTypedArray())
    }
    mainScan(args)
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun mainScan(args: Array<String>) {
    if (args.contains("--state")) {
        println("scan-harness: CBCentralManager state probe …")
        printCentralState(5)
        return
    }
    val seconds = args.toList().zipWithNext()
        .firstOrNull { it.first == "--seconds" }?.second?.toLongOrNull() ?: 30L
    val all = args.contains("--all")
    val models = when {
        args.contains("--whoop4") -> setOf(WhoopModel.WHOOP4)
        args.contains("--whoop5") -> setOf(WhoopModel.WHOOP5)
        else -> setOf(WhoopModel.WHOOP4, WhoopModel.WHOOP5)
    }
    println(
        if (all) "scan-harness: UNFILTERED scan for ${seconds}s …"
        else "scan-harness: scanning ${models.joinToString()} for ${seconds}s …"
    )

    var found = 0
    runBlocking {
        try {
            withTimeoutOrNull(seconds * 1000) {
                if (all) {
                    val seen = mutableSetOf<String>()
                    Scanner {}.advertisements.collect { adv ->
                        found += 1
                        val id = adv.identifier.toString()
                        if (seen.add(id)) {
                            println("  #${seen.size} ${adv.peripheralName ?: "(no name)"}  rssi=${adv.rssi}  id=$id")
                        }
                    }
                } else {
                    BleScanner().discoveries(models).collect { d ->
                        found += 1
                        println("  #$found ${d.name}  rssi=${d.rssi}  model=${d.model ?: "?"}  id=${d.identifier}")
                    }
                }
            }
        } catch (t: Throwable) {
            println("scan-harness: scan failed — ${t::class.simpleName}: ${t.message}")
        }
    }
    println(if (found > 0) "scan-harness: OK — $found advertisement(s)" else "scan-harness: nothing discovered")
    kotlin.system.exitProcess(if (found > 0) 0 else 1)
}
