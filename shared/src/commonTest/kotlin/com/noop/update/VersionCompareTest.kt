package com.noop.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the version comparison behind "Check for updates" (headline being the string-compare trap
 *  (`1.40` > `1.39`, `1.10` > `1.9`), plus the demo suffix and a leading "v"). Mirrors
 *  Packages/WhoopProtocol/Tests/WhoopProtocolTests/VersionCheckTests.swift. */
class VersionCompareTest {

    @Test
    fun testNewer() {
        assertTrue(VersionCompare.isNewer("1.40", "1.39"))   // the trap: "1.40" < "1.39" as strings
        assertTrue(VersionCompare.isNewer("1.10", "1.9"))    // and "1.10" < "1.9" as strings
        assertTrue(VersionCompare.isNewer("2.0", "1.39"))
        assertTrue(VersionCompare.isNewer("1.39.1", "1.39"))
        assertTrue(VersionCompare.isNewer("v1.40", "1.39"))
    }

    @Test
    fun testNotNewer() {
        assertFalse(VersionCompare.isNewer("1.39", "1.39"))      // equal
        assertFalse(VersionCompare.isNewer("1.38", "1.39"))
        assertFalse(VersionCompare.isNewer("1.9", "1.10"))
        assertFalse(VersionCompare.isNewer("1.39-demo", "1.39"))
        assertFalse(VersionCompare.isNewer("garbage", "1.39"))   // unparseable, no false alarm
    }
}
