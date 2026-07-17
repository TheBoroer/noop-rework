package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [AndroidWhoopBleClient.classifyCompletedOffload] — the pure classification exitBackfilling runs on a
 * COMPLETED (HISTORY_COMPLETE) offload. The #214 fix is the `rowsPersisted == 0` arm: before it, the
 * empty-banking signal had ONE shape (console-only across ≥3 diagnostic chunks), so a NEAR-EMPTY
 * metadata-only completion (zero rows persisted, fewer than 3 console frames) slipped through to the
 * silent branch and surfaced no "charge to 100% and reconnect" guidance. Kotlin twin of the macOS
 * EmptyBankingClassifierTests. Banner firing still requires a SUSTAINED streak (EmptySyncTracker, #126).
 */
class EmptyBankingClassifierTest {

    @Test
    fun decodedChunksAreBanking() {
        val (banked, nothing) = AndroidWhoopBleClient.classifyCompletedOffload(
            decodedChunks = 5, consoleChunks = 0, rowsPersisted = 120,
        )
        assertTrue(banked)
        assertFalse(nothing)
    }

    @Test
    fun consoleOnlyAcrossManyChunksIsBankedNothing() {
        val (banked, nothing) = AndroidWhoopBleClient.classifyCompletedOffload(
            decodedChunks = 0, consoleChunks = 4, rowsPersisted = 0,
        )
        assertFalse(banked)
        assertTrue(nothing)
    }

    // #214 regression case: metadata-only completion — zero rows, FEWER than 3 console frames.
    @Test
    fun metadataOnlyZeroRowsIsBankedNothing() {
        val (banked, nothing) = AndroidWhoopBleClient.classifyCompletedOffload(
            decodedChunks = 0, consoleChunks = 0, rowsPersisted = 0,
        )
        assertFalse(banked)
        assertTrue("#214: a metadata-only completion that persisted 0 rows banks nothing", nothing)
    }

    @Test
    fun fewConsoleFramesZeroRowsIsBankedNothing() {
        val (_, nothing) = AndroidWhoopBleClient.classifyCompletedOffload(
            decodedChunks = 0, consoleChunks = 2, rowsPersisted = 0,
        )
        assertTrue("#214: < 3 console frames no longer hides a zero-row completion", nothing)
    }

    // Rows persisted (but nothing decoded this pass) is NOT "banked nothing".
    @Test
    fun rowsPersistedIsNotBankedNothing() {
        val (banked, nothing) = AndroidWhoopBleClient.classifyCompletedOffload(
            decodedChunks = 0, consoleChunks = 0, rowsPersisted = 40,
        )
        assertTrue(banked)
        assertFalse("rows were persisted — the strap is banking", nothing)
    }

    // The new signal still feeds the SUSTAINED-streak gate: 3 consecutive metadata-only completions are
    // required before the banner trips (the #126 guard is unchanged); a banking cycle clears the streak.
    @Test
    fun metadataOnlyTripsBannerOnlyWhenSustained() {
        val tracker = EmptySyncTracker()   // default threshold 3
        fun recordMetadataOnly(): Boolean {
            val (banked, nothing) = AndroidWhoopBleClient.classifyCompletedOffload(0, 0, 0)
            return tracker.recordCompletedSync(bankedSensorRecords = banked, consoleOnly = nothing)
        }
        assertFalse(recordMetadataOnly())
        assertFalse(recordMetadataOnly())
        assertTrue("#214 + #126: three consecutive metadata-only completions trip the guidance",
            recordMetadataOnly())
    }

    @Test
    fun bankingCycleResetsTheMetadataOnlyStreak() {
        val tracker = EmptySyncTracker()
        val empty = AndroidWhoopBleClient.classifyCompletedOffload(0, 0, 0)
        val banking = AndroidWhoopBleClient.classifyCompletedOffload(decodedChunks = 3, consoleChunks = 0, rowsPersisted = 90)
        tracker.recordCompletedSync(bankedSensorRecords = empty.first, consoleOnly = empty.second)
        tracker.recordCompletedSync(bankedSensorRecords = empty.first, consoleOnly = empty.second)
        tracker.recordCompletedSync(bankedSensorRecords = banking.first, consoleOnly = banking.second)
        assertEquals(0, tracker.consecutiveEmptySyncs)
    }
}
