package com.noop.util

import java.util.Locale
import kotlin.math.pow
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

    /**
     * Dense tie-region sweep: this is the exact failure class a scale-then-roundToLong
     * implementation gets wrong (double-rounding a binary double that sits just below/above a
     * decimal .5 boundary, e.g. i/10.0 + 0.05 -> 3.3499999999999996 at d=1, which the JVM formats
     * as "3.3" via its shortest round-trip decimal string + HALF_UP, not "3.4"). This sweep FAILS
     * against the old scale-then-round implementation and PASSES once toFixed rounds the decimal
     * digit string (Double.toString()) directly instead of a scaled binary value.
     */
    @Test
    fun matchesStringFormatAcrossDenseTieRegion() {
        for (d in 0..3) {
            val scale = 10.0.pow(d)
            val half = 5.0 / 10.0.pow(d + 1)
            for (i in -20000..20000) {
                val v = i / scale + half
                assertEquals(
                    String.format(Locale.US, "%.${d}f", v),
                    v.toFixed(d),
                    "value=$v decimals=$d (tie-region, i=$i)",
                )
            }
        }

        // The reviewer's exact counterexample: JVM formats this as "3.3" at d=1.
        val counterexample = 3.3499999999999996
        assertEquals(
            String.format(Locale.US, "%.1f", counterexample),
            counterexample.toFixed(1),
            "value=$counterexample decimals=1 (reviewer counterexample)",
        )

        // Classic tie-trap values and signed zero, at each precision.
        for (d in 0..3) {
            for (v in listOf(9.95, 99.95, 0.05, -0.05)) {
                assertEquals(
                    String.format(Locale.US, "%.${d}f", v),
                    v.toFixed(d),
                    "value=$v decimals=$d (tie-trap)",
                )
            }
            for (v in listOf(0.0, -0.0)) {
                assertEquals(
                    String.format(Locale.US, "%.${d}f", v),
                    v.toFixed(d),
                    "value=$v decimals=$d (signed zero)",
                )
            }
        }

        // Large values that force Double.toString() into exponent form ("1.0E7", "1.23456789E10").
        for (v in listOf(1.0e7 + 0.05, 1.23456789e10)) {
            for (d in 0..3) {
                assertEquals(
                    String.format(Locale.US, "%.${d}f", v),
                    v.toFixed(d),
                    "value=$v decimals=$d (exponent form)",
                )
            }
        }
    }
}
