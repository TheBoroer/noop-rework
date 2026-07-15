package com.noop.ble

import com.noop.ble.RealtimeAction.HeavyStream
import com.noop.ble.RealtimeAction.ToggleRealtimeHr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinuousHrvScheduleTest {

    @Test
    fun windowContains_plainDaytimeInterval() {
        // start <= end: [9:00, 17:00)
        assertTrue(ContinuousHrvSchedule.windowContains(9 * 60, 9 * 60, 17 * 60))
        assertTrue(ContinuousHrvSchedule.windowContains(16 * 60 + 59, 9 * 60, 17 * 60))
        assertFalse(ContinuousHrvSchedule.windowContains(17 * 60, 9 * 60, 17 * 60)) // exclusive end
        assertFalse(ContinuousHrvSchedule.windowContains(8 * 60 + 59, 9 * 60, 17 * 60))
    }

    @Test
    fun windowContains_wrapsMidnight() {
        // Default 22:00 → 07:00
        val s = 22 * 60
        val e = 7 * 60
        assertTrue(ContinuousHrvSchedule.windowContains(23 * 60, s, e))
        assertTrue(ContinuousHrvSchedule.windowContains(0, s, e))
        assertTrue(ContinuousHrvSchedule.windowContains(6 * 60 + 59, s, e))
        assertTrue(ContinuousHrvSchedule.windowContains(s, s, e)) // inclusive start
        assertFalse(ContinuousHrvSchedule.windowContains(e, s, e)) // exclusive end
        assertFalse(ContinuousHrvSchedule.windowContains(12 * 60, s, e))
    }

    @Test
    fun streamWanted_modeComposition() {
        val noon = 12 * 60
        val midnight = 0
        // continuous OFF → never
        assertFalse(ContinuousHrvSchedule.streamWanted(false, false, midnight))
        assertFalse(ContinuousHrvSchedule.streamWanted(false, true, midnight))
        // continuous ON, overnight OFF → ALWAYS (24/7)
        assertTrue(ContinuousHrvSchedule.streamWanted(true, false, noon))
        // continuous ON, overnight ON → window-gated
        assertTrue(ContinuousHrvSchedule.streamWanted(true, true, midnight))
        assertFalse(ContinuousHrvSchedule.streamWanted(true, true, noon))
    }
}

class RealtimePolicyTest {

    private fun policy(minute: () -> Int = { 12 * 60 }) = RealtimePolicy(minuteOfDay = minute)

    @Test
    fun startRealtime_armsHeavyStreamAndToggleOnEdge() {
        val p = policy()
        val actions = p.startRealtime()
        assertEquals(listOf(HeavyStream(on = true), ToggleRealtimeHr(on = true)), actions)
        assertTrue(p.realtimeArmed)
        // Steady state: reconcile sends nothing (edge-only).
        assertEquals(emptyList(), p.reconcile())
        // Re-entering start while already armed re-requests only the heavy stream, no toggle.
        assertEquals(listOf(HeavyStream(on = true)), p.startRealtime())
    }

    @Test
    fun stopRealtime_disarmsWhenNothingElseWantsIt() {
        val p = policy()
        p.startRealtime()
        val actions = p.stopRealtime()
        assertEquals(listOf(HeavyStream(on = false), ToggleRealtimeHr(on = false)), actions)
        assertFalse(p.realtimeArmed)
    }

    @Test
    fun stopRealtime_keepsToggleWhenContinuousCaptureHoldsIt() {
        val p = policy() // noon; ALWAYS mode ignores the window
        p.setKeepRealtimeForData(keep = true, overnightOnly = false)
        p.startRealtime()
        // Screen goes away: heavy stream stops, but the toggle stays armed for capture.
        assertEquals(listOf<RealtimeAction>(HeavyStream(on = false)), p.stopRealtime())
        assertTrue(p.realtimeArmed)
    }

    @Test
    fun overnightWindow_gatesTheContinuousWant() {
        var minute = 12 * 60 // noon — outside 22:00→07:00
        val p = policy { minute }
        assertEquals(emptyList(), p.setKeepRealtimeForData(keep = true, overnightOnly = true))
        assertFalse(p.wantsRealtime())
        // Clock crosses into the window: reconcile arms on the edge.
        minute = 23 * 60
        assertEquals(listOf<RealtimeAction>(ToggleRealtimeHr(on = true)), p.reconcile())
        // Clock leaves the window: reconcile disarms.
        minute = 8 * 60
        assertEquals(listOf<RealtimeAction>(ToggleRealtimeHr(on = false)), p.reconcile())
    }

    @Test
    fun standardHRFallback_skipsArmingButNeverBlocksDisarm() {
        val p = policy()
        p.startRealtime()
        // Detector trips: disarm edge fires even in fallback.
        assertEquals(listOf<RealtimeAction>(ToggleRealtimeHr(on = false)), p.noteMarginalRadioTripped())
        assertFalse(p.realtimeArmed)
        // Continuous capture cannot re-arm while the fallback is latched.
        assertEquals(emptyList(), p.setKeepRealtimeForData(keep = true, overnightOnly = false))
        assertFalse(p.realtimeArmed)
        // An explicit screen start clears the fallback and arms again.
        val actions = p.startRealtime()
        assertTrue(ToggleRealtimeHr(on = true) in actions)
        assertTrue(p.realtimeArmed)
    }

    @Test
    fun handshake_reArmsFromFreshIntent() {
        val p = policy()
        p.startRealtime()
        p.onDisconnect()
        assertFalse(p.realtimeArmed)
        assertTrue(p.screenWantsRealtime) // intent survives the drop
        val actions = p.onHandshakeComplete()
        assertEquals(listOf(ToggleRealtimeHr(on = true), HeavyStream(on = true)), actions)
        assertTrue(p.realtimeArmed)
    }

    @Test
    fun handshake_reArmWithoutScreenSkipsHeavyStream() {
        val p = policy()
        p.setKeepRealtimeForData(keep = true, overnightOnly = false)
        p.onDisconnect()
        assertEquals(listOf<RealtimeAction>(ToggleRealtimeHr(on = true)), p.onHandshakeComplete())
    }

    @Test
    fun handshake_outsideWindowDoesNotReArm() {
        var minute = 23 * 60
        val p = policy { minute }
        p.setKeepRealtimeForData(keep = true, overnightOnly = true)
        assertTrue(p.realtimeArmed)
        p.onDisconnect()
        // Reconnect lands OUTSIDE the window: the stale precomputed want must not re-arm (#927).
        minute = 12 * 60
        assertEquals(emptyList(), p.onHandshakeComplete())
        assertFalse(p.realtimeArmed)
    }

    @Test
    fun handshake_fallbackSkipsReArm() {
        val p = policy()
        p.startRealtime()
        p.noteMarginalRadioTripped()
        p.onDisconnect()
        assertEquals(emptyList(), p.onHandshakeComplete()) // rides 0x2A37 (#86)
        assertFalse(p.realtimeArmed)
    }
}
