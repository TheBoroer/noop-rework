package com.noop.ble

import com.noop.protocol.DeviceFamily
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 2c-2 Task 10: unit tests for the pure reconnect-policy value types and the SET_CLOCK
 * payload builders ported from `Strand/BLE/BLEManager.swift` (detectors: lines 24-71, 87-137,
 * 150-205; clock payloads: 3322-3356). Scenarios mirror the Swift behavior comments so a parity
 * regression fails loudly here rather than on hardware.
 */
class BleSessionPolicyTest {

    // ---- MarginalRadioDetector (arm streak) ----------------------------------------------------

    @Test
    fun marginalRadio_tripsOnSecondConsecutiveQuickArmTimeout() {
        val d = MarginalRadioDetector()
        assertFalse(d.connectionEnded(wasArmed = true, secondsSinceArm = 5.0, timedOut = true))
        assertFalse(d.tripped)
        // Second in a row freshly trips — and reports true exactly once.
        assertTrue(d.connectionEnded(wasArmed = true, secondsSinceArm = 3.0, timedOut = true))
        assertTrue(d.tripped)
        // Third quick timeout keeps tripped but is not "freshly crossed".
        assertFalse(d.connectionEnded(wasArmed = true, secondsSinceArm = 2.0, timedOut = true))
        assertTrue(d.tripped)
    }

    @Test
    fun marginalRadio_lateOrUnrelatedDropsBreakTheStreak() {
        val d = MarginalRadioDetector()
        assertFalse(d.connectionEnded(wasArmed = true, secondsSinceArm = 5.0, timedOut = true))
        assertEquals(1, d.consecutiveArmTimeouts)
        // A drop outside the 20s window is unrelated to the arm burst and resets the streak.
        assertFalse(d.connectionEnded(wasArmed = true, secondsSinceArm = 21.0, timedOut = true))
        assertEquals(0, d.consecutiveArmTimeouts)
        // Never armed → resets.
        assertFalse(d.connectionEnded(wasArmed = true, secondsSinceArm = 5.0, timedOut = true))
        assertFalse(d.connectionEnded(wasArmed = false, secondsSinceArm = null, timedOut = true))
        assertEquals(0, d.consecutiveArmTimeouts)
        // Non-timeout close (intentional disconnect, bond reset …) → resets.
        assertFalse(d.connectionEnded(wasArmed = true, secondsSinceArm = 5.0, timedOut = true))
        assertFalse(d.connectionEnded(wasArmed = true, secondsSinceArm = 5.0, timedOut = false))
        assertEquals(0, d.consecutiveArmTimeouts)
    }

    @Test
    fun marginalRadio_windowBoundaryIsInclusive() {
        val d = MarginalRadioDetector()
        assertFalse(d.connectionEnded(wasArmed = true, secondsSinceArm = 20.0, timedOut = true))
        assertEquals(1, d.consecutiveArmTimeouts) // <= window counts (Swift `$0 <= quickTimeoutWindow`)
    }

    @Test
    fun marginalRadio_resetClearsTrippedState() {
        val d = MarginalRadioDetector()
        d.connectionEnded(wasArmed = true, secondsSinceArm = 1.0, timedOut = true)
        d.connectionEnded(wasArmed = true, secondsSinceArm = 1.0, timedOut = true)
        assertTrue(d.tripped)
        d.reset()
        assertFalse(d.tripped)
        assertEquals(0, d.consecutiveArmTimeouts)
    }

    // ---- PostBondTimeoutLoopDetector (#617 bond loop) ------------------------------------------

    @Test
    fun postBondLoop_tripsOnSecondConsecutiveQuickBondTimeout() {
        val d = PostBondTimeoutLoopDetector()
        assertFalse(d.connectionEnded(wasBonded = true, secondsSinceBond = 1.0, timedOut = true))
        assertTrue(d.connectionEnded(wasBonded = true, secondsSinceBond = 1.5, timedOut = true))
        assertTrue(d.tripped)
        // Stays tripped, never re-reports "fresh".
        assertFalse(d.connectionEnded(wasBonded = true, secondsSinceBond = 0.5, timedOut = true))
    }

