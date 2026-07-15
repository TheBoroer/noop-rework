package com.noop.ble

import com.juul.kable.WriteType
import com.noop.protocol.AlarmPayload
import com.noop.protocol.CommandNumber
import com.noop.protocol.DeviceFamily
import com.noop.protocol.Whoop5Config

/**
 * Flow 5: device commands (Phase 2c-2 Task 14) — the Kotlin port of the BLEManager command path
 * (alarms, haptics, battery, rename) over [BleSession.send].
 *
 * Curated, SAFE command codes only ([CommandNumber]). Destructive commands (reboot / firmware
 * load / force-trim / ship-mode / power-cycle / fuel-gauge reset / BLE DFU) are deliberately
 * excluded so the in-app sender can never brick or wipe the device.
 *
 * Split planner/executor: [CommandPlanner] is pure (family → the exact `(cmd, payload)` sequence,
 * unit-testable against the Swift byte fixtures); [CommandChannel] is the thin live wrapper that
 * resolves each planned send through the WHOOP 5/MG gate + maverick remap and writes it.
 */

/** One planned command write: opcode + payload + write type, before the family gate/remap. */
data class PlannedSend(
    val cmd: CommandNumber,
    val payload: ByteArray,
    val withResponse: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is PlannedSend && cmd == other.cmd && withResponse == other.withResponse &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        (cmd.hashCode() * 31 + payload.contentHashCode()) * 31 + withResponse.hashCode()
}

object CommandPlanner {

    /**
     * Max UTF-8 byte length for a strap advertising name. BLE caps the whole advertising payload
     * at 31 bytes; keeping the name ≤ 24 leaves room for the rest of the AD structure (flags +
     * service UUID) the strap still has to broadcast. (Commands.swift:149)
     */
    const val MAX_ADVERTISING_NAME_BYTES = 24

    /**
     * SET_ADVERTISING_NAME_HARVARD (77) payload: `[0x00, 0x00] + <UTF-8 name> + [0x00]`.
     * The 2-byte header + trailing NUL is the `h2z` layout verified against the whoop-rename
     * prototype on WHOOP 4.0 firmware. The name is clamped to [MAX_ADVERTISING_NAME_BYTES] on a
     * character boundary (never splitting a multibyte character) so it can't overflow the BLE
     * advertising packet. Port of Swift WhoopCommand.advertisingNamePayload (Commands.swift:157).
     */
    fun advertisingNamePayload(name: String): ByteArray {
        var clamped = name
        while (clamped.encodeToByteArray().size > MAX_ADVERTISING_NAME_BYTES) {
            clamped = clamped.dropLast(1)
        }
        return byteArrayOf(0x00, 0x00) + clamped.encodeToByteArray() + byteArrayOf(0x00)
    }

    /**
     * The WHOOP 5/MG maverick "notify" haptic body (#48, decoded from the working "maverick"
     * app's binary): `[0x01, effects(8), loopControl(u16 LE), overallLoop]` with the notify
     * preset (effects 47,152) — NOT the 4.0 `[patternId, loops, …]`. 12 bytes; puffin framing
     * pads the inner record to a 4-byte boundary, which this length needs (BLEManager.swift:1389).
     */
    fun maverickNotifyBody(): ByteArray =
        byteArrayOf(0x01, 47, 152.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)

    /**
     * WHOOP 4.0 one-shot buzz payload for RUN_HAPTICS_PATTERN(79): patternId=2, 3 loops
     * (BLEManager.swift:2513). On a 5/MG [resolve] remaps the opcode+payload to the maverick buzz.
     */
    fun buzzPayload(): ByteArray = byteArrayOf(2, 3, 0, 0, 0)

    /**
     * The WHOOP 5/MG send gate (BLEManager.swift:1370-1385): only commands with verified puffin
     * framing may go out. SET_CONFIG (the R22 deep-stream unlock) is allowed ONLY while the
     * deep-data experiment is opted in — it writes a persistent feature flag, so it must never
     * fire on a default install (#174). SET_DEVICE_CONFIG (the Broadcast-HR flag) is allowed
     * ONLY while that opt-in is on (#181). Everything else is skipped, not errored — matching
     * the Swift `log + return`.
     */
    fun whoop5Allows(
        cmd: CommandNumber,
        deepDataEnabled: Boolean = false,
        broadcastHrEnabled: Boolean = false,
    ): Boolean = when (cmd) {
        CommandNumber.TOGGLE_REALTIME_HR,
        CommandNumber.RUN_HAPTICS_PATTERN,
        CommandNumber.SET_ALARM_TIME,
        CommandNumber.GET_ALARM_TIME,
        CommandNumber.RUN_ALARM,
        CommandNumber.DISABLE_ALARM,
        CommandNumber.SEND_HISTORICAL_DATA,
        CommandNumber.HISTORICAL_DATA_RESULT,
        CommandNumber.SET_CLOCK,
        CommandNumber.GET_CLOCK,
        -> true
        CommandNumber.SET_CONFIG -> deepDataEnabled
        CommandNumber.SET_DEVICE_CONFIG -> broadcastHrEnabled
        else -> false
    }

