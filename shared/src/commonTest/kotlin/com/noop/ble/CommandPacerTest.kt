package com.noop.ble

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * CommandPacer is the fix for the T15e/#75 hardware finding (strap drops mid-burst commands):
 * bursts must be spread to the minimum gap, while naturally spaced sends pay nothing.
 * Virtual time: `runTest` drives delay(); `TestTimeSource` simulates real elapsed time between
 * sends, so each case controls both clocks independently.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // TestScope.currentTime
class CommandPacerTest {

    @Test
    fun firstSendIsNotDelayed() = runTest {
        val pacer = CommandPacer(gap = 200.milliseconds, timeSource = TestTimeSource())
        val before = currentTime
        pacer.paced {}
        assertEquals(before, currentTime, "first command must go out immediately")
    }

    @Test
    fun burstSendsArePacedToTheFullGap() = runTest {
        val ts = TestTimeSource()
        val pacer = CommandPacer(gap = 200.milliseconds, timeSource = ts)
        pacer.paced {}
        val before = currentTime
        pacer.paced {} // zero elapsed on ts since the previous send → full-gap delay
        assertEquals(before + 200, currentTime, "back-to-back send must wait the full gap")
    }

    @Test
    fun naturallySpacedSendPaysNothing() = runTest {
        val ts = TestTimeSource()
        val pacer = CommandPacer(gap = 200.milliseconds, timeSource = ts)
        pacer.paced {}
        ts += 300.milliseconds // more than the gap elapsed since the last send
        val before = currentTime
        pacer.paced {}
        assertEquals(before, currentTime, "a send after the gap has already passed must not delay")
    }

    @Test
    fun partiallyElapsedGapDelaysOnlyTheRemainder() = runTest {
        val ts = TestTimeSource()
        val pacer = CommandPacer(gap = 200.milliseconds, timeSource = ts)
        pacer.paced {}
        ts += 150.milliseconds
        val before = currentTime
        pacer.paced {}
        assertEquals(before + 50, currentTime, "only the remaining 50 ms of the gap may be slept")
    }

    @Test
    fun gapIsStampedEvenWhenTheWriteThrows() = runTest {
        val ts = TestTimeSource()
        val pacer = CommandPacer(gap = 200.milliseconds, timeSource = ts)
        runCatching { pacer.paced { error("write failed") } }
        val before = currentTime
        pacer.paced {} // the failed write still consumed a slot → next send is paced
        assertEquals(before + 200, currentTime, "a failed write must still stamp the pacing mark")
    }
}
