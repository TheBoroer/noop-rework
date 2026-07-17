package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the BLE scan-family fallback rotation (PR#195): a service-filtered scan that finds nothing rotates
 * to the OTHER WHOOP family in case the persisted preference went stale after an update/restore. Kotlin
 * twin of macOS WhoopModelFallbackTests.
 */
class WhoopModelFallbackTest {

    @Test
    fun fallbackRotatesBetweenFamilies() {
        assertEquals(AndroidWhoopModel.WHOOP5_MG, AndroidWhoopModel.WHOOP4.fallbackScanModel)
        assertEquals(AndroidWhoopModel.WHOOP4, AndroidWhoopModel.WHOOP5_MG.fallbackScanModel)
    }

    @Test
    fun fallbackIsInvolution() {
        assertEquals(AndroidWhoopModel.WHOOP4, AndroidWhoopModel.WHOOP4.fallbackScanModel.fallbackScanModel)
        assertEquals(AndroidWhoopModel.WHOOP5_MG, AndroidWhoopModel.WHOOP5_MG.fallbackScanModel.fallbackScanModel)
    }
}
