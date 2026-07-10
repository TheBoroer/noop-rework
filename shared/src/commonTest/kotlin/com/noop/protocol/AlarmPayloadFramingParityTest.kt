package com.noop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Split out of FramingTest (Task 6, protocol hoist) when [AlarmPayload] still needed java.time and
 * stayed in androidMain; hoisted to commonTest itself in Task 3 (kotlinx-datetime adoption) once
 * AlarmPayload moved to commonMain. Kept as its own file rather than merged back into FramingTest
 * to keep this task's diff scoped to date-logic conversion.
 */
class AlarmPayloadFramingParityTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun puffinCommandFrame_alarmFramesMatchSwiftParityGoldens() {
        // Cross-platform parity pins: the macOS port (DeviceFamilyFramingTests) asserts these SAME
        // three full-frame hexes, so both platforms are locked to identical alarm bytes. The
        // SET_ALARM_TIME inner is 23 bytes → pad4 → 24 (declLen 28); the rev-2 bodies pad 5 → 8.
        // (RUN_ALARM rev2 [0x02, alarmId] is built inline, the Kotlin client no longer ships a
        // helper for it, but the Mac test-buzz path still sends it, so the bytes stay pinned here.)
        val alarm = Framing.puffinCommandFrame(cmd = 66, seq = 1, payload = AlarmPayload.build(1_700_000_000_123L))
        assertEquals("aa011c000001e381230142040100f15365be0f2f980000000000000000071e00392f2ac9", hex(alarm))
        assertEquals("aa010c000001e74123014502ff000000267ffc4f",
            hex(Framing.puffinCommandFrame(cmd = 69, seq = 1, payload = AlarmPayload.disableRev2())))
        assertEquals("aa010c000001e741230144020100000017cd19e2",
            hex(Framing.puffinCommandFrame(cmd = 68, seq = 1, payload = byteArrayOf(0x02, 0x01))))
    }
}
