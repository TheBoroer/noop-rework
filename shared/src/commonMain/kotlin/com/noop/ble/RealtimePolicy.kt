package com.noop.ble

/**
 * Continuous-capture window schedule (#927) — pure port of the Swift `ContinuousHrvSchedule`
 * (BLEManager.swift:412) / Android NotifPrefs.inQuietHours semantics: minutes since LOCAL
 * midnight, inclusive start, exclusive end, and the window may cross midnight (22:00 → 07:00 by
 * default). Local wall time keeps it DST-agnostic the same way quiet hours are: a DST jump moves
 * the wall clock, the window definition never changes.
 *
 * The MODE is composed from the two persisted booleans so existing users need no migration:
 *   continuous OFF                → stream never held open (unchanged)
 *   continuous ON, overnight OFF  → ALWAYS: armed 24/7 (the pre-#927 behaviour)
 *   continuous ON, overnight ON   → OVERNIGHT: armed only inside the window
 *
 * Pure + value-typed so the predicate is unit-testable. [RealtimePolicy] RE-DERIVES it at every
 * arm site (reconcile / re-arm-on-handshake) instead of caching it, so a reconnect outside the
 * window can never re-arm the flood from a stale precomputed want.
 */
object ContinuousHrvSchedule {
    /** Reused quiet-hours window defaults (22:00 / 07:00). Platform prefs may override. */
    const val DEFAULT_START_MINUTES: Int = 22 * 60
    const val DEFAULT_END_MINUTES: Int = 7 * 60

