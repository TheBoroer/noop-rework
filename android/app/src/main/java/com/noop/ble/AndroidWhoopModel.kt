package com.noop.ble

import java.util.UUID

/**
 * Which strap the user is pairing. They pick this before scanning so we look for
 * exactly one device family instead of guessing — a WHOOP 4.0 scan no longer
 * waits forever on a WHOOP 5/MG wrist, and vice versa.
 *
 * This is the user-facing choice; it is deliberately separate from the
 * protocol-layer DeviceFamily (which carries CRC/characteristic detail).
 */
enum class AndroidWhoopModel(val displayName: String, val service: UUID) {
    WHOOP4("WHOOP 4.0", AndroidWhoopBleClient.WHOOP4_SERVICE),
    WHOOP5_MG("WHOOP 5.0 / MG", AndroidWhoopBleClient.WHOOP5_SERVICE);

    /**
     * The OTHER WHOOP family to try when a service-filtered scan for this model finds nothing. A
     * stale/missing persisted preference (after an update or restore) can point the scan at the wrong
     * service so it runs forever with the strap right there; rotating to the other family — and
     * persisting whichever one actually advertises — recovers reconnect automatically. Mirrors macOS
     * `AndroidWhoopModel.fallbackScanModel`. (PR#195)
     */
    val fallbackScanModel: AndroidWhoopModel
        get() = when (this) {
            WHOOP4 -> WHOOP5_MG
            WHOOP5_MG -> WHOOP4
        }
}
