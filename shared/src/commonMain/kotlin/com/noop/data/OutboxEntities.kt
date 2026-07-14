package com.noop.data

import androidx.room.Entity
import androidx.room.Index

/**
 * Room mirrors of the legacy GRDB outbox tables (the raw-frame capture queue the Swift store drains
 * to the backend), Phase 2c-2 Task 1. Two roles:
 *  - `outboxBatch`: one row per captured raw-frame batch awaiting upload. `framesBlob` holds the
 *    serialized frame payload verbatim (opaque to Room, whatever encoding the writer used). The
 *    natural key is `batchId` (caller-generated, e.g. a UUID), so a retried capture dedupes via
 *    `OnConflictStrategy.IGNORE`, matching the Swift `ON CONFLICT(batchId) DO NOTHING` insert.
 *  - `outboxCursor`: a tiny named key/value table for sync bookkeeping (drain watermarks etc.),
 *    mirroring the GRDB cursor table's PK(name) shape.
 *
 * No backfill of legacy GRDB rows happens in the migration that creates these tables (see
 * [WhoopDatabase.OUTBOX_MIGRATION_SQL]): Task 6's one-time "drain" reads the old outbox and populates
 * these tables; MIGRATION_17_18 only creates them, empty.
 */

/**
 * One captured, not-yet-uploaded (or already-uploaded, until pruned) raw outbox batch.
 *
 * Indices: `syncedAt` backs the pending scan ([OutboxDao.pendingBatches] filters `WHERE syncedAt IS
 * NULL`) and the prune-by-age sweep ([OutboxDao.pruneSyncedBefore] filters + cuts on `syncedAt`, Phase
 * 2c-2 Task 2 — matching GRDB `pruneRaw` Policy 1); `capturedAt` backs the pending scan's `ORDER BY
 * capturedAt` and the Policy 2 byte-cap eviction ordering ([OutboxDao.byteSizesNewestFirst]).
 */
@Entity(
    tableName = "outboxBatch",
    indices = [
        Index(name = "idx_outboxBatch_syncedAt", value = ["syncedAt"]),
        Index(name = "idx_outboxBatch_capturedAt", value = ["capturedAt"]),
    ],
)
data class OutboxBatchRow(
    @androidx.room.PrimaryKey
    val batchId: String,
    val deviceId: String,
    val capturedAt: Long,
    val deviceClockRef: Long,
    val wallClockRef: Long,
    val startTs: Long,
    val endTs: Long,
    val frameCount: Int,
    val byteSize: Int,
    val framesBlob: ByteArray,
    val syncedAt: Long? = null,
) {
    // ByteArray has no structural equals/hashCode, so a plain data-class-generated pair would compare
    // framesBlob by reference; overridden manually (contentEquals/contentHashCode) so two rows read
    // back from disk with the same bytes are equal, e.g. in migration/round-trip tests.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxBatchRow) return false
        return batchId == other.batchId &&
            deviceId == other.deviceId &&
            capturedAt == other.capturedAt &&
            deviceClockRef == other.deviceClockRef &&
            wallClockRef == other.wallClockRef &&
            startTs == other.startTs &&
            endTs == other.endTs &&
            frameCount == other.frameCount &&
            byteSize == other.byteSize &&
            framesBlob.contentEquals(other.framesBlob) &&
            syncedAt == other.syncedAt
    }

    override fun hashCode(): Int {
        var result = batchId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + capturedAt.hashCode()
        result = 31 * result + deviceClockRef.hashCode()
        result = 31 * result + wallClockRef.hashCode()
        result = 31 * result + startTs.hashCode()
        result = 31 * result + endTs.hashCode()
        result = 31 * result + frameCount
        result = 31 * result + byteSize
        result = 31 * result + framesBlob.contentHashCode()
        result = 31 * result + (syncedAt?.hashCode() ?: 0)
        return result
    }
}

/** Named sync-cursor watermark (e.g. drain progress). Natural key = name. */
@Entity(tableName = "outboxCursor")
data class OutboxCursorRow(
    @androidx.room.PrimaryKey
    val name: String,
    val value: Long,
)

/**
 * [OutboxBatchRow] projection excluding `framesBlob`, for [OutboxDao.pendingBatches]: the pending scan
 * runs frequently and must not pull the (possibly large) frame payload into memory for every row —
 * callers fetch the blob for one batch at a time via [OutboxDao.framesBlob] once they're ready to
 * upload it.
 */
data class OutboxBatchMeta(
    val batchId: String,
    val deviceId: String,
    val capturedAt: Long,
    val deviceClockRef: Long,
    val wallClockRef: Long,
    val startTs: Long,
    val endTs: Long,
    val frameCount: Int,
    val byteSize: Int,
    val syncedAt: Long?,
)