    @Test
    fun postBondLoop_only8SecondWindowCounts() {
        val d = PostBondTimeoutLoopDetector()
        assertFalse(d.connectionEnded(wasBonded = true, secondsSinceBond = 1.0, timedOut = true))
        // 9s after the bond is a healthy session that later flapped — breaks the streak.
        assertFalse(d.connectionEnded(wasBonded = true, secondsSinceBond = 9.0, timedOut = true))
        assertEquals(0, d.consecutiveBondTimeouts)
        // Never bonded → breaks the streak too.
        assertFalse(d.connectionEnded(wasBonded = true, secondsSinceBond = 1.0, timedOut = true))
        assertFalse(d.connectionEnded(wasBonded = false, secondsSinceBond = null, timedOut = true))
        assertEquals(0, d.consecutiveBondTimeouts)
    }

    // ---- BondRefusalGiveUp (#747 / #750) --------------------------------------------------------

    @Test
    fun bondGiveUp_freshlyCrossesAtFiveAndLatches() {
        val g = BondRefusalGiveUp()
        repeat(4) { assertFalse(g.recordRefusal()) }
        assertFalse(g.gaveUp)
        assertTrue(g.recordRefusal()) // 5th refusal freshly crosses
        assertTrue(g.gaveUp)
        assertFalse(g.recordRefusal()) // 6th: still given up, not "fresh"
        assertEquals(6, g.refusals)
        g.reset()
        assertFalse(g.gaveUp)
        assertEquals(0, g.refusals)
    }

    @Test
    fun bondGiveUp_epitaphAndHintCarryNoEmDashAndNoPii() {
        val opaque = BondRefusalGiveUp.opaqueId("8A6B1F0C-1234-5678-9ABC-DEF012345678")
        assertEquals("8a6b1f0c", opaque) // first 8 hex chars, lowercase, dashes stripped
        val epitaph = BondRefusalGiveUp.epitaphLine(refusals = 5, opaqueId = opaque)
        assertTrue(epitaph.contains("[8a6b1f0c]"))
        assertTrue(epitaph.contains("refused the encrypted bond 5x in a row"))
        assertFalse(epitaph.contains("—")) // project rule: no em-dash in user-facing strings
        assertFalse(BondRefusalGiveUp.pausedHint().contains("—"))
    }

    @Test
    fun bondGiveUp_pausedAtStampsOnFreshGiveUpAndRestampMovesItForward() {
        var clock = 1_000_000L
        val g = BondRefusalGiveUp(nowUnixSeconds = { clock })
        repeat(4) { g.recordRefusal() }
        assertEquals(null, g.pausedAtEpochSeconds) // not given up yet: no stamp
        assertTrue(g.recordRefusal()) // 5th freshly crosses
        assertEquals(1_000_000L, g.pausedAtEpochSeconds)

        // Advance past the salvage floor: the gate would now fire for a foreground probe.
        clock += BOND_LOOP_SALVAGE_FLOOR_SECONDS + 60
        val secondsSince = clock - g.pausedAtEpochSeconds!!
        assertTrue(
            shouldSalvageProbe(
                pausedForBondLoop = g.gaveUp, connected = false,
                intentionalDisconnect = false, secondsSincePauseTripped = secondsSince,
            ),
        )

        // Re-stamp BEFORE the connect attempt (mirrors the shim: pausedAt = now, then connect).
        g.restampPause()
        assertEquals(clock, g.pausedAtEpochSeconds)

        // Immediately after the re-stamp, back-to-back foregrounds cannot chain another probe:
        // the gate is blocked again until another full floor elapses.
        val secondsSinceRestamp = clock - g.pausedAtEpochSeconds!!
        assertFalse(
            shouldSalvageProbe(
                pausedForBondLoop = g.gaveUp, connected = false,
                intentionalDisconnect = false, secondsSincePauseTripped = secondsSinceRestamp,
            ),
        )
    }

    @Test
    fun bondGiveUp_restampIsNoOpUnlessGivenUp() {
        val g = BondRefusalGiveUp(nowUnixSeconds = { 500L })
        g.restampPause()
        assertEquals(null, g.pausedAtEpochSeconds)
    }

