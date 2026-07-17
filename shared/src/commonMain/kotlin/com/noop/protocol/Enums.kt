package com.noop.protocol

/**
 * On-wire enums for the WHOOP protocol. Each constant carries its raw (on-wire) Int value and
 * every enum offers a `fromRaw(Int)` companion lookup that returns null for unknown codes.
 *
 * Values mirror the canonical schema (whoop_protocol.json) and the project SHARED CONTRACT.
 * These are deliberately a curated subset of the full device enum tables — only the codes the
 * offline companion app reads or sends. Unknown codes are surfaced by name elsewhere (see
 * [Framing.enumLabel]); they are not added here so the enums stay small and intentional.
 */

/** Frame packet type (envelope byte at offset 4 for Whoop 4.0). */
enum class PacketType(val rawValue: Int) {
    COMMAND(35),
    COMMAND_RESPONSE(36),
    PUFFIN_COMMAND(37),
    PUFFIN_COMMAND_RESPONSE(38),
    REALTIME_DATA(40),
    REALTIME_RAW_DATA(43),
    HISTORICAL_DATA(47),
    EVENT(48),
    METADATA(49),
    CONSOLE_LOGS(50),
    REALTIME_IMU_DATA_STREAM(51),
    HISTORICAL_IMU_DATA_STREAM(52);

    companion object {
        private val byRaw = entries.associateBy { it.rawValue }
        fun fromRaw(raw: Int): PacketType? = byRaw[raw]
    }
}

/** METADATA frame sub-type (historical-offload state machine). */
enum class MetadataType(val rawValue: Int) {
    HISTORY_START(1),
    HISTORY_END(2),
    HISTORY_COMPLETE(3);

    companion object {
        private val byRaw = entries.associateBy { it.rawValue }
        fun fromRaw(raw: Int): MetadataType? = byRaw[raw]
    }
}

/** EVENT frame event code (offset 6 in an EVENT frame). */
enum class EventNumber(val rawValue: Int) {
    BATTERY_LEVEL(3),
    CHARGING_ON(7),
    CHARGING_OFF(8),
    WRIST_ON(9),
    WRIST_OFF(10),
    DOUBLE_TAP(14),
    // Observed on WHOOP 4.0 firmware (#76 HW capture): emitted once per SET_CLOCK write,
    // ~1s after the command — a clock-set acknowledgement event.
    CLOCK_SET_ACK(16),
    TEMPERATURE_LEVEL(17),
    BLE_BONDED(23),
    BLE_REALTIME_HR_ON(33),
    BLE_REALTIME_HR_OFF(34),
    // 57/58/60 come from the Swift decompile. WHOOP 4.0 firmware does NOT emit 57 live:
    // two on-wrist HW captures (#76) show the strap is silent at wake time and instead sends
    // STRAP_ALARM_STOPPED(100) once the buzz ends — likely 5/MG or app-driven only.
    STRAP_DRIVEN_ALARM_EXECUTED(57),
    APP_DRIVEN_ALARM_EXECUTED(58),
    HAPTICS_FIRED(60),
    // Observed on WHOOP 4.0 firmware (#76 HW capture): fires when a strap-driven alarm stops,
    // both user-dismissed (double-tap; payload bytes 010200) and untouched/timed-out (010000).
    // Payload byte likely encodes the stop reason. Also seen: unmapped 0x3F(63) — periodic
    // status frame with a battery echo, deliberately left unmapped for now.
    STRAP_ALARM_STOPPED(100);

    companion object {
        private val byRaw = entries.associateBy { it.rawValue }
        fun fromRaw(raw: Int): EventNumber? = byRaw[raw]
    }
}

/**
 * Curated, SAFE command codes for *sending* to the strap. Destructive commands
 * (reboot / firmware load / force-trim / ship-mode / power-cycle / fuel-gauge reset / BLE DFU)
 * are deliberately excluded so the in-app sender can never brick or wipe the device.
 */
