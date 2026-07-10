package com.noop.util

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class FixedFormatJvmParityTest {
    @Test
    fun matchesStringFormatAcrossSweep() {
        val values = buildList {
            add(0.0); add(-0.0); add(0.05); add(-0.05); add(1.25); add(2.675)
            add(99.994); add(99.995); add(1234.5678); add(-1234.5678)
            var v = -10.0
            while (v <= 10.0) { add(v); v += 0.037 }
        }
        for (d in 0..3) {
            for (v in values) {
                assertEquals(
                    String.format(Locale.US, "%.${d}f", v),
                    v.toFixed(d),
                    "value=$v decimals=$d",
                )
            }
        }
    }
}
