package com.noop.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpLruTest {
    @Test
    fun evictsLeastRecentlyAccessedNotLeastRecentlyInserted() {
        val lru = MpLru<String, Int>(2)
        lru.put("a", 1)
        lru.put("b", 2)
        lru.get("a")            // a is now most recent
        lru.put("c", 3)         // evicts b, not a
        assertEquals(1, lru.get("a"))
        assertNull(lru.get("b"))
        assertEquals(3, lru.get("c"))
        assertEquals(2, lru.size)
    }

    @Test
    fun putExistingKeyRefreshesRecency() {
        val lru = MpLru<String, Int>(2)
        lru.put("a", 1)
        lru.put("b", 2)
        lru.put("a", 10)        // refresh a
        lru.put("c", 3)         // evicts b
        assertEquals(10, lru.get("a"))
        assertNull(lru.get("b"))
    }

    @Test
    fun capacityOneAlwaysKeepsNewest() {
        val lru = MpLru<String, Int>(1)
        lru.put("a", 1)
        lru.put("b", 2)
        assertNull(lru.get("a"))
        assertEquals(2, lru.get("b"))
    }
}
