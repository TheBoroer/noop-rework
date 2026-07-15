package com.noop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StandardHeartRateTest {

    @Test
    fun parse_8bitHrNoRr() {
        val m = StandardHeartRate.parse(byteArrayOf(0x00, 72))
        assertEquals(72, m?.hr)
        assertEquals(emptyList(), m?.rr)
    }

    @Test
    fun parse_16bitHr() {
        // flags bit0 = 16-bit HR; 0x0141 = 321 (synthetic, exercises the wide path)
        val m = StandardHeartRate.parse(byteArrayOf(0x01, 0x41, 0x01))
        assertEquals(321, m?.hr)
    }

    @Test
    fun parse_rrIntervalsConvertTo1024thsMs() {
        // flags bit4 = RR present; raw 1024 → 1000 ms, raw 512 → 500 ms
        val m = StandardHeartRate.parse(
            byteArrayOf(0x10, 60, 0x00, 0x04, 0x00, 0x02),
        )
        assertEquals(60, m?.hr)
        assertEquals(listOf(1000, 500), m?.rr)
    }

    @Test
    fun parse_skipsEnergyExpended() {
        // flags: RR present (bit4) + energy expended (bit3); EE two bytes skipped before RR
        val m = StandardHeartRate.parse(
            byteArrayOf(0x18, 55, 0x22, 0x11, 0x00, 0x04),
        )
        assertEquals(55, m?.hr)
        assertEquals(listOf(1000), m?.rr)
    }

    @Test
    fun parse_rejectsTruncated() {
        assertNull(StandardHeartRate.parse(byteArrayOf()))
        assertNull(StandardHeartRate.parse(byteArrayOf(0x01, 0x41))) // 16-bit flag, one byte
        assertNull(StandardHeartRate.parse(byteArrayOf(0x00))) // no HR byte
    }
}
