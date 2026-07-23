package com.noop.ble

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #533 (upstream 73972540): experimental LE 2M PHY preference for the offload burst, behind the
 * "Prefer 2M Bluetooth" toggle (default OFF).
 *
 * Pins the pure decision twins [AndroidWhoopBleClient.preferredPhyMaskOnBegin] /
 * [AndroidWhoopBleClient.preferredPhyMaskOnRelease] and the [AndroidWhoopBleClient.phyLabel]
 * diagnostics mapping — the instance helpers route every issued op through them (same pattern as
 * [ConnectionPriorityTest]; the live `setPreferredPhy` half needs a strap).
 *
 * The invariants that matter:
 *  1. Default config issues ZERO BLE ops — today's behaviour byte-for-byte.
 *  2. The requested mask ALWAYS includes 1M, never 2M alone: it is a preference, and keeping 1M
 *     lets the controller fall back rather than cling to a 2M link gone marginal.
 *  3. Toggle-off releases back to a 1M-only preference, and ONLY if this link actually requested
 *     2M — a PHY persists once negotiated, so the off edge must issue the release NOW, but the
 *     default launch path must stay silent.
 */
class PreferredPhyTest {

    // --- Begin: the 2M preference is opt-in --------------------------------------------------------

    @Test
    fun defaultOff_beginIssuesNoOp() {
        assertNull(AndroidWhoopBleClient.preferredPhyMaskOnBegin(optedIn = false))
    }

    @Test
    fun optedIn_beginPrefers2M() {
        assertEquals(
            BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK,
            AndroidWhoopBleClient.preferredPhyMaskOnBegin(optedIn = true),
        )
    }

    @Test
    fun beginMask_alwaysKeeps1MFallback() {
        // Never 2M alone: the controller must be able to fall back on a marginal link.
        val mask = AndroidWhoopBleClient.preferredPhyMaskOnBegin(optedIn = true)!!
        assertTrue(mask and BluetoothDevice.PHY_LE_1M_MASK != 0)
    }

    // --- Release: only ever undoes a real request --------------------------------------------------

    @Test
    fun notRequested_releaseIssuesNoOp() {
        // Launch path / toggle-off with nothing negotiated: zero BLE ops.
        assertNull(AndroidWhoopBleClient.preferredPhyMaskOnRelease(requested = false))
    }

    @Test
    fun requested_releaseHandsBackTo1M() {
        assertEquals(
            BluetoothDevice.PHY_LE_1M_MASK,
            AndroidWhoopBleClient.preferredPhyMaskOnRelease(requested = true),
        )
    }

    // --- onPhyUpdate diagnostics: the field-report line must be readable ---------------------------

    @Test
    fun phyLabels_coverTheSpecValues() {
        assertEquals("1M", AndroidWhoopBleClient.phyLabel(BluetoothDevice.PHY_LE_1M))
        assertEquals("2M", AndroidWhoopBleClient.phyLabel(BluetoothDevice.PHY_LE_2M))
        assertEquals("coded", AndroidWhoopBleClient.phyLabel(BluetoothDevice.PHY_LE_CODED))
        assertEquals("unknown(7)", AndroidWhoopBleClient.phyLabel(7))
    }
}
