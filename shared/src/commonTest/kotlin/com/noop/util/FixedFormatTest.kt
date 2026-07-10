package com.noop.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FixedFormatTest {
    @Test
    fun basics() {
        assertEquals("1.3", 1.25.toFixed(1))
        assertEquals("0.1", 0.05.toFixed(1))
        assertEquals("-0.1", (-0.05).toFixed(1))
        assertEquals("13", 12.6.toFixed(0))
        assertEquals("5.00", 5.0.toFixed(2))
        assertEquals("99.99", 99.994.toFixed(2))
    }
}