enum class CommandNumber(val rawValue: Int) {
    TOGGLE_REALTIME_HR(3),
    // REPORT_VERSION_INFO (7): WHOOP 4.0 firmware/version read. The strap answers with the bundled
    // component versions (`fw_harvard` a.b.c.d, `fw_boylston` a.b.c.d). A documented READ command,
    // separate from the firmware-LOAD opcodes. Mirrors Swift `WhoopCommand.reportVersionInfo`.
    REPORT_VERSION_INFO(7),
    SET_CLOCK(10),
    GET_CLOCK(11),
    SEND_HISTORICAL_DATA(22),
    // The historical-offload trim/ack command. Sent (with response) to confirm one HISTORY_END
    // chunk so the strap may trim it; payload = [0x01] + the verbatim 8-byte HISTORY_END end_data.
    // Port of Swift `WhoopCommand.historicalDataResult` (whoop_protocol.json: 23 HISTORICAL_DATA_RESULT).
    HISTORICAL_DATA_RESULT(23),
    GET_BATTERY_LEVEL(26),
    GET_DATA_RANGE(34),
    GET_HELLO_HARVARD(35),
    // GET_HELLO (145): WHOOP 5.0/MG hello. The response carries the device name plus `fw_version`
    // a.b.c.d. Older 4.0 firmware replies "unsupported" (0a03) and is ignored. Mirrors Swift
    // `WhoopCommand.getHello`.
    GET_HELLO(145),
    // GET_ADVERTISING_NAME_HARVARD (76) — read the WHOOP 4.0's BLE advertising name from the strap
    // firmware. Read-only twin of SET_ADVERTISING_NAME(77) below; sent once per connect in the
    // WHOOP-faithful handshake (BLEManager.swift:3258) and by the Devices card refresh. Safe: a
    // pure read, replied with a COMMAND_RESPONSE carrying the name string. Port of Swift
    // `WhoopCommand.getAdvertisingNameHarvard` (Commands.swift:24).
    GET_ADVERTISING_NAME(76),
    SEND_R10_R11_REALTIME(63),
    // WHOOP 5.0/MG (device family GOOSE/MAVERICK) one-shot buzz. Gen-4 straps use the legacy
    // RUN_HAPTICS_PATTERN(79) below; a 5/MG strap only honors this command.
    RUN_HAPTIC_PATTERN_MAVERICK(19),
    SET_ALARM_TIME(66),
    GET_ALARM_TIME(67),
    RUN_ALARM(68),
    DISABLE_ALARM(69),
    // SET_ADVERTISING_NAME_HARVARD (77) — rename the WHOOP 4.0's BLE advertising name on the strap
    // firmware (the name the OS shows in Bluetooth). Payload: [0x00,0x00] + UTF-8 name + [0x00]; the
    // strap reboots to apply. WHOOP 4.0 only (a 5/MG uses puffin framing + a different config path).
    // Port of Swift WhoopCommand.setAdvertisingNameHarvard.
    SET_ADVERTISING_NAME(77),
    RUN_HAPTICS_PATTERN(79),
    GET_ALL_HAPTICS_PATTERN(80),
    // SET_CONFIG / SET_FF_VALUE (0x78) — write one persistent feature flag. The 5/MG "enable R22
    // packets" sequence (Whoop5Config) sends 15 of these to switch on the deep biometric streams.
    // Reversible; gated behind the deep-data opt-in; iOS/Android only. (#174)
    SET_CONFIG(120),
    // SET_DEVICE_CONFIG (0x77) — write one persistent DEVICE-config value (distinct from the
    // feature-flag SET_CONFIG/0x78). Used for the "Broadcast HR" flag whoop_live_hr_in_adv_ind_pkt,
    // which makes the strap advertise its HR as a standard 0x180D BLE sensor. Validated on real
    // hardware (paired on a Garmin Edge 840). Reversible; gated behind the broadcast-HR opt-in. (#181)
    SET_DEVICE_CONFIG(119),
    START_RAW_DATA(81),
    STOP_RAW_DATA(82),
    // High-frequency-sync window around a historical offload (WHOOP 4.0). Verified on a real strap
    // that plain SEND_HISTORICAL_DATA serves without it (BleSession doc), but the official app
    // brackets offloads with 96/97, so the safe table carries both. Port of Swift
    // WhoopCommand.enterHighFreqSync / exitHighFreqSync (Commands.swift:34-38).
    ENTER_HIGH_FREQ_SYNC(96),
    EXIT_HIGH_FREQ_SYNC(97),
    // Extended battery/charger telemetry read (COMMAND_RESPONSE). A pure read, safe.
    // Port of Swift WhoopCommand.getExtendedBatteryInfo (Commands.swift:39).
    GET_EXTENDED_BATTERY_INFO(98),
    // IMU / optical stream toggles used by the raw-data capture tooling. Reversible mode
    // switches, not destructive. Ports of Swift WhoopCommand.toggleIMUMode / enableOpticalData
    // (Commands.swift:40-41).
    TOGGLE_IMU_MODE(106),
    ENABLE_OPTICAL_DATA(107),
    STOP_HAPTICS(122),
    SELECT_WRIST(123);

    companion object {
        private val byRaw = entries.associateBy { it.rawValue }
        fun fromRaw(raw: Int): CommandNumber? = byRaw[raw]
    }
}