    /**
     * Apply the family gate + the 5/MG haptics remap to one planned send.
     *
     * WHOOP 4.0: passes through untouched. WHOOP 5/MG: `null` when the command isn't allowlisted
     * (caller logs + skips); RUN_HAPTICS_PATTERN becomes RUN_HAPTIC_PATTERN_MAVERICK(0x13) with
     * the maverick notify body — a real-MG capture showed the strap rejecting 79 with
     * COMMAND_RESPONSE result=0x03 (BLEManager.swift:1389-1397).
     */
    fun resolve(
        family: DeviceFamily,
        send: PlannedSend,
        deepDataEnabled: Boolean = false,
        broadcastHrEnabled: Boolean = false,
    ): PlannedSend? {
        if (family == DeviceFamily.WHOOP4) return send
        if (!whoop5Allows(send.cmd, deepDataEnabled, broadcastHrEnabled)) return null
        if (send.cmd == CommandNumber.RUN_HAPTICS_PATTERN) {
            return PlannedSend(
                CommandNumber.RUN_HAPTIC_PATTERN_MAVERICK,
                maverickNotifyBody(),
                send.withResponse,
            )
        }
        return send
    }

    /**
     * Arm the strap-driven wake alarm (BLEManager.swift armStrapAlarm, 2395-2460).
     *
     * WHOOP 4.0: SET_CLOCK first (both forms, [ClockSync.setClockPayloads]) so the strap RTC is
     * UTC-correct, then the rev-1 9-byte SET_ALARM_TIME, then a GET_ALARM_TIME `[0x01]` readback
     * (#401: armed + strap-reports + fired become one decidable sequence; log-only, never gated on).
     *
     * WHOOP 5/MG: the REVISION_4 body alone — the strap maintains its RTC (set during the connect
     * handshake) and the official app's alarm path doesn't re-set it. EXPERIMENTAL / UNCONFIRMED
     * on 5/MG: arming ACKs but a strap-driven fire has not been captured; the CALLER must gate
     * this behind the Experimental opt-in (this planner just builds bytes).
     */
    fun planArmAlarm(family: DeviceFamily, wakeEpochMs: Long, nowUnixSeconds: Long): List<PlannedSend> =
        when (family) {
            DeviceFamily.WHOOP4 ->
                ClockSync.setClockPayloads(DeviceFamily.WHOOP4, nowUnixSeconds)
                    .map { PlannedSend(CommandNumber.SET_CLOCK, it) } +
                    PlannedSend(CommandNumber.SET_ALARM_TIME, AlarmPayload.setAlarmRev1(wakeEpochMs / 1000L)) +
                    PlannedSend(CommandNumber.GET_ALARM_TIME, byteArrayOf(0x01))
            DeviceFamily.WHOOP5 ->
                listOf(PlannedSend(CommandNumber.SET_ALARM_TIME, AlarmPayload.build(wakeEpochMs)))
        }

    /** GET_ALARM_TIME (67) readback, payload `[0x01]` (BLEManager.swift:2459). */
    fun planGetAlarm(): PlannedSend = PlannedSend(CommandNumber.GET_ALARM_TIME, byteArrayOf(0x01))

    /** DISABLE_ALARM: WHOOP 4.0 `[0x01]`, 5/MG REVISION_2 `[0x02, 0xFF]` (BLEManager.swift:2478-2482). */
    fun planDisableAlarm(family: DeviceFamily): PlannedSend = when (family) {
        DeviceFamily.WHOOP4 -> PlannedSend(CommandNumber.DISABLE_ALARM, byteArrayOf(0x01))
        DeviceFamily.WHOOP5 -> PlannedSend(CommandNumber.DISABLE_ALARM, AlarmPayload.disableRev2())
    }

    /**
     * One-shot buzz (BLEManager.swift buzzStrapOnce, 2512-2520): RUN_HAPTICS_PATTERN + a RUN_ALARM
     * chaser, both `.withResponse`. WHOOP 4.0: `[2,3,0,0,0]` + RUN_ALARM `[0x01]`; on a 5/MG
     * [resolve] remaps the haptics send to the maverick buzz and RUN_ALARM uses REVISION_2
     * `[0x02, alarmId]`.
     */
    fun planBuzz(family: DeviceFamily): List<PlannedSend> = listOf(
        PlannedSend(CommandNumber.RUN_HAPTICS_PATTERN, buzzPayload(), withResponse = true),
        when (family) {
            DeviceFamily.WHOOP4 ->
                PlannedSend(CommandNumber.RUN_ALARM, byteArrayOf(0x01), withResponse = true)
            DeviceFamily.WHOOP5 ->
                PlannedSend(CommandNumber.RUN_ALARM, AlarmPayload.runAlarmRev2(), withResponse = true)
        },
    )

