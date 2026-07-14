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

    /** Deletes synced batches captured at or before `capturedAtCutoff`, freeing their blob storage.
     *  Never touches a still-pending (`syncedAt IS NULL`) batch. Returns rows deleted. */
    @Query("DELETE FROM outboxBatch WHERE syncedAt IS NOT NULL AND capturedAt <= :capturedAtCutoff")
    suspend fun pruneSyncedBefore(capturedAtCutoff: Long): Int

    /** Latest-wins cursor write (e.g. drain watermark). */
    @Upsert
    suspend fun upsertCursor(row: OutboxCursorRow)

    /** Current value of a named cursor, or null if never written. */
    @Query("SELECT value FROM outboxCursor WHERE name = :name")
    suspend fun cursorValue(name: String): Long?
}
