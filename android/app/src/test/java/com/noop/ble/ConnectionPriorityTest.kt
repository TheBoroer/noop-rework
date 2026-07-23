package com.noop.ble

import android.bluetooth.BluetoothGatt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #533 (upstream 5b1b31d5): offload-burst GATT connection-priority escalation, behind the
 * "Faster history sync" experimental toggle (default OFF).
 *
 * Pins the pure decision twins [AndroidWhoopBleClient.offloadPriorityOnBegin] /
 * [AndroidWhoopBleClient.offloadPriorityOnRelease] — the instance helpers route every issued op
 * through them, so these ARE the shipped policy (same pattern as [GattCrashSafetyTest] pinning
 * `shouldTeardownOnGattThrow`; the live GATT halves need a strap and can't run under the stub
 * android.jar harness).
 *
 * The invariants that matter:
 *  1. Default config issues ZERO BLE ops — today's behaviour byte-for-byte.
 *  2. Opt-in escalates to HIGH for the bounded offload burst only (never live HR).
 *  3. Release hands the link back to BALANCED, and ONLY if this session escalated — the upstream
 *     re-review catch was a toggle-off leaving a link pinned at HIGH for hours under
 *     "Keep connected in the background".
 */
class ConnectionPriorityTest {

    // --- Begin: escalation is opt-in ---------------------------------------------------------------

    @Test
    fun defaultOff_beginIssuesNoOp() {
        // The whole ballgame: a user who never touched Settings must see zero new BLE traffic.
        assertNull(AndroidWhoopBleClient.offloadPriorityOnBegin(optedIn = false))
    }

    @Test
    fun optedIn_beginEscalatesToHigh() {
        assertEquals(
            BluetoothGatt.CONNECTION_PRIORITY_HIGH,
            AndroidWhoopBleClient.offloadPriorityOnBegin(optedIn = true),
        )
    }

    // --- Release: only ever undoes a real escalation -----------------------------------------------

    @Test
    fun notEscalated_releaseIssuesNoOp() {
        // Offload ends on a default-config link → no spurious BALANCED request. Also the launch
        // path (toggle already off, nothing pinned): still zero BLE ops.
        assertNull(AndroidWhoopBleClient.offloadPriorityOnRelease(escalated = false))
    }

    @Test
    fun escalated_releaseHandsBackBalanced() {
        // Burst over (HISTORY_COMPLETE / idle timeout) or Settings on→off edge: the link goes back
        // to the stack default NOW, not at the next reconnect.
        assertEquals(
            BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
            AndroidWhoopBleClient.offloadPriorityOnRelease(escalated = true),
        )
    }

    // --- The two halves compose: a full opt-in burst is HIGH then BALANCED, nothing else -----------

    @Test
    fun optedInBurst_isExactlyHighThenBalanced() {
        val ops = listOfNotNull(
            AndroidWhoopBleClient.offloadPriorityOnBegin(optedIn = true),
            AndroidWhoopBleClient.offloadPriorityOnRelease(escalated = true),
        )
        assertEquals(
            listOf(
                BluetoothGatt.CONNECTION_PRIORITY_HIGH,
                BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
            ),
            ops,
        )
    }

    @Test
    fun defaultBurst_isZeroOps() {
        val ops = listOfNotNull(
            AndroidWhoopBleClient.offloadPriorityOnBegin(optedIn = false),
            AndroidWhoopBleClient.offloadPriorityOnRelease(escalated = false),
        )
        assertEquals(emptyList<Int>(), ops)
    }
}
