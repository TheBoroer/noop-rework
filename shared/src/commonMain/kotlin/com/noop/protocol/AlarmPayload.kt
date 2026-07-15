@file:OptIn(ExperimentalTime::class)

package com.noop.protocol

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * WHOOP 5.0/MG ("puffin") firmware wake-alarm command payload encoder.
 *
 * These are WHOOP 5.0/MG protocol facts — command numbers, field offsets, byte layouts — documented
 * as factual wire-format observations for interoperability; no proprietary code is reproduced.
 * (Adopted from PR #85, iHateSubscriptions.)
 *
 * EXPERIMENTAL / UNCONFIRMED: unlike the maverick buzz (hardware-confirmed on a real MG), the rev4
 * alarm layout below is self-consistent and modelled on the official app but has NOT been confirmed
 * to actually wake a strap on our side (no captured STRAP_DRIVEN_ALARM_EXECUTED event). The caller
 * therefore gates it behind the Experimental opt-in so a normal user can't rely on an alarm that
 * might silently not fire. All multi-byte fields are little-endian.
 */
object AlarmPayload {
    private const val OVERALL_LOOP: Byte = 7        // overallWaveformLoopControl (alarm pattern)
    private const val DURATION_SECONDS: Byte = 30   // alarmDurationInSeconds

    /** The canonical WHOOP wake waveform-effect pair (same 47/152 the notification buzz uses). */
    private val WAVEFORM_EFFECTS = byteArrayOf(47, 152.toByte(), 0, 0, 0, 0, 0, 0)

    /**
     * Next future epoch-millis for local wake [hour]:[minute], relative to [nowMs] in [zone].
     * Today's occurrence if strictly in the future, else tomorrow's (next occurrence after now).
     */
    fun nextWakeEpochMs(hour: Int, minute: Int, nowMs: Long, zone: TimeZone): Long {
        // During a DST fall-back overlap hour (a wall-clock time that occurs twice), toInstant's
        // fresh offset resolution below may pick a different offset than the old ZonedDateTime
        // preferred-offset behavior, differing by the DST shift; accepted divergence.
        val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)
        val candidate = now.date.atTime(hour, minute) // second + nanos cleared → subseconds 0
        val target = if (candidate.toInstant(zone).toEpochMilliseconds() > nowMs) candidate
        else candidate.date.plus(1, DateTimeUnit.DAY).atTime(hour, minute)
        return target.toInstant(zone).toEpochMilliseconds()
    }

    /**
     * SET_ALARM_TIME (cmd 66) REVISION_4 body — 20 bytes; the strap arms its own RTC and fires the
     * wake haptic itself (a strap-driven wake event) even with the phone away. Wire layout:
     * ```
     *   [0]      0x04 (REVISION_4)
     *   [1]      alarmId
     *   [2..5]   u32 LE epoch seconds
     *   [6..7]   u16 LE subseconds = (ms % 1000) * 32768 / 1000   (1/32768-s fixed point)
     *   [8..19]  haptic pattern: 8 effects + u16 LE loopControl(0) + overallLoop(7) + duration(30)
     * ```
     */
    fun build(wakeEpochMs: Long, alarmId: Int = 1): ByteArray {
        val seconds = wakeEpochMs / 1000L
        val subseconds = ((wakeEpochMs % 1000L) * 32768L) / 1000L // u16 fixed point
        val out = ByteArray(20)
        out[0] = 4 // REVISION_4
        out[1] = alarmId.toByte()
        out[2] = (seconds and 0xFF).toByte()
        out[3] = ((seconds ushr 8) and 0xFF).toByte()
        out[4] = ((seconds ushr 16) and 0xFF).toByte()
        out[5] = ((seconds ushr 24) and 0xFF).toByte()
        out[6] = (subseconds and 0xFF).toByte()
        out[7] = ((subseconds ushr 8) and 0xFF).toByte()
        WAVEFORM_EFFECTS.copyInto(out, destinationOffset = 8)
        out[16] = 0x00 // loopControlForEffects LE lo
        out[17] = 0x00 // loopControlForEffects LE hi
        out[18] = OVERALL_LOOP
        out[19] = DURATION_SECONDS
        return out
    }

    /** DISABLE_ALARM (cmd 69) REVISION_2 body `[0x02, 0xFF]` (the 5/MG form). */
    fun disableRev2(): ByteArray = byteArrayOf(0x02, 0xFF.toByte())

    /**
     * SET_ALARM_TIME (cmd 66) Rev1 body — the WHOOP 4.0 form, 9 bytes:
     * `[0x01] + <epoch u32 LE> + [0x00,0x00] (subseconds) + [0x00,0x00] (haptic-mode)`.
     *
     * The two trailing zero bytes are REQUIRED: the earlier 7-byte form was ACKed but never
     * buzzed (#428); @ujix's btsnoop capture of the official app on a real 4.0 (PR #535) shows
     * 9 bytes, and that frame is confirmed to fire on-device. Callers must send SET_CLOCK first
     * so the strap RTC is UTC-correct. Port of Swift WhoopCommand.setAlarmPayload
     * (Commands.swift:120-146), byte layout pinned there by SetAlarmPayloadTests.
     * [epochSec] is clamped to the u32 range rather than trapping (pre-1970 / post-2106 dates).
     */
    fun setAlarmRev1(epochSec: Long): ByteArray {
        val clamped = epochSec.coerceIn(0L, 0xFFFFFFFFL)
        return byteArrayOf(
            0x01,
            (clamped and 0xFF).toByte(),
            ((clamped ushr 8) and 0xFF).toByte(),
            ((clamped ushr 16) and 0xFF).toByte(),
            ((clamped ushr 24) and 0xFF).toByte(),
            0x00, 0x00, // subseconds (always 0 — minute precision)
            0x00, 0x00, // haptic-mode field (from the official-app wire capture, #535)
        )
    }

    /** RUN_ALARM (cmd 68) REVISION_2 body `[0x02, alarmId]` (the 5/MG form; BLEManager.swift:2515). */
    fun runAlarmRev2(alarmId: Int = 1): ByteArray = byteArrayOf(0x02, alarmId.toByte())
}