    /**
     * One Haptic Clock pulse (BLEManager.swift buzzTimeNow, 2551-2556): the SAME
     * RUN_HAPTICS_PATTERN send as [planBuzz] but with a caller-chosen loop count — a LONG
     * ("tens") pulse is felt as a heavier buzz (2 stacked loops), a SHORT ("units") pulse as
     * a light one (1). No RUN_ALARM chaser and NO `.withResponse` (the legacy per-pulse send
     * used the `send()` defaults). On a 5/MG [CommandChannel.run]'s resolve step remaps this
     * to the maverick notify buzz exactly as for the one-shot.
     */
    fun planHapticPulse(loops: Int): PlannedSend = PlannedSend(
        CommandNumber.RUN_HAPTICS_PATTERN,
        byteArrayOf(2, loops.coerceIn(0, 255).toByte(), 0, 0, 0),
    )

    /**
     * One arbitrary haptics-pattern send (AppModel.runPattern, 963): RUN_HAPTICS_PATTERN with a
     * caller-chosen pattern-id byte + loop count, legacy `send()` defaults (no `.withResponse`).
     * [planHapticPulse] is the pattern-2 special case of this.
     */
    fun planHapticPattern(patternId: Int, loops: Int): PlannedSend = PlannedSend(
        CommandNumber.RUN_HAPTICS_PATTERN,
        byteArrayOf(
            patternId.coerceIn(0, 255).toByte(),
            loops.coerceIn(0, 255).toByte(),
            0, 0, 0,
        ),
    )

    /**
     * Stop a running haptics pattern (AppModel.stopHaptics, 979-980): STOP_HAPTICS with the legacy
     * `[0x00]` payload and `send()` defaults. Deliberately NOT added to [whoop5Allows] — the legacy
     * Swift 5/MG allowlist dropped it too, so on a 5/MG this stays a logged no-op exactly like
     * BLEManager (a 4.0 honors it).
     */
    fun planStopHaptics(): PlannedSend = PlannedSend(
        CommandNumber.STOP_HAPTICS,
        byteArrayOf(0x00),
    )

    /**
     * The backfill kick (BLEManager.beginBackfill, 1519-1533): SEND_HISTORICAL_DATA with the
     * `[0x00]` payload, `.withResponse`. Payload MUST be `[0x00]`, NOT empty — verified on-device
     * that the strap serves type-47 only with `[0x00]` (empty → 0 frames on a clean stable link
     * with ~2k records pending); the Mac ground-truth offload (re/sync_openwhoop.py) uses `[0x00]`
     * too. The connect-handshake / store-ready / family guards stay with the caller (the shim),
     * exactly like the legacy method.
     */
    fun planSendHistorical(): PlannedSend = PlannedSend(
        CommandNumber.SEND_HISTORICAL_DATA,
        byteArrayOf(0x00),
        withResponse = true,
    )

    /**
     * Ack one HISTORY_END chunk so the strap may trim it (BLEManager.ackHistoricalChunk,
     * 1471-1477): HISTORICAL_DATA_RESULT with `[0x01] + endData`, `.withResponse`, where
     * [endData] is the verbatim 8 bytes of HISTORY_END metadata (trim u32 + next u32) produced
     * by [Backfiller.endData] — family offset handling lives there, not here. Callers fall back
     * to 8 zero bytes when the frame was too short, matching legacy.
     */
    fun planHistoricalAck(endData: ByteArray): PlannedSend = PlannedSend(
        CommandNumber.HISTORICAL_DATA_RESULT,
        byteArrayOf(0x01) + endData,
        withResponse = true,
    )

    /**
     * The Broadcast-HR device-config write (BLEManager.setBroadcastHr, 2106-2118, #181):
     * SET_DEVICE_CONFIG carrying `whoop_live_hr_in_adv_ind_pkt` = ASCII '1'/'0', with the b3
     * byte 0x01 ahead of the 33-byte body, `.withResponse`. The family/connect/bond guards (and
     * their log lines) stay with the caller, exactly like the legacy method.
     */
    fun planBroadcastHr(on: Boolean): PlannedSend = PlannedSend(
        CommandNumber.SET_DEVICE_CONFIG,
        byteArrayOf(0x01) +
            Whoop5Config.deviceConfigBody("whoop_live_hr_in_adv_ind_pkt", if (on) 0x31 else 0x30),
        withResponse = true,
    )

