package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests RecoveryScorer.restingHR's artifact hardening (#686): the resting floor is the minimum of
 * 5-min bin means, but a thin (single-artifact) bin or a sub-physiological (dropout) bin must NOT
 * win that minimum. Faithful Kotlin mirror of the #686 cases in RecoveryScorerTests.swift — same
 * scenarios, same expected floors, byte-identical logic.
 */
class RecoveryScorerRestingHrTest {

    private val dev = "test"

    private fun hr(ts: Long, bpm: Int) = HrSample(deviceId = dev, ts = ts, bpm = bpm)

    @Test
    fun rejectsSingleSampleArtifactBin() {
        // A dense, well-populated bin at 55 bpm, then a SECOND 5-min bin holding exactly ONE
        // artifact beat at 30 bpm. The old min-of-bin-means took the lone-sample bin (30) as the
        // floor; with #686 a single-sample bin can't WIN, so the floor is the real 55.
        val start = 1000L
        val samples = ArrayList<HrSample>()
        for (i in 0 until 300) samples.add(hr(start + i, 55)) // bin 0: 300 samples @55
        samples.add(hr(start + 300, 30))                       // bin 1: ONE sample @30
        val r = RecoveryScorer.restingHR(samples, start, start + 600)
        assertEquals("a single-sample artifact bin must not win the resting floor", 55, r)
    }

    @Test
    fun rejectsSubPhysiologicalDropoutBin() {
        // Two FULLY-populated bins: a real 52 bpm bin and a dropout bin whose 300 samples all read
        // an implausible 10 bpm. It clears the sample-count bar but is sub-physiological, so #686
        // bars it from the floor → resting reads the real 52.
        val start = 2000L
        val samples = ArrayList<HrSample>()
        for (i in 0 until 300) samples.add(hr(start + i, 52))         // real bin
        for (i in 0 until 300) samples.add(hr(start + 300 + i, 10))   // dropout bin
        val r = RecoveryScorer.restingHR(samples, start, start + 600)
        assertEquals("a sub-physiological dropout bin must not win the resting floor", 52, r)
    }

    @Test
    fun keepsGenuineLowFloor() {
        // A REAL sustained dip (a full 5-min bin at 45 bpm) is plausible AND well-populated, so it
        // still wins — the hardening must not flatten genuine athletic resting HRs.
        val start = 3000L
        val samples = ArrayList<HrSample>()
        for (i in 0 until 300) samples.add(hr(start + i, 60))
        for (i in 0 until 300) samples.add(hr(start + 300 + i, 45))
        val r = RecoveryScorer.restingHR(samples, start, start + 600)
        assertEquals("a genuine sustained low bin must still win the floor", 45, r)
    }

    @Test
    fun fallsBackWhenNoBinQualifies() {
        // A wholly sparse window: every bin holds a single sample (none clears the count bar).
        // Rather than return null on data present, fall back to the legacy lowest-bin-mean (here 48).
        val start = 4000L
        val samples = listOf(hr(start + 10, 58), hr(start + 320, 48)) // two bins, one sample each
        val r = RecoveryScorer.restingHR(samples, start, start + 600)
        assertEquals("with no qualifying bin, fall back to the lowest bin mean (never null on data)", 48, r)
    }

    @Test
    fun nullWhenNoSamples() {
        assertEquals(null, RecoveryScorer.restingHR(emptyList(), 0L, 1000L))
    }
}