    @Test
    fun bondGiveUp_resetClearsPausedAt() {
        val g = BondRefusalGiveUp(nowUnixSeconds = { 42L })
        repeat(5) { g.recordRefusal() }
        assertEquals(42L, g.pausedAtEpochSeconds)
        g.reset()
        assertEquals(null, g.pausedAtEpochSeconds)
    }

    // ---- ReconnectPolicy facade ----------------------------------------------------------------

    @Test
    fun reconnectPolicy_pausesViaGiveUpAndClearsOnGenuineBond() {
        val p = ReconnectPolicy()
        repeat(5) { p.bondGiveUp.recordRefusal() }
        assertTrue(p.autoReconnectPaused)
        p.noteGenuineBond()
        assertFalse(p.autoReconnectPaused)
    }

    @Test
    fun reconnectPolicy_connectionEndedFeedsBothDetectors() {
        val p = ReconnectPolicy()
        // Prime both detectors to one-away-from-trip.
        p.connectionEnded(
            wasArmed = true, secondsSinceArm = 1.0,
            wasBonded = true, secondsSinceBond = 1.0, timedOut = true,
        )
        val events = p.connectionEnded(
            wasArmed = true, secondsSinceArm = 1.0,
            wasBonded = true, secondsSinceBond = 1.0, timedOut = true,
        )
        assertTrue(events.marginalRadioTripped)
        assertTrue(events.postBondLoopTripped)
        p.userReset()
        assertFalse(p.marginalRadio.tripped)
        assertFalse(p.postBondLoop.tripped)
    }

    // ---- shouldSalvageProbe (#78 hole-4) --------------------------------------------------------

    private val floor = BOND_LOOP_SALVAGE_FLOOR_SECONDS

    @Test
    fun salvageProbe_firesAtExactlyTheFloorAndAbove() {
        assertTrue(
            shouldSalvageProbe(
                pausedForBondLoop = true, connected = false,
                intentionalDisconnect = false, secondsSincePauseTripped = floor,
            ),
        )
        assertTrue(
            shouldSalvageProbe(
                pausedForBondLoop = true, connected = false,
                intentionalDisconnect = false, secondsSincePauseTripped = floor + 3600,
            ),
        )
    }

    @Test
    fun salvageProbe_blockedBelowTheFloor() {
        assertFalse(
            shouldSalvageProbe(
                pausedForBondLoop = true, connected = false,
                intentionalDisconnect = false, secondsSincePauseTripped = floor - 1,
            ),
        )
        assertFalse(
            shouldSalvageProbe(
                pausedForBondLoop = true, connected = false,
                intentionalDisconnect = false, secondsSincePauseTripped = 0L,
            ),
        )
    }

    @Test
    fun salvageProbe_blockedWhenNoTripTimestamp() {
        assertFalse(
            shouldSalvageProbe(
                pausedForBondLoop = true, connected = false,
                intentionalDisconnect = false, secondsSincePauseTripped = null,
            ),
        )
    }

    @Test
    fun salvageProbe_blockedWhenNotPaused() {
        assertFalse(
            shouldSalvageProbe(
                pausedForBondLoop = false, connected = false,
                intentionalDisconnect = false, secondsSincePauseTripped = floor,
            ),
        )
    }

    @Test
    fun salvageProbe_blockedWhenConnected() {
        assertFalse(
            shouldSalvageProbe(
                pausedForBondLoop = true, connected = true,
                intentionalDisconnect = false, secondsSincePauseTripped = floor,
            ),
        )
    }

    @Test
    fun salvageProbe_blockedWhenIntentionalDisconnect() {
        assertFalse(
            shouldSalvageProbe(
                pausedForBondLoop = true, connected = false,
                intentionalDisconnect = true, secondsSincePauseTripped = floor,
            ),
        )
    }

    // ---- isInsufficientAuthError (#78 hole-1) ---------------------------------------------------

