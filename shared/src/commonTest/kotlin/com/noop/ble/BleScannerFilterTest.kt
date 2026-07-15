package com.noop.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2c-2 Task 9: pure-logic tests for the WHOOP advertisement filter rules ported from
 * `BLEManager.swift`'s discovery path. No radio, no Kable — predicates only.
 */
class BleScannerFilterTest {

    // -- displayName: advertised ?? peripheral ?? "unknown" (BLEManager.swift:2657) --

    @Test
    fun displayNamePrefersAdvertisedLocalName() {
        assertEquals("WHOOP 4A012345", WhoopAdvertisementFilter.displayName("WHOOP 4A012345", "ignored"))
    }

    @Test
    fun displayNameFallsBackToPeripheralName() {
        assertEquals("WHOOP", WhoopAdvertisementFilter.displayName(null, "WHOOP"))
    }

    @Test
    fun displayNameFallsBackToUnknown() {
        assertEquals("unknown", WhoopAdvertisementFilter.displayName(null, null))
    }

    // -- service UUID match (BLEManager.swift:446-447 scanService selection) --

    @Test
    fun whoop4ServiceMatchesCaseInsensitively() {
        assertTrue(
            WhoopAdvertisementFilter.matchesService(
                listOf("61080001-8D6D-82B8-614A-1C8CB0F8DCC6"),
                WhoopModel.WHOOP4,
            )
        )
    }

    @Test
    fun whoop5ServiceDoesNotMatchWhoop4Model() {
        assertFalse(
            WhoopAdvertisementFilter.matchesService(
                listOf("fd4b0001-cce1-4033-93ce-002d5875f58a"),
                WhoopModel.WHOOP4,
            )
        )
    }

    @Test
    fun modelForResolvesEachFamily() {
        assertEquals(
            WhoopModel.WHOOP4,
            WhoopAdvertisementFilter.modelFor(listOf("61080001-8d6d-82b8-614a-1c8cb0f8dcc6")),
        )
        assertEquals(
            WhoopModel.WHOOP5,
            WhoopAdvertisementFilter.modelFor(listOf("fd4b0001-cce1-4033-93ce-002d5875f58a")),
        )
        assertNull(WhoopAdvertisementFilter.modelFor(listOf("0000180d-0000-1000-8000-00805f9b34fb")))
    }

    // -- preferred-peripheral pin (BLEManager.swift didDiscover guard) --

    @Test
    fun noPinPassesEverything() {
        assertTrue(WhoopAdvertisementFilter.passesPreferredFilter("ANY-ID", null))
    }

    @Test
    fun pinPassesOnlyThePinnedStrap() {
        val pinned = "c52abd4f-149f-407a-89f6-2daf3281da01"
        assertTrue(WhoopAdvertisementFilter.passesPreferredFilter(pinned, pinned))
        assertTrue(WhoopAdvertisementFilter.passesPreferredFilter(pinned.uppercase(), pinned))
        assertFalse(WhoopAdvertisementFilter.passesPreferredFilter("other-strap", pinned))
    }
}
