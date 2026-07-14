package com.noop.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [OutboxStore] contract tests — mirror the Swift `RawOutboxTests` / `CursorTests` / `PruneTests`
 * (`Packages/WhoopStore/Tests/WhoopStoreTests`). Exactly like [DeviceRegistryTest] (no Robolectric, no
 * real Room database needed for logic-level assertions), these run the REAL [OutboxStore] over an
 * in-memory [FakeOutboxDao] that reproduces the DAO's SQL semantics exactly, with a pass-through
 * transactor (Room's `immediateTransaction` stand-in — these tests are single-coroutine, so no real
 * transaction isolation is exercised here; that is [OutboxStoreConcurrencyTest]'s job).
 */
class OutboxStoreTest {

    /** In-memory stand-in for [OutboxDao]. Reproduces: `INSERT ... ON CONFLICT(batchId) DO NOTHING`
     *  (insertion order preserved, existing row untouched by a retry), the `syncedAt IS NULL` pending
     *  scan ordered by `capturedAt`, mark-synced by `batchId`, `syncedAt`-cutoff prune (Policy 1), a
     *  `rowid`-keyed newest-first byte-size scan + delete-by-rowid (Policy 2), and the cursor table. */
    private class FakeOutboxDao : OutboxDao {
        private val rows = LinkedHashMap<String, OutboxBatchRow>() // insertion order
        private val rowIdOf = LinkedHashMap<String, Long>()
        private var nextRowId = 1L
        private val cursors = LinkedHashMap<String, Long>()

        override suspend fun insertBatch(row: OutboxBatchRow): Long {
            if (rows.containsKey(row.batchId)) return -1L // ON CONFLICT(batchId) DO NOTHING
            rows[row.batchId] = row
            val rowId = nextRowId++
            rowIdOf[row.batchId] = rowId
            return rowId
        }

        override suspend fun pendingBatches(limit: Int): List<OutboxBatchMeta> =
            rows.values.filter { it.syncedAt == null }
                .sortedBy { it.capturedAt }
                .take(limit)
                .map {
                    OutboxBatchMeta(
                        batchId = it.batchId, deviceId = it.deviceId, capturedAt = it.capturedAt,
                        deviceClockRef = it.deviceClockRef, wallClockRef = it.wallClockRef,
                        startTs = it.startTs, endTs = it.endTs, frameCount = it.frameCount,
                        byteSize = it.byteSize, syncedAt = it.syncedAt,
                    )
                }

        override suspend fun framesBlob(batchId: String): ByteArray? = rows[batchId]?.framesBlob

        override suspend fun markSynced(batchId: String, syncedAt: Long): Int {
            val row = rows[batchId] ?: return 0
            rows[batchId] = row.copy(syncedAt = syncedAt)
            return 1
        }

        override suspend fun countByBatchId(batchId: String): Int = if (rows.containsKey(batchId)) 1 else 0

        override suspend fun pruneSyncedBefore(syncedAtCutoff: Long): Int {
            val toDrop = rows.values.filter { it.syncedAt != null && it.syncedAt < syncedAtCutoff }
                .map { it.batchId }
            for (id in toDrop) { rows.remove(id); rowIdOf.remove(id) }
            return toDrop.size
        }

        override suspend fun byteSizesNewestFirst(): List<OutboxRowIdAndByteSize> =
            rows.values
                .sortedWith(compareByDescending<OutboxBatchRow> { it.capturedAt }.thenByDescending { rowIdOf[it.batchId] })
                .map { OutboxRowIdAndByteSize(rowId = rowIdOf.getValue(it.batchId), byteSize = it.byteSize) }

        override suspend fun deleteByRowIds(rowIds: List<Long>): Int {
            val target = rowIds.toSet()
            val toDrop = rowIdOf.filterValues { it in target }.keys.toList()
            for (id in toDrop) { rows.remove(id); rowIdOf.remove(id) }
            return toDrop.size
        }

        override suspend fun upsertCursor(row: OutboxCursorRow) { cursors[row.name] = row.value }

        override suspend fun cursorValue(name: String): Long? = cursors[name]

        fun batchIds(): List<String> = rows.keys.toList() // insertion order, for assertions
    }

    /** [OutboxStore] over the fake DAO with a pass-through transactor. */
    private fun storeWith(dao: FakeOutboxDao) =
        OutboxStore(
            dao,
            object : OutboxStore.Transactor {
                override suspend fun <R> run(block: suspend () -> R): R = block()
            },
        )

    private fun meta(
        id: String,
        capturedAt: Long,
        byteSize: Int = 100,
        deviceId: String = "dev1",
    ) = OutboxBatchMeta(
        batchId = id, deviceId = deviceId, capturedAt = capturedAt,
        deviceClockRef = 0, wallClockRef = 0, startTs = capturedAt, endTs = capturedAt,
        frameCount = 1, byteSize = byteSize, syncedAt = null,
    )

    private val frames = byteArrayOf(0xAA.toByte(), 0x00, 0x01, 0x02)

    @Test
    fun enqueueSameBatchIdTwice_insertsOneRow_returnsTrueBothTimes() = runTest {
        val dao = FakeOutboxDao()
        val store = storeWith(dao)

        val first = store.enqueue(meta("b1", capturedAt = 10), frames)
        assertTrue(first, "a brand-new batchId is durably present after enqueue")

        // Retried capture with the SAME batchId (e.g. a different, possibly stale, payload) — must be
        // a no-op (IGNORE), matching the Swift ON CONFLICT(batchId) DO NOTHING insert, and still report
        // true: the row IS durably present, whether or not THIS call is the one that put it there.
        val retry = store.enqueue(meta("b1", capturedAt = 10, byteSize = 999), byteArrayOf(9, 9))
        assertTrue(retry)

        assertEquals(listOf("b1"), dao.batchIds())
        assertTrue(frames.contentEquals(store.framesBlob("b1")), "the ORIGINAL payload survives the ignored retry")
    }

    @Test
    fun framesBlobIsOpaqueBytes_nullForUnknownBatch() = runTest {
        val store = storeWith(FakeOutboxDao())
        assertNull(store.framesBlob("nope"))
        store.enqueue(meta("b1", capturedAt = 10), frames)
        val got = store.framesBlob("b1")
        assertTrue(got != null && frames.contentEquals(got))
    }

    @Test
    fun rejectedRawFramesRoundTripThroughOutboxWithoutLoss() = runTest {
        // Phase 2c-2 Task 12 scope 3: REJECTED/raw historical records (CRC-garbled, unmapped-version,
        // gate-failed — anything the decoder left raw) must survive enqueue → framesBlob byte-for-byte,
        // because re-decode after a future firmware RE pass is the whole point of keeping them. Encodes
        // with the shared [com.noop.ble.OutboxCodec] (the Kotlin twin of Swift RawOutbox) and asserts
        // the decoded frames are content-identical, including a deliberately corrupt frame.
        val garbledRejected = ByteArray(84) { ((it * 13 + 1) and 0xFF).toByte() } // fails every CRC/gate
        val healthy = byteArrayOf(0xAA.toByte(), 0x03, 0x00, 0x07, 0x2F, 0x18)
        val original = listOf(healthy, garbledRejected, ByteArray(0))
        val blob = com.noop.ble.OutboxCodec.zlibCompressWithLength(
            com.noop.ble.OutboxCodec.packFrames(original),
        )

        val store = storeWith(FakeOutboxDao())
        store.enqueue(meta("rejected-batch", capturedAt = 42, byteSize = blob.size), blob)

        val readBack = store.framesBlob("rejected-batch")
        assertTrue(readBack != null && blob.contentEquals(readBack), "blob is opaque to the store — byte-identical")
        val frames = com.noop.ble.OutboxCodec.unpackFrames(
            com.noop.ble.OutboxCodec.zlibDecompressWithLength(readBack!!),
        )
        assertEquals(original.size, frames.size)
        for (i in original.indices) {
            assertTrue(original[i].contentEquals(frames[i]), "frame $i must survive the outbox untouched")
        }
    }

    @Test
    fun pendingReturnsOldestFirst_upToLimit() = runTest {
        val store = storeWith(FakeOutboxDao())
        store.enqueue(meta("new", capturedAt = 300), frames)
        store.enqueue(meta("old", capturedAt = 100), frames)
        store.enqueue(meta("mid", capturedAt = 200), frames)

        val all = store.pending(limit = 10)
        assertEquals(listOf("old", "mid", "new"), all.map { it.batchId })

        val limited = store.pending(limit = 1)
        assertEquals(listOf("old"), limited.map { it.batchId })
    }

    @Test
    fun markSyncedExcludesFromPending() = runTest {
        val store = storeWith(FakeOutboxDao())
        store.enqueue(meta("old", capturedAt = 100), frames)
        store.enqueue(meta("mid", capturedAt = 200), frames)
        store.enqueue(meta("new", capturedAt = 300), frames)

        store.markSynced("mid", at = 999)

        val pending = store.pending(limit = 10)
        assertEquals(listOf("old", "new"), pending.map { it.batchId })

        // Unknown batchId: a no-op, not an error, matching the Swift UPDATE.
        store.markSynced("no-such-batch", at = 1)
    }

    @Test
    fun prunePolicy1_deletesAgedSyncedBatches_keepsFreshSyncedAndUnsynced() = runTest {
        // Mirrors Swift PruneTests.testPrunesAgedSyncedBatches.
        val store = storeWith(FakeOutboxDao())
        store.enqueue(meta("aged", capturedAt = 10), frames)
        store.enqueue(meta("fresh", capturedAt = 20), frames)
        store.enqueue(meta("unsynced", capturedAt = 30), frames)
        store.markSynced("aged", at = 1000)
        store.markSynced("fresh", at = 9500)

        val pruned = store.prune(now = 10_000, keepWindowSeconds = 1_000, maxUnsyncedBytes = 1_000_000)

        assertEquals(1, pruned) // only "aged" (syncedAt=1000 < cutoff=9000)
        val remaining = store.pending(limit = 10).map { it.batchId }.toSet() +
            setOfNotNull("fresh".takeIf { store.framesBlob("fresh") != null })
        assertTrue(store.framesBlob("aged") == null, "aged synced batch pruned")
        assertTrue(store.framesBlob("fresh") != null, "recently-synced batch survives the keep window")
        assertTrue(store.framesBlob("unsynced") != null, "unsynced batch untouched by Policy 1")
    }

    @Test
    fun prunePolicy2_evictsOldestBeyondByteCap_regardlessOfSyncStatus() = runTest {
        // Mirrors Swift PruneTests.testEvictionAppliesToSyncedAndUnsyncedAlike: the byte cap is a
        // total-footprint bound. A freshly-synced batch still inside the Policy 1 keep window still
        // counts toward the cap, and the OLDEST raw — synced or not — is evicted first.
        val store = storeWith(FakeOutboxDao())
        store.enqueue(meta("s1", capturedAt = 10, byteSize = 800), frames)
        store.enqueue(meta("u2", capturedAt = 20, byteSize = 800), frames)
        store.markSynced("s1", at = 9000) // recent -> survives Policy 1

        val pruned = store.prune(now = 9_500, keepWindowSeconds = 1_000, maxUnsyncedBytes = 1_000)

        assertEquals(1, pruned) // u2 (800) fits under the 1000 cap; s1 tips it over -> evicted
        assertNull(store.framesBlob("s1"))
        assertTrue(store.framesBlob("u2") != null)
    }

    @Test
    fun prunePolicy2_evictsOldestFirst_amongPurelyUnsyncedBatches() = runTest {
        // Mirrors Swift PruneTests.testEvictsOldestRawBeyondByteCap.
        val store = storeWith(FakeOutboxDao())
        store.enqueue(meta("u1", capturedAt = 10, byteSize = 500), frames)
        store.enqueue(meta("u2", capturedAt = 20, byteSize = 500), frames)
        store.enqueue(meta("u3", capturedAt = 30, byteSize = 500), frames)

        // Cap 1000 < 1500 total: newest-first u3(500)+u2(500)=1000 fits, u1 tips it over -> evicted.
        val pruned = store.prune(now = 100, keepWindowSeconds = 0, maxUnsyncedBytes = 1_000)

        assertEquals(1, pruned)
        assertNull(store.framesBlob("u1"), "oldest dropped")
        assertTrue(store.framesBlob("u2") != null)
        assertTrue(store.framesBlob("u3") != null)
    }

    @Test
    fun pruneReturnsZeroWhenNothingToPrune() = runTest {
        val store = storeWith(FakeOutboxDao())
        store.enqueue(meta("u1", capturedAt = 10, byteSize = 100), frames)
        val pruned = store.prune(now = 100, keepWindowSeconds = 1_000, maxUnsyncedBytes = 1_000_000)
        assertEquals(0, pruned)
        assertTrue(store.framesBlob("u1") != null)
    }

    @Test
    fun cursorRoundTrips_includingStrapTrimName() = runTest {
        val store = storeWith(FakeOutboxDao())
        assertNull(store.cursor("strap_trim"))
        store.setCursor("strap_trim", 12_345)
        assertEquals(12_345L, store.cursor("strap_trim"))
        store.setCursor("strap_trim", 12_400) // latest-wins, no duplicate row semantics
        assertEquals(12_400L, store.cursor("strap_trim"))
    }

    @Test
    fun highwaterAndReadHighwaterUseDistinctPrefixedCursorNames() = runTest {
        val store = storeWith(FakeOutboxDao())
        assertNull(store.highwater("hr"))
        assertNull(store.readHighwater("hr"))

        store.setHighwater("hr", 100)
        store.setReadHighwater("hr", 200)

        // The upload watermark and the pull watermark for the SAME stream never collide.
        assertEquals(100L, store.highwater("hr"))
        assertEquals(200L, store.readHighwater("hr"))
        assertEquals(100L, store.cursor("highwater:hr"))
        assertEquals(200L, store.cursor("read:hr"))
    }
}
