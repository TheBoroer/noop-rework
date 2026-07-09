package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests HrZones.timeInZone — time-in-zone accumulation from an HR stream.
 * Pure-function tests; no DB. Kotlin twin of the StrandAnalytics HRZonesTests.
 */
class HrZonesTest {

    @Test
    fun timeInZone_capsHugePositiveGap() {
        // Regression (#366): three 1 Hz zone-1 samples (median gap 1 s), then one sample an HOUR
        // later. The 3600 s gap before the last sample must be capped at the median (1 s) — as the
        // comment promises — not credited in full, so one wear gap / sparse stretch can't blow up a
        // bucket.
        val zs = HrZones.zones(maxHR = 200.0)
        val hr = listOf(
            HrSample(deviceId = "t", ts = 0L, bpm = 110),
            HrSample(deviceId = "t", ts = 1L, bpm = 110),
            HrSample(deviceId = "t", ts = 2L, bpm = 110),
            HrSample(deviceId = "t", ts = 3602L, bpm = 110),
        )
        val tiz = HrZones.timeInZone(hr, zs)
        assertTrue(
            "a huge inter-sample gap must be capped at the median, not credited in full",
            tiz.total < 10.0,
        )
        assertEquals(tiz.total, tiz.secondsInZone(1), 1e-9) // all of it is zone 1
    }
}