    /**
     * Wrap-aware membership: is [minuteOfDay] inside `[startMin, endMin)`, where the window may
     * cross midnight? start <= end is the plain daytime interval; start > end wraps midnight.
     */
    fun windowContains(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean =
        if (startMin <= endMin) {
            minuteOfDay >= startMin && minuteOfDay < endMin
        } else {
            minuteOfDay >= startMin || minuteOfDay < endMin
        }

    /**
     * The composed want: should the continuous-capture stream be held open at local wall-clock
     * minute [minuteOfDay]? False when the feature is off; true 24/7 in ALWAYS mode
     * (overnightOnly false); window-gated in OVERNIGHT mode.
     */
    fun streamWanted(
        continuousHrv: Boolean,
        overnightOnly: Boolean,
        minuteOfDay: Int,
        startMin: Int = DEFAULT_START_MINUTES,
        endMin: Int = DEFAULT_END_MINUTES,
    ): Boolean {
        if (!continuousHrv) return false
        if (!overnightOnly) return true
        return windowContains(minuteOfDay, startMin, endMin)
    }
}

/**
 * What [RealtimePolicy] decided must be written to the strap. The transport maps these onto
 * commands: [ToggleRealtimeHr] → TOGGLE_REALTIME_HR(3) with payload [0x01]/[0x00] (puffin frame
 * on 5/MG), [HeavyStream] → SEND_R10_R11_REALTIME(63) with payload [0x01]/[0x00] — WHOOP 4.0
 * only, the transport drops it for 5/MG (which streams over the puffin toggle alone).
 */
sealed interface RealtimeAction {
    data class ToggleRealtimeHr(val on: Boolean) : RealtimeAction
    data class HeavyStream(val on: Boolean) : RealtimeAction
}

/**
 * Flow-3 realtime want/arm reconciler — pure port of the BLEManager realtime intent state
 * (screenWantsRealtime / keepRealtimeForData / standardHRFallback / realtimeArmed,
 * BLEManager.swift:529-584) and `reconcileRealtime()` (:1995).
 *
 * Invariants carried over verbatim:
 *  - TOGGLE_REALTIME_HR is sent ONLY on the want edge ([realtimeArmed] tracks what we last told
 *    the strap, so steady state sends nothing).
 *  - The heavy R10/R11 burst is SCREEN-scoped (Live tab): emitted by [startRealtime] /
 *    [stopRealtime], never by the continuous-capture window, so overnight capture doesn't
 *    permanently compete with the historical offload.
 *  - [standardHRFallback] (#86 marginal-radio): when tripped, reconcile skips ARMING (live HR
 *    rides the standard 0x2A37 profile instead) but never blocks DISARM. An explicit screen
 *    start clears it — if the radio still can't take it, the detector simply trips again.
 *  - Disconnect clears only [realtimeArmed] ("what we last sent"); the intent flags survive a
 *    reconnect so [onHandshakeComplete] can re-arm.
 *  - The continuous want is re-derived from the wall clock at every decision point
 *    ([minuteOfDay] seam), never cached.
 */
class RealtimePolicy(
    /** Local wall-clock minutes since midnight — seam so tests can pin the window gate. */
    private val minuteOfDay: () -> Int,
    /** Window override seam (platform prefs); defaults to 22:00 → 07:00. */
    private val quietWindow: () -> Pair<Int, Int> =
        { ContinuousHrvSchedule.DEFAULT_START_MINUTES to ContinuousHrvSchedule.DEFAULT_END_MINUTES },
) {
    /** The Live screen is open and wants the heavy realtime streams. */
    var screenWantsRealtime: Boolean = false
        private set

    /** Continuous-capture preference intent (window-gated when [overnightOnly]). */
    var keepRealtimeForData: Boolean = false
        private set

    /** #927: gate the continuous want to the overnight window. */
    var overnightOnly: Boolean = false
        private set

    /** #86: marginal radio tripped — skip arming, rely on the standard 0x2A37 profile. */
    var standardHRFallback: Boolean = false
        private set

    /** What we last told the strap (armed = TOGGLE_REALTIME_HR 1). Edge tracking only. */
    var realtimeArmed: Boolean = false
        private set

    /** Window-gated continuous-capture want at the current wall-clock minute. */
    fun continuousCaptureWantsNow(): Boolean {
        val (startMin, endMin) = quietWindow()
        return ContinuousHrvSchedule.streamWanted(
            continuousHrv = keepRealtimeForData,
            overnightOnly = overnightOnly,
            minuteOfDay = minuteOfDay(),
            startMin = startMin,
            endMin = endMin,
        )
    }

    /** The combined want: Live screen OR the (window-gated) continuous-capture preference. */
    fun wantsRealtime(): Boolean = screenWantsRealtime || continuousCaptureWantsNow()

    /**
     * Live screen appeared. Clears a tripped [standardHRFallback] (explicit user intent; the
     * detector re-trips if the radio still can't take it), requests the heavy R10/R11 stream,
     * and arms the toggle on the off→on edge.
     */
    fun startRealtime(): List<RealtimeAction> {
        screenWantsRealtime = true
        standardHRFallback = false
        return listOf(RealtimeAction.HeavyStream(on = true)) + reconcile()
    }

    /** Live screen disappeared. Stops the heavy stream; the toggle disarms only if the
     *  continuous-capture want isn't holding it open. */
    fun stopRealtime(): List<RealtimeAction> {
        screenWantsRealtime = false
        return listOf(RealtimeAction.HeavyStream(on = false)) + reconcile()
    }

    /** Continuous-capture preference changed (both persisted booleans). */
    fun setKeepRealtimeForData(keep: Boolean, overnightOnly: Boolean): List<RealtimeAction> {
        keepRealtimeForData = keep
        this.overnightOnly = overnightOnly
        return reconcile()
    }

    /** #86 marginal-radio detector tripped: disarm and fall back to the 0x2A37 profile.
     *  Forces the disarm edge directly rather than via [reconcile] — the screen/continuous want
     *  may still be true at trip time, which would leave the toggle armed while the fallback is
     *  latched. (In Swift the trip coincides with disconnect, so the drop itself cleared the
     *  armed state; here trip is an explicit event, and a disconnect-time caller pairs it with
     *  [onDisconnect], making the returned disarm a harmless no-op on a dead link.) */
    fun noteMarginalRadioTripped(): List<RealtimeAction> {
        standardHRFallback = true
        if (!realtimeArmed) return emptyList()
        realtimeArmed = false
        return listOf(RealtimeAction.ToggleRealtimeHr(on = false))
    }

    /**
     * Edge-only reconcile (`reconcileRealtime()`): send TOGGLE_REALTIME_HR only when the combined
     * want differs from what we last sent. Arming is additionally gated on !standardHRFallback;
     * disarming never is.
     */
    fun reconcile(): List<RealtimeAction> {
        val want = wantsRealtime()
        if (want == realtimeArmed) return emptyList()
        if (want && standardHRFallback) return emptyList() // ride 0x2A37 instead (#86)
        realtimeArmed = want
        return listOf(RealtimeAction.ToggleRealtimeHr(on = want))
    }

    /**
     * Handshake completed on a (re)connect (BLEManager.swift:3195/3294): the strap booted fresh,
     * so re-derive the want from the wall clock and re-arm. The heavy stream is re-requested only
     * for a live screen intent.
     */
    fun onHandshakeComplete(): List<RealtimeAction> {
        realtimeArmed = false // fresh connection: the strap has no toggle state
        val want = wantsRealtime()
        if (!want || standardHRFallback) return emptyList()
        realtimeArmed = true
        val actions = mutableListOf<RealtimeAction>(RealtimeAction.ToggleRealtimeHr(on = true))
        if (screenWantsRealtime) actions += RealtimeAction.HeavyStream(on = true)
        return actions
    }

    /** Link dropped: clear only "what we last sent" — intent survives the reconnect. */
    fun onDisconnect() {
        realtimeArmed = false
    }
}
