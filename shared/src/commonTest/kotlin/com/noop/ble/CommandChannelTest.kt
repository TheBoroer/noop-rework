package com.noop.ble

import com.noop.protocol.AlarmPayload
import com.noop.protocol.CommandNumber
import com.noop.protocol.DeviceFamily
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Flow-5 command planning (2c-2 Task 14): pins the exact `(cmd, payload, writeType)` sequences the
 * Swift BLEManager sent, byte-for-byte, plus the WHOOP 5/MG allowlist + maverick haptics remap.
 *
 * Byte fixtures come from the Swift suites/captures:
 *  - SetAlarmPayloadTests.swift (epoch 1_781_912_880 wire capture, PR #535)
 *  - BLEManager.swift:1389-1397 (maverick notify body, decoded from the "maverick" app binary)
 *  - BLEManager.swift:2512-2520 (buzz), 2137-2156 (rename), 1440-1450 (battery)
 */
class CommandChannelTest {

    // ---- payload builders -------------------------------------------------------------------

    @Test
    fun advertisingNamePayload_isH2zLayout() {
        // [0x00, 0x00] + UTF-8 + [0x00] — verified against the whoop-rename prototype.
        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0x4E, 0x4F, 0x4F, 0x50, 0x00),
            CommandPlanner.advertisingNamePayload("NOOP"),
        )
    }

    @Test
    fun advertisingNamePayload_clampsTo24Utf8Bytes() {
        val long = "A".repeat(30)
        val payload = CommandPlanner.advertisingNamePayload(long)
        assertEquals(2 + 24 + 1, payload.size, "2-byte header + 24 name bytes + NUL")
        assertContentEquals(
            "A".repeat(24).encodeToByteArray(),
            payload.copyOfRange(2, payload.size - 1),
        )
    }

    @Test
    fun advertisingNamePayload_neverSplitsAMultibyteCharacter() {
        // 13 × 'é' (2 UTF-8 bytes each) = 26 bytes → must clamp to 12 chars (24 bytes),
        // not 24.5: a split é would make the strap advertise mojibake.
        val payload = CommandPlanner.advertisingNamePayload("é".repeat(13))
        assertContentEquals(
            "é".repeat(12).encodeToByteArray(),
            payload.copyOfRange(2, payload.size - 1),
        )
    }

    @Test
    fun maverickNotifyBody_matchesDecodedMaverickCapture() {
        // [0x01, effects 47/152, u16 LE loopControl 0, overallLoop 0…] — 12 bytes so the puffin
        // inner record stays 4-byte aligned (BLEManager.swift:1389).
        val body = CommandPlanner.maverickNotifyBody()
        assertEquals(12, body.size)
        assertContentEquals(
            byteArrayOf(0x01, 47, 152.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0),
            body,
        )
    }

    @Test
    fun buzzPayload_isPattern2Loops3() {
        assertContentEquals(byteArrayOf(2, 3, 0, 0, 0), CommandPlanner.buzzPayload())
    }

    // ---- WHOOP 5/MG allowlist ---------------------------------------------------------------

    @Test
    fun whoop5Allows_verifiedPuffinCommandsOnly() {
        listOf(
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
        ).forEach { assertTrue(CommandPlanner.whoop5Allows(it), "$it must pass the 5/MG gate") }

        listOf(
            CommandNumber.GET_BATTERY_LEVEL,
            CommandNumber.SET_ADVERTISING_NAME,
            CommandNumber.START_RAW_DATA,
        ).forEach { assertFalse(CommandPlanner.whoop5Allows(it), "$it has no verified 5/MG framing") }
    }

    @Test
    fun whoop5Allows_setConfig_onlyWithDeepDataOptIn() {
        // SET_CONFIG writes a persistent feature flag — must never fire on a default install (#174).
        assertFalse(CommandPlanner.whoop5Allows(CommandNumber.SET_CONFIG))
        assertTrue(CommandPlanner.whoop5Allows(CommandNumber.SET_CONFIG, deepDataEnabled = true))
    }

    @Test
    fun whoop5Allows_setDeviceConfig_onlyWithBroadcastHrOptIn() {
        assertFalse(CommandPlanner.whoop5Allows(CommandNumber.SET_DEVICE_CONFIG))
        assertTrue(
            CommandPlanner.whoop5Allows(CommandNumber.SET_DEVICE_CONFIG, broadcastHrEnabled = true),
        )
    }

    // ---- resolve: family gate + maverick remap ------------------------------------------------

    @Test
    fun resolve_whoop4_passesEverythingThroughUntouched() {
        val send = PlannedSend(CommandNumber.RUN_HAPTICS_PATTERN, CommandPlanner.buzzPayload(), withResponse = true)
        assertEquals(send, CommandPlanner.resolve(DeviceFamily.WHOOP4, send))
        val rename = PlannedSend(CommandNumber.SET_ADVERTISING_NAME, byteArrayOf(0, 0, 0x41, 0))
        assertEquals(rename, CommandPlanner.resolve(DeviceFamily.WHOOP4, rename))
    }

    @Test
    fun resolve_whoop5_remapsHapticsToMaverick() {
        // A real-MG capture showed the strap rejecting cmd 79 with COMMAND_RESPONSE result=0x03.
        val resolved = CommandPlanner.resolve(
            DeviceFamily.WHOOP5,
            PlannedSend(CommandNumber.RUN_HAPTICS_PATTERN, CommandPlanner.buzzPayload(), withResponse = true),
        )
        assertNotNull(resolved)
        assertEquals(CommandNumber.RUN_HAPTIC_PATTERN_MAVERICK, resolved.cmd)
        assertContentEquals(CommandPlanner.maverickNotifyBody(), resolved.payload)
        assertTrue(resolved.withResponse, "remap must preserve the write type")
    }

    @Test
    fun resolve_whoop5_dropsNonAllowlistedCommands() {
        assertNull(
            CommandPlanner.resolve(
                DeviceFamily.WHOOP5,
                PlannedSend(CommandNumber.GET_BATTERY_LEVEL, byteArrayOf(0x00)),
            ),
            "skip, don't error — matches the Swift log+return",
        )
    }

    // ---- alarm plans --------------------------------------------------------------------------

    @Test
    fun planArmAlarm_whoop4_isSetClockBothForms_thenRev1_thenReadback() {
        val nowUnixSeconds = 1_781_900_000L
        val wakeEpochMs = 1_781_912_880_000L // @ujix's btsnoop capture epoch (PR #535)
        val plan = CommandPlanner.planArmAlarm(DeviceFamily.WHOOP4, wakeEpochMs, nowUnixSeconds)

        val clockPayloads = ClockSync.setClockPayloads(DeviceFamily.WHOOP4, nowUnixSeconds)
        assertEquals(clockPayloads.size + 2, plan.size)

        // 1..n: SET_CLOCK (both forms) so the strap RTC is UTC-correct before arming.
        clockPayloads.forEachIndexed { i, expected ->
            assertEquals(CommandNumber.SET_CLOCK, plan[i].cmd)
            assertContentEquals(expected, plan[i].payload)
        }
        // n+1: the 9-byte rev-1 body — wire-capture fixture from SetAlarmPayloadTests.swift.
        val arm = plan[clockPayloads.size]
        assertEquals(CommandNumber.SET_ALARM_TIME, arm.cmd)
        assertContentEquals(
            byteArrayOf(0x01, 0x30, 0xD5.toByte(), 0x35, 0x6A, 0x00, 0x00, 0x00, 0x00),
            arm.payload,
        )
        // n+2: GET_ALARM_TIME [0x01] readback (#401).
        val readback = plan.last()
        assertEquals(CommandNumber.GET_ALARM_TIME, readback.cmd)
        assertContentEquals(byteArrayOf(0x01), readback.payload)
    }

    @Test
    fun planArmAlarm_whoop5_isRev4BodyAlone() {
        val wakeEpochMs = 1_781_912_880_500L
        val plan = CommandPlanner.planArmAlarm(DeviceFamily.WHOOP5, wakeEpochMs, nowUnixSeconds = 0L)
        assertEquals(1, plan.size, "5/MG does not re-set the clock in the alarm path")
        assertEquals(CommandNumber.SET_ALARM_TIME, plan[0].cmd)
        assertContentEquals(AlarmPayload.build(wakeEpochMs), plan[0].payload)
        assertEquals(20, plan[0].payload.size, "REVISION_4 body")
        assertEquals(0x04, plan[0].payload[0].toInt())
    }

    @Test
    fun planDisableAlarm_familyForms() {
        val w4 = CommandPlanner.planDisableAlarm(DeviceFamily.WHOOP4)
        assertEquals(CommandNumber.DISABLE_ALARM, w4.cmd)
        assertContentEquals(byteArrayOf(0x01), w4.payload)

        val w5 = CommandPlanner.planDisableAlarm(DeviceFamily.WHOOP5)
        assertEquals(CommandNumber.DISABLE_ALARM, w5.cmd)
        assertContentEquals(byteArrayOf(0x02, 0xFF.toByte()), w5.payload)
    }

    @Test
    fun planGetAlarm_isReadbackForm() {
        val get = CommandPlanner.planGetAlarm()
        assertEquals(CommandNumber.GET_ALARM_TIME, get.cmd)
        assertContentEquals(byteArrayOf(0x01), get.payload)
    }

    // ---- buzz ---------------------------------------------------------------------------------

    @Test
    fun planBuzz_whoop4_isHapticsThenRunAlarm_bothAcked() {
        val plan = CommandPlanner.planBuzz(DeviceFamily.WHOOP4)
        assertEquals(2, plan.size)
        assertEquals(CommandNumber.RUN_HAPTICS_PATTERN, plan[0].cmd)
        assertContentEquals(byteArrayOf(2, 3, 0, 0, 0), plan[0].payload)
        assertEquals(CommandNumber.RUN_ALARM, plan[1].cmd)
        assertContentEquals(byteArrayOf(0x01), plan[1].payload)
        assertTrue(plan.all { it.withResponse }, "buzz writes are .withResponse (BLEManager.swift:2513)")
    }

    @Test
    fun planBuzz_whoop5_resolvesToMaverickPlusRunAlarmRev2() {
        val resolved = CommandPlanner.planBuzz(DeviceFamily.WHOOP5)
            .map { assertNotNull(CommandPlanner.resolve(DeviceFamily.WHOOP5, it)) }
        assertEquals(CommandNumber.RUN_HAPTIC_PATTERN_MAVERICK, resolved[0].cmd)
        assertContentEquals(CommandPlanner.maverickNotifyBody(), resolved[0].payload)
        assertEquals(CommandNumber.RUN_ALARM, resolved[1].cmd)
        assertContentEquals(byteArrayOf(0x02, 0x01), resolved[1].payload)
        assertTrue(resolved.all { it.withResponse })
    }

    // ---- battery (#77) ------------------------------------------------------------------------

    @Test
    fun planReadBattery_whoop4_usesCommand_whoop5_usesGatt() {
        val w4 = CommandPlanner.planReadBattery(DeviceFamily.WHOOP4)
        assertNotNull(w4, "4.0: 0x2A19 is a stub reporting 100 — must use GET_BATTERY_LEVEL")
        assertEquals(CommandNumber.GET_BATTERY_LEVEL, w4.cmd)
        assertContentEquals(byteArrayOf(0x00), w4.payload)

        assertNull(
            CommandPlanner.planReadBattery(DeviceFamily.WHOOP5),
            "5/MG: read the standard GATT battery characteristic instead",
        )
    }

    // ---- rename -------------------------------------------------------------------------------

    @Test
    fun planRename_whoop4Only_trimmed_acked() {
        val send = CommandPlanner.planRename(DeviceFamily.WHOOP4, "  Strand  ")
        assertNotNull(send)
        assertEquals(CommandNumber.SET_ADVERTISING_NAME, send.cmd)
        assertContentEquals(CommandPlanner.advertisingNamePayload("Strand"), send.payload)
        assertTrue(send.withResponse)
    }

    @Test
    fun planRename_refusesWhoop5AndBlankNames() {
        assertNull(CommandPlanner.planRename(DeviceFamily.WHOOP5, "Strand"))
        assertNull(CommandPlanner.planRename(DeviceFamily.WHOOP4, "   "))
        assertNull(CommandPlanner.planRename(DeviceFamily.WHOOP4, ""))
    }
}