    @Test
    fun isInsufficientAuthError_classifiesByAttCodeEvenWhenTextIsLocalized() {
        // German-device regression parity: the code (15 / 5) must classify regardless of what the
        // (possibly localized) message text says.
        val encryption = Exception(
            "Error Domain=CBATTErrorDomain Code=15 \"Die Verschluesselung ist unzureichend.\"",
        )
        assertTrue(isInsufficientAuthError(encryption))
        val auth = Exception(
            "Error Domain=CBATTErrorDomain Code=5 \"Authentifizierung fehlgeschlagen.\"",
        )
        assertTrue(isInsufficientAuthError(auth))
    }

    @Test
    fun isInsufficientAuthError_englishStringFallbackIsAdditive() {
        // No CBATTErrorDomain/code in the message at all: only the English keyword fallback
        // classifies these, and it must still fire.
        assertTrue(isInsufficientAuthError(Exception("Encryption is insufficient.")))
        assertTrue(isInsufficientAuthError(Exception("Authentication is insufficient.")))
    }

    @Test
    fun isInsufficientAuthError_walksCauseChain() {
        val root = Exception("Error Domain=CBATTErrorDomain Code=15 \"Encryption is insufficient.\"")
        val wrapped = RuntimeException("write failed", root)
        assertTrue(isInsufficientAuthError(wrapped))
    }

    @Test
    fun isInsufficientAuthError_unrelatedErrorsDoNotClassify() {
        assertFalse(isInsufficientAuthError(Exception("Connection timeout")))
        val otherCode = Exception("Error Domain=CBATTErrorDomain Code=6 \"Request is not supported.\"")
        assertFalse(isInsufficientAuthError(otherCode))
    }

    // ---- ClockSync (#120) ----------------------------------------------------------------------

    @Test
    fun clockSync_eightBytePayloadIsU32LittleEndianPlusFourZeros() {
        // now = 0x01020304 → LE [04 03 02 01] + [00 00 00 00]
        assertContentEquals(
            byteArrayOf(0x04, 0x03, 0x02, 0x01, 0, 0, 0, 0),
            ClockSync.setClockPayload(0x01020304L),
        )
    }

    @Test
    fun clockSync_legacyPayloadIsSameSecondsPlusFiveZeros() {
        assertContentEquals(
            byteArrayOf(0x04, 0x03, 0x02, 0x01, 0, 0, 0, 0, 0),
            ClockSync.setClockPayloadLegacy(0x01020304L),
        )
    }

    @Test
    fun clockSync_realUnixSecondsEncodeLittleEndian() {
        // 2026-07-13 ~ 1784332800 = 0x6A5AC200 → LE [00 C2 5A 6A]
        val p = ClockSync.setClockPayload(1784332800L)
        assertContentEquals(byteArrayOf(0x00, 0xC2.toByte(), 0x5A, 0x6A, 0, 0, 0, 0), p)
    }

    @Test
    fun clockSync_familyGatingMatchesSendSetClockBothForms() {
        // WHOOP4: both forms, 8-byte first (sendSetClockBothForms order).
        val w4 = ClockSync.setClockPayloads(DeviceFamily.WHOOP4, 1L)
        assertEquals(2, w4.size)
        assertEquals(8, w4[0].size)
        assertEquals(9, w4[1].size)
        // WHOOP5: single hardware-validated 8-byte form only.
        val w5 = ClockSync.setClockPayloads(DeviceFamily.WHOOP5, 1L)
        assertEquals(1, w5.size)
        assertEquals(8, w5[0].size)
    }

    @Test
    fun clockSync_getClockFormsPerFamily() {
        // WHOOP4 sends the empty form then [0x00] (#120 — fw 41.17.x only answers [0x00]).
        val w4 = ClockSync.getClockPayloads(DeviceFamily.WHOOP4)
        assertEquals(2, w4.size)
        assertEquals(0, w4[0].size)
        assertContentEquals(byteArrayOf(0x00), w4[1])
        // WHOOP5 sends only the empty form.
        val w5 = ClockSync.getClockPayloads(DeviceFamily.WHOOP5)
        assertEquals(1, w5.size)
        assertEquals(0, w5[0].size)
    }
}
