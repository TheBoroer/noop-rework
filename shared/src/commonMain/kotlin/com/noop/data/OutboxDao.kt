package com.noop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/**
 * Room DAO for the outbox tables ([OutboxBatchRow], [OutboxCursorRow]), Phase 2c-2 Task 1. Mirrors the
 * GRDB outbox writer/drainer shape: an idempotent capture insert (a retried capture with the same
 * `batchId` is a no-op), a `syncedAt`-ordered pending scan (blob excluded, see [framesBlob]),
 * mark-synced, a prune sweep, and a tiny named-cursor upsert/read pair for drain bookkeeping.
 */
@Dao
interface OutboxDao {

    /**
     * Idempotent capture write: a retried capture with the same `batchId` is a no-op, matching the
     * Swift `ON CONFLICT(batchId) DO NOTHING` insert. Returns the new rowid, or -1 when ignored
     * (existing Room `IGNORE` convention for a single-row insert).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatch(row: OutboxBatchRow): Long

    /** Oldest-first, not-yet-synced batches (blob excluded; fetch it via [framesBlob] once a batch is
     *  actually being uploaded). */
    @Query(
        "SELECT `batchId`, `deviceId`, `capturedAt`, `deviceClockRef`, `wallClockRef`, `startTs`, " +
            "`endTs`, `frameCount`, `byteSize`, `syncedAt` FROM outboxBatch " +
            "WHERE syncedAt IS NULL ORDER BY capturedAt LIMIT :limit",
    )
    suspend fun pendingBatches(limit: Int): List<OutboxBatchMeta>

    /** The raw frame payload for one batch, or null if no such batch exists. */
    @Query("SELECT framesBlob FROM outboxBatch WHERE batchId = :batchId")
    suspend fun framesBlob(batchId: String): ByteArray?

    /** Marks a batch uploaded at `syncedAt` (caller-supplied timestamp). Returns rows changed (0 when
     *  no such batch exists). */
    @Query("UPDATE outboxBatch SET syncedAt = :syncedAt WHERE batchId = :batchId")
    suspend fun markSynced(batchId: String, syncedAt: Long): Int

    /**
     * 1 if a batch with [batchId] exists (synced or not), else 0. [OutboxStore.enqueue] uses this,
     * post-insert, to confirm the row is durably present regardless of whether THIS call performed the
     * insert or a concurrent/earlier caller already had (the `OnConflictStrategy.IGNORE` case).
     */
    @Query("SELECT COUNT(*) FROM outboxBatch WHERE batchId = :batchId")
    suspend fun countByBatchId(batchId: String): Int

    /**
     * Deletes synced batches whose `syncedAt` timestamp is strictly older than [syncedAtCutoff],
     * freeing their blob storage. Never touches a still-pending (`syncedAt IS NULL`) batch. Returns rows
     * deleted.
     *
     * Phase 2c-2 Task 2: this now cuts on `syncedAt < cutoff` (matching GRDB `pruneRaw` Policy 1,
     * `Packages/WhoopStore/Sources/WhoopStore/RawOutbox.swift`) — Task 1's original cut on
     * `capturedAt <= cutoff` was a placeholder pending the Swift-source read this task's carry-over
     * flagged. `capturedAt` measures when the batch was CAPTURED, not when it finished uploading, so it
     * has no bearing on "how long has this synced batch been sitting around" — a batch captured long ago
     * but synced just now must NOT be pruned by an age-of-capture check.
     */
    @Query("DELETE FROM outboxBatch WHERE syncedAt IS NOT NULL AND syncedAt < :syncedAtCutoff")
    suspend fun pruneSyncedBefore(syncedAtCutoff: Long): Int

    /**
     * `(rowid, byteSize)` for every batch (synced or not), newest-first: `capturedAt DESC`, with `rowid
     * DESC` as a stable tiebreaker on equal `capturedAt` (mirrors GRDB `pruneRaw` Policy 2's `ORDER BY
     * capturedAt DESC, rowid DESC`). [OutboxStore.prune] walks this newest-to-oldest, summing `byteSize`,
     * to find which OLDEST rows push the running total past the byte cap.
     */
    @Query("SELECT rowid AS rowId, byteSize FROM outboxBatch ORDER BY capturedAt DESC, rowid DESC")
    suspend fun byteSizesNewestFirst(): List<OutboxRowIdAndByteSize>

    /** Deletes the batches with the given `rowid`s (Policy 2 byte-cap eviction). Returns rows deleted. */
    @Query("DELETE FROM outboxBatch WHERE rowid IN (:rowIds)")
    suspend fun deleteByRowIds(rowIds: List<Long>): Int

    /** Latest-wins cursor write (e.g. drain watermark). */
    @Upsert
    suspend fun upsertCursor(row: OutboxCursorRow)

    /** Current value of a named cursor, or null if never written. */
    @Query("SELECT value FROM outboxCursor WHERE name = :name")
    suspend fun cursorValue(name: String): Long?
}

/** Row projection for [OutboxDao.byteSizesNewestFirst]: a batch's SQLite `rowid` (stable identity for
 *  the eviction delete, distinct from the `batchId` natural key) plus its `byteSize`. */
data class OutboxRowIdAndByteSize(
    val rowId: Long,
    val byteSize: Int,
)
