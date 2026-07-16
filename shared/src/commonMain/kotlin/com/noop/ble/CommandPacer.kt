package com.noop.ble

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Serializes strap-bound command writes and enforces a minimum gap between them.
 *
 * Hardware finding (T15e / #75, on-wrist WHOOP 4.0 diagnostic): the strap services roughly one
 * command per connection event and SILENTLY DROPS the rest of a back-to-back burst. An
 * instrumented run showed every write clearing the local Kable/CoreBluetooth path ("tx done"),
 * yet only the burst tails were acked (seq=2, seq=14) while seq 3-12 — hello, version, clock,
 * SEND_R10_R11_REALTIME, TOGGLE_REALTIME_HR — were ignored, so realtime never armed (0 HR rows
 * in 90 s on-wrist). The identical sequence paced at 200 ms was acked 14/14 and produced
 * type-40/43 frames plus HR inserts within seconds. The old Swift BLEManager never hit this
 * because its call sites were naturally spaced (UI/timer-driven), not because CoreBluetooth
 * paced anything — the Kotlin connect handshake + arm path is the first flow that fires the
 * whole sequence in one synchronous burst.
 *
 * Pacing is burst-only: a send arriving later than [gap] after the previous one pays nothing,
 * so keep-alive ticks and user-triggered commands are unaffected.
 */
class CommandPacer(
    private val gap: Duration = DEFAULT_GAP,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val lock = Mutex()
    private var lastSend: TimeMark? = null

    /**
     * Runs [write] inside the pacing slot: serialized with other senders, and starting at least
     * [gap] after the previous write finished. The mark is stamped after [write] returns (even
     * on failure), so the gap covers the strap's processing window, not just our call spacing.
     */
    suspend fun <T> paced(write: suspend () -> T): T = lock.withLock {
        lastSend?.let { prev ->
            val remaining = gap - prev.elapsedNow()
            if (remaining > Duration.ZERO) delay(remaining)
        }
        try {
            write()
        } finally {
            lastSend = timeSource.markNow()
        }
    }

    companion object {
        /**
         * Minimum inter-command gap. 200 ms is the hardware-validated value from the T15e
         * diagnostic (14/14 acks, realtime armed); comfortably above the strap's ~1 command per
         * connection event service rate while keeping the 10-command connect handshake under ~2 s.
         */
        val DEFAULT_GAP: Duration = 200.milliseconds
    }
}
