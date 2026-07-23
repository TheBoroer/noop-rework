package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #386: pins [BackgroundHealth.isAggressiveVendor] (case-insensitive, substring, real
 * Build.MANUFACTURER values) + the canonical vendor set, port of upstream 05c2d867's
 * BackgroundHealthTest. The Context-bearing halves (exempt check, intents) need a device;
 * the classifier is the piece the Settings copy and the Test Centre diagnostic both key on.
 */
class BackgroundHealthTest {

    @Test
    fun realManufacturerStringsClassifyAggressive() {
        // Real-world Build.MANUFACTURER values, mixed case — substring + lowercase must match all.
        for (m in listOf("Xiaomi", "XIAOMI", "OPPO", "vivo", "HUAWEI", "OnePlus", "realme", "Meizu")) {
            assertTrue("$m should classify aggressive", BackgroundHealth.isAggressiveVendor(m))
        }
    }

    @Test
    fun standardVendorsClassifyStandard() {
        for (m in listOf("Google", "samsung", "Sony", "motorola", "Fairphone", "Nothing")) {
            assertFalse("$m should classify standard", BackgroundHealth.isAggressiveVendor(m))
        }
    }

    @Test
    fun substringVariantsStillMatch() {
        // ROMs report decorated names; the check is contains(), not equals().
        assertTrue(BackgroundHealth.isAggressiveVendor("Xiaomi/POCO"))
        assertTrue(BackgroundHealth.isAggressiveVendor("HUAWEI Technologies Co., Ltd."))
    }

    @Test
    fun canonicalSetIsTheDontKillMyAppSeven() {
        // The diagnostic and the Settings fix read this ONE list — pin it so a drive-by edit that
        // drops a vendor (or typos one) breaks loudly.
        assertEquals(
            listOf("xiaomi", "oppo", "vivo", "huawei", "oneplus", "realme", "meizu"),
            BackgroundHealth.AGGRESSIVE_VENDORS,
        )
    }

    @Test
    fun diagnosticHeuristicDelegatesToTheSameSet() {
        // AndroidDiagnostics.oemKillHeuristic must agree with the classifier for every vendor in the
        // canonical set — the whole point of centralising was that the two can never drift.
        for (v in BackgroundHealth.AGGRESSIVE_VENDORS) {
            assertTrue(
                com.noop.testcentre.AndroidDiagnostics.oemKillHeuristic(v).startsWith("aggressive vendor"),
            )
        }
        assertEquals("standard", com.noop.testcentre.AndroidDiagnostics.oemKillHeuristic("Google"))
    }
}
