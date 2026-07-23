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

    // ── #324/#928: futureDatedStrapBanner — the OTHER bad-clock shape (banks records, dated ahead) ──

    private val wallNow = 1_800_000_000L

    @Test
    fun futureBanner_nullWithinTheSkewAllowance() {
        // 47h ahead is inside the 48h skew allowance (time zones + honest drift) — no banner.
        org.junit.Assert.assertNull(
            AndroidWhoopBleClient.futureDatedStrapBanner(wallNow + 47L * 3600L, wallNow),
        )
    }

    @Test
    fun futureBanner_firesBeyondTheSkewAllowance() {
        // 49h ahead: nothing legitimate banks 2 days into the future — one detection is decisive.
        val banner = AndroidWhoopBleClient.futureDatedStrapBanner(wallNow + 49L * 3600L, wallNow)
        assertTrue(banner != null && banner.contains("clock is set in the future"))
    }

    @Test
    fun futureBanner_nullWhenNewestUnknown() {
        // An unanswered range query is UNKNOWN, not future-dated (same rule as isFutureDatedNewest).
        org.junit.Assert.assertNull(AndroidWhoopBleClient.futureDatedStrapBanner(null, wallNow))
    }

    @Test
    fun futureBanner_agreesWithTheAutoContinueGuard() {
        // The banner and the #928/#1012 auto-continue gate key on the SAME predicate, so the sync
        // that refuses to chase a future-dated range is exactly the sync that explains why.
        val ts = wallNow + 3L * 86_400L
        assertTrue(AndroidWhoopBleClient.isFutureDatedNewest(ts, wallNow))
        assertTrue(AndroidWhoopBleClient.futureDatedStrapBanner(ts, wallNow) != null)
    }
}
