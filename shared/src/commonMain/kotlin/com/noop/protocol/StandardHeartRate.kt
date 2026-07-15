package com.noop.protocol

import kotlin.math.roundToInt

/**
 * Pure parser for the standard BLE Heart Rate Measurement characteristic (0x2A37).
 * Byte-for-byte port of the Swift `StandardHeartRate` (Strand/BLE/StandardHeartRate.swift).
 * Returns the heart rate (bpm) and any R-R intervals (ms). Pure → unit-testable.
 */
object StandardHeartRate {

    /** Parse result: heart rate in bpm plus zero or more R-R intervals in milliseconds. */
    data class Measurement(val hr: Int, val rr: List<Int>)

    fun parse(data: ByteArray): Measurement? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        var idx = 1
        val hr: Int
        if (flags and 0x01 != 0) { // 16-bit HR
            if (idx + 1 >= data.size) return null
            hr = (data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)
            idx += 2
        } else { // 8-bit HR
            if (idx >= data.size) return null
            hr = data[idx].toInt() and 0xFF
            idx += 1
        }
        if (flags and 0x08 != 0) idx += 2 // skip Energy Expended (bit 3)
        val rr = mutableListOf<Int>()
        if ((flags shr 4) and 0x01 != 0) { // R-R present (bit 4)
            while (idx + 1 < data.size) {
                val raw = (data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)
                rr += (raw.toDouble() / 1024.0 * 1000.0).roundToInt() // 1/1024 s → ms
                idx += 2
            }
        }
        return Measurement(hr, rr)
    }
}
