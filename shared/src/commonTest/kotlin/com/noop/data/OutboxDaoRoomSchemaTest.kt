package com.noop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/**
 * Phase 2c-2 Task 1 drift guard: does a FRESH v18 database, built through Room's own KSP-generated
 * schema for [OutboxBatchRow] / [OutboxCursorRow] (no hand-written SQL involved at all), accept the
 * exact same shape of insert/readback that [OutboxMigrationTest] proves against the hand-written
 * [WhoopDatabase.OUTBOX_MIGRATION_SQL] on the plain-JVM androidUnitTest target?
 *
 * This needs a real Room instance, so — exactly like [WhoopDaoStreamQueryTest] / [BackupRestoreTest] —
 * the body is gated on [canRunFullRestore]: confirmed empirically that Room's KMP
 * `Room.databaseBuilder<WhoopDatabase>(name)` requires a `Context` on the Android target specifically
 * (compile error: "No value passed for parameter 'context'"), so the plain-JVM androidUnitTest target
 * has no way to open a real Room database off-device (same limitation `canRunFullRestore` already
 * documents for the classic Android builder). The iosSimulatorArm64 / macosArm64 targets carry the
 * end-to-end proof; on JVM the test returns early (still counted, always green).
 */
class OutboxDaoRoomSchemaTest {

    private fun freshDb(name: String): WhoopDatabase =
        openWhoopDatabaseAt((tempWorkDir().toPath() / name).toString())

    @Test
    fun freshV18Schema_acceptsSameInsertReadbackAsTheMigration() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("outbox-fresh-v18.db")
        try {
            val dao = db.outboxDao()
            val blob = byteArrayOf(1, 2, 3, 4, 5, 0, -1, 127, -128)
            val row = OutboxBatchRow(
                batchId = "batch-1",
                deviceId = "dev1",
                capturedAt = 1_000,
                deviceClockRef = 10,
                wallClockRef = 20,
                startTs = 900,
                endTs = 1_000,
                frameCount = 42,
                byteSize = blob.size,
                framesBlob = blob,
                syncedAt = null,
            )

            val rowid = dao.insertBatch(row)
            assertTrue(rowid != -1L, "insert of a new batchId must succeed")

            // Idempotent retry: same batchId, different payload, must be a no-op (IGNORE), matching
            // the Swift ON CONFLICT(batchId) DO NOTHING semantics this DAO method mirrors.
            val retryRowid = dao.insertBatch(row.copy(deviceId = "other-device"))
            assertEquals(-1L, retryRowid, "retried capture with the same batchId must be ignored")

            val pending = dao.pendingBatches(10)
            assertEquals(1, pending.size)
            assertEquals("batch-1", pending[0].batchId)
            assertEquals("dev1", pending[0].deviceId) // unchanged by the ignored retry
            assertNull(pending[0].syncedAt)

            val fetchedBlob = dao.framesBlob("batch-1")
            assertTrue(fetchedBlob != null && blob.contentEquals(fetchedBlob))

            val changed = dao.markSynced("batch-1", 5_000)
            assertEquals(1, changed)
            assertEquals(0, dao.pendingBatches(10).size, "synced batch drops out of the pending scan")
            assertEquals(0, dao.markSynced("no-such-batch", 5_000))

            dao.upsertCursor(OutboxCursorRow("drain", 7))
            assertEquals(7L, dao.cursorValue("drain"))
            assertNull(dao.cursorValue("no-such-cursor"))
            dao.upsertCursor(OutboxCursorRow("drain", 8)) // latest-wins
            assertEquals(8L, dao.cursorValue("drain"))

            val pruned = dao.pruneSyncedBefore(10_000)
            assertEquals(1, pruned)
            assertNull(dao.framesBlob("batch-1"), "pruned batch's blob is gone")
        } finally {
            db.close()
        }
    }
}