    /**
     * The 15-flag enable_r22 deep-data sequence (BLEManager.enableWhoop5DeepData, 2068-2098, #174):
     * one SET_CONFIG write per [Whoop5Config.enableR22Sequence] flag — b3 byte 0x01 + the 40-byte
     * flag body, `.withResponse` — byte-identical to the legacy loop. The legacy 80 ms stagger
     * between writes lives in [WhoopBleClient.enableWhoop5DeepData]; the on-wrist / encrypted-bond
     * guards stay with the caller like every other command.
     */
    fun planDeepDataEnable(): List<PlannedSend> = Whoop5Config.enableR22Sequence.map { flag ->
        PlannedSend(
            CommandNumber.SET_CONFIG,
            byteArrayOf(0x01) + Whoop5Config.payloadBody(flag.name, flag.value),
            withResponse = true,
        )
    }

    /**
     * Battery refresh is FAMILY-SPECIFIC (#77): on a WHOOP 4.0 the standard 0x2A19 characteristic
     * is a STUB that reports a constant 100 — the real charge only comes from the proprietary
     * GET_BATTERY_LEVEL command (COMMAND_RESPONSE, u16/10). A WHOOP 5/MG is the opposite: it uses
     * ONLY 0x2A19, so this returns `null` and the caller reads the GATT battery characteristic
     * (BLEManager.swift refreshBattery, 1440-1450).
     */
    fun planReadBattery(family: DeviceFamily): PlannedSend? = when (family) {
        DeviceFamily.WHOOP4 -> PlannedSend(CommandNumber.GET_BATTERY_LEVEL, byteArrayOf(0x00))
        DeviceFamily.WHOOP5 -> null
    }

    /**
     * Rename the strap's BLE advertising name — WHOOP 4.0 ONLY (a 5/MG uses puffin framing + a
     * different config path); returns `null` for a 5/MG or a blank name, matching the Swift
     * guards (BLEManager.swift renameStrap, 2137-2156). The strap may reboot to apply.
     */
    fun planRename(family: DeviceFamily, rawName: String): PlannedSend? {
        val name = rawName.trim()
        if (family != DeviceFamily.WHOOP4 || name.isEmpty()) return null
        return PlannedSend(
            CommandNumber.SET_ADVERTISING_NAME,
            advertisingNamePayload(name),
            withResponse = true,
        )
    }
}

/**
 * The live flow-5 channel: plans with [CommandPlanner], resolves each send through the family
 * gate/remap, and writes it on [BleSession.send] (which owns per-family framing + the sequence
 * counter). Skipped sends (5/MG non-allowlisted) are logged, mirroring Swift.
 */
class CommandChannel(
    private val session: BleSession,
    private val family: DeviceFamily,
    // Runtime-mutable (was ctor-fixed): the legacy Settings toggles flip deep-data / Broadcast-HR
    // mid-connection, so the client must be able to update the gating on the LIVE channel too.
    var deepDataEnabled: Boolean = false,
    var broadcastHrEnabled: Boolean = false,
    private val log: (String) -> Unit = {},
) {
    suspend fun run(planned: List<PlannedSend>) {
        planned.forEach { run(it) }
    }

    suspend fun run(planned: PlannedSend) {
        val resolved = CommandPlanner.resolve(family, planned, deepDataEnabled, broadcastHrEnabled)
        if (resolved == null) {
            log("send(${planned.cmd}) skipped — no WHOOP 5/MG framing for this command yet")
            return
        }
        session.send(
            resolved.cmd,
            resolved.payload,
            if (resolved.withResponse) WriteType.WithResponse else WriteType.WithoutResponse,
        )
        log("→ ${resolved.cmd} payload=${resolved.payload.toHexString()}")
    }

    suspend fun armAlarm(wakeEpochMs: Long, nowUnixSeconds: Long) =
        run(CommandPlanner.planArmAlarm(family, wakeEpochMs, nowUnixSeconds))

    suspend fun getAlarm() = run(CommandPlanner.planGetAlarm())

    suspend fun disableAlarm() = run(CommandPlanner.planDisableAlarm(family))

    suspend fun buzz() = run(CommandPlanner.planBuzz(family))

    /** `false` when the family has no battery COMMAND (5/MG: read GATT 0x2A19 instead). */
    suspend fun readBattery(): Boolean {
        val planned = CommandPlanner.planReadBattery(family) ?: return false
        run(planned)
        return true
    }

    /** `false` when the rename was refused (5/MG, or a blank name). */
    suspend fun rename(rawName: String): Boolean {
        val planned = CommandPlanner.planRename(family, rawName) ?: return false
        run(planned)
        return true
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { b -> ((b.toInt() and 0xFF) or 0x100).toString(16).substring(1) }
}
