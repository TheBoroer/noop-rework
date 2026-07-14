package com.noop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-concurrency proof for [OutboxStore]: many actual coroutines (`Dispatchers.Default`, real
 * threads, not `runTest`'s virtual-time dispatcher) driving ONE shared [OutboxStore] handle over a
 * REAL Room database. This is the named replacement for the Swift `DatabasePoolConcurrencyTests`
 * deleted in Task 8 — GRDB's `DatabasePool` gave the old store safe concurrent readers/writers for
 * free; here Room's own writer-connection serialization (`useWriterConnection` /
 * `immediateTransaction`, wired in [OutboxStore]'s production constructor) has to carry the same
 * guarantee, so this test exercises it directly rather than trusting [OutboxStoreTest]'s single-
 * coroutine fake-DAO logic tests.
 *
 * Needs a real Room instance, so — exactly like [OutboxDaoRoomSchemaTest] — gated on
 * [canRunFullRestore]: false on plain-JVM androidUnitTest (no way to open Room off-device there),
 * true on the Apple native targets (macosArm64 / iosSimulatorArm64), which carry the end-to-end proof.
 */
class OutboxStoreConcurrencyTest {

    private fun freshStore(name: String): Pair<WhoopDatabase, OutboxStore> {
        val db = openWhoopDatabaseAt((tempWorkDir().toPath() / name).toString())
        return db to OutboxStore(db)
    }

    private fun meta(id: String, capturedAt: Long, byteSize: Int = 64) = OutboxBatchMeta(
        batchId = id, deviceId = "dev1", capturedAt = capturedAt,
        deviceClockRef = 0, wallClockRef = 0, startTs = capturedAt, endTs = capturedAt,
        frameCount = 1, byteSize = byteSize, syncedAt = null,
    )

    private val frames = ByteArray(16) { it.toByte() }

    @Test
    fun concurrentEnqueue_distinctBatchIds_noLostRows_noDuplicates() = runTest {
        if (!canRunFullRestore) return@runTest
        val (db, store) = freshStore("outbox-concurrency-distinct.db")
        try {
            val n = 200
            coroutineScope {
                (0 until n).map { i ->
                    async(Dispatchers.Default) { store.enqueue(meta("batch-$i", capturedAt = i.toLong()), frames) }
                }.awaitAll()
            }.let { results -> assertTrue(results.all { it }, "every concurrent enqueue reports durably-present") }

            val pending = store.pending(limit = n + 10)
            assertEquals(n, pending.size, "no lost rows under concurrent enqueue")
            assertEquals(n, pending.map { it.batchId }.toSet().size, "no duplicate batchId under concurrent enqueue")
        } finally {
            db.close()
        }
    }

    @Test
    fun concurrentEnqueue_sameBatchId_dedupesToExactlyOneRow_allCallersSeeDurablyPresent() = runTest {
        if (!canRunFullRestore) return@runTest
        val (db, store) = freshStore("outbox-concurrency-same-id.db")
        try {
            val racers = 50
            val results = coroutineScope {
                (0 until racers).map { i ->
                    async(Dispatchers.Default) {
                        // Same batchId from every racer: a real INSERT-vs-INSERT race on the natural key.
                        store.enqueue(meta("race-batch", capturedAt = 1), frames + i.toByte())
                    }
                }.awaitAll()
            }

            assertTrue(results.all { it }, "every racer sees the row as durably present, win or lose the insert")
            val pending = store.pending(limit = 10)
            assertEquals(1, pending.size, "concurrent same-batchId enqueues collapse to exactly one row")
            assertEquals("race-batch", pending[0].batchId)
            assertTrue(store.framesBlob("race-batch") != null)
        } finally {
            db.close()
        }
    }

    @Test
    fun concurrentEnqueueMarkSyncedAndPending_exactPartition_noLostRows_noDuplicates() = runTest {
        if (!canRunFullRestore) return@runTest
        val (db, store) = freshStore("outbox-concurrency-partition.db")
        try {
            val n = 120
            // Phase 1: concurrently enqueue N distinct batches on one shared handle.
            coroutineScope {
                (0 until n).map { i ->
                    async(Dispatchers.Default) { store.enqueue(meta("b-$i", capturedAt = i.toLong()), frames) }
                }.awaitAll()
            }

            val allIds = (0 until n).map { "b-$it" }
            val toSync = allIds.filterIndexed { idx, _ -> idx % 2 == 0 }.toSet()

            // Phase 2: interleave concurrent markSynced (on half the batches) with concurrent pending()
            // reads on the SAME shared handle — the pending reads don't assert mid-flight (interleaved
            // reads may legitimately observe any subset), they just exercise concurrent readers/writers
            // against Room's writer-connection serialization without deadlocking or throwing.
            coroutineScope {
                val writers = toSync.map { id ->
                    async(Dispatchers.Default) { store.markSynced(id, at = 999) }
                }
                val readers = (0 until 20).map {
                    async(Dispatchers.Default) { store.pending(limit = n + 10) }
                }
                writers.awaitAll()
                readers.awaitAll()
            }

            // Phase 3: after everything settles, the pending/synced partition must be EXACT — every
            // batch is in pending xor synced, nothing lost, nothing duplicated.
            val pending = store.pending(limit = n + 10)
            val pendingIds = pending.map { it.batchId }
            assertEquals(pendingIds.size, pendingIds.toSet().size, "no duplicate batchId in pending")

            val expectedPending = allIds.toSet() - toSync
            assertEquals(expectedPending, pendingIds.toSet(), "pending = all batches minus the synced ones, exactly")

            for (id in toSync) {
                assertTrue(store.framesBlob(id) != null, "synced batch $id must not have been lost")
            }
            assertEquals(n, (pendingIds.toSet() + toSync).size, "pending ∪ synced covers every enqueued batch exactly once")
        } finally {
            db.close()
        }
    }
}
