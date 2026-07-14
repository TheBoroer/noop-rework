package com.noop.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Suspend-API façade over [OutboxDao] + [WhoopDatabase], Phase 2c-2 Task 2 — the Kotlin twin of the
 * Swift GRDB outbox API (`Packages/WhoopStore/Sources/WhoopStore/RawOutbox.swift` +
 * `Cursors.swift`). Method names and cursor name strings mirror the Swift originals exactly (including
 * the `"strap_trim"` cursor name Backfiller uses, and the `highwater:` / `read:` prefixes), so a Task 3
 * Swift bridge can forward 1:1 without renaming anything.
 *
 * The frames blob is OPAQUE here: already packed + zlib-compressed by the caller (see `RawOutbox.swift`
 * `packFrames` / `zlibCompressWithLength`); this store never inspects or re-encodes it. Codec parity
 * only matters once Kotlin becomes a WRITER of that format (Task 12), not for this passthrough store.
 *
 * Follows the [DeviceRegistry] pattern: production wraps Room's KMP write-transaction boundary
 * (`useWriterConnection { it.immediateTransaction { ... } }`, the common-artifact replacement for the
 * Android-only `androidx.room.withTransaction`), injected as [Transactor] so the store's own logic is
 * exercisable in `commonTest` against a fake [OutboxDao] with no real Room database (see
 * `OutboxStoreTest`). The concurrency guarantee itself — many real coroutines against a real Room
 * database, one shared handle — is proven separately in `OutboxStoreConcurrencyTest` (the named
 * replacement for the Swift `DatabasePoolConcurrencyTests` deleted in Task 8).
 */
class OutboxStore(
    private val dao: OutboxDao,
    private val transactor: Transactor,
) {
    /** A single-transaction boundary. Production wraps Room's KMP `useWriterConnection` +
     *  `immediateTransaction`; tests pass through. Not a `fun interface` — a SAM method may not be
     *  generic — so implementors use the object form (mirrors [DeviceRegistry.Transactor]). */
    interface Transactor {
        suspend fun <R> run(block: suspend () -> R): R
    }

    /** Production constructor: wraps [OutboxDao] + Room's KMP write transaction over [db]. */
    constructor(db: WhoopDatabase) : this(
        dao = db.outboxDao(),
        transactor = object : Transactor {
            override suspend fun <R> run(block: suspend () -> R): R =
                db.useWriterConnection { it.immediateTransaction { block() } }
        },
    )

    /**
     * Insert-or-already-present, keyed on `batchId` — the Kotlin twin of Swift `enqueueRawBatch`. Runs
     * in ONE transaction: the [OutboxDao.insertBatch] (`OnConflictStrategy.IGNORE`, matching the Swift
     * `ON CONFLICT(batchId) DO NOTHING`) followed by an explicit existence check, so the returned
     * [Boolean] is a verified durability signal rather than an inference from the insert's own return
     * value — a retried capture with the SAME `batchId` is a no-op that still reports `true` (the row
     * IS durably present, just not because of this call).
     *
     * `meta.syncedAt` is ignored: a newly-enqueued batch is always unsynced, exactly like the Swift
     * `RawBatchMeta` input (which has no `syncedAt` field at all — the raw INSERT hardcodes `NULL`).
     *
     * This is the commit signal persist-before-ack callers gate on (the Hard Invariant): only ack the
     * strap's trim cursor after this returns `true`, never on enqueue-attempt alone.
     */
    suspend fun enqueue(meta: OutboxBatchMeta, framesBlob: ByteArray): Boolean = transactor.run {
        dao.insertBatch(
            OutboxBatchRow(
                batchId = meta.batchId,
                deviceId = meta.deviceId,
                capturedAt = meta.capturedAt,
                deviceClockRef = meta.deviceClockRef,
                wallClockRef = meta.wallClockRef,
                startTs = meta.startTs,
                endTs = meta.endTs,
                frameCount = meta.frameCount,
                byteSize = meta.byteSize,
                framesBlob = framesBlob,
                syncedAt = null,
            ),
        )
        dao.countByBatchId(meta.batchId) > 0
    }

    /** Un-synced batches, oldest-first, capped at [limit] — the Kotlin twin of Swift `pendingRawBatches`. */
    suspend fun pending(limit: Int): List<OutboxBatchMeta> = dao.pendingBatches(limit)

    /** The raw frame payload for [batchId], or null if no such batch exists — the Kotlin twin of Swift
     *  `rawFrames`. Opaque bytes: not decoded/validated here. */
    suspend fun framesBlob(batchId: String): ByteArray? = dao.framesBlob(batchId)

    /** Marks a batch uploaded at [at] (caller-supplied unix-second timestamp) — the Kotlin twin of Swift
     *  `markRawBatchSynced`. A no-op (not an error) if `batchId` is unknown, matching the Swift UPDATE. */
    suspend fun markSynced(batchId: String, at: Long) {
        dao.markSynced(batchId, at)
    }

    /**
     * Prunes outbox rows, mirroring GRDB `pruneRaw` (`RawOutbox.swift`) exactly. Returns the number of
     * `outboxBatch` rows deleted. Runs both policies in ONE transaction, exactly like the Swift
     * `syncWrite` closure.
     *
     * **Policy 1:** delete SYNCED batches whose `syncedAt` is older than `now - keepWindowSeconds`.
     * Synced raw is safe to drop: the decoded streams are persisted separately (before the raw batch is
     * even enqueued — the Collector/Backfiller E2 invariant).
     *
     * **Policy 2 (byte-cap eviction, GRDB #27):** cap the total on-disk raw footprint at
     * [maxUnsyncedBytes]. Walk the SURVIVING batches newest-first (`capturedAt DESC`, `rowid DESC`
     * tiebreak), summing `byteSize`; once the running total exceeds the cap, delete every row from there
     * on (the oldest raw). **This applies to synced AND unsynced batches alike** — despite the parameter
     * name `maxUnsyncedBytes` (kept identical to the Swift signature), the eviction scan is NOT filtered
     * by `syncedAt`. Raw is transient working data, not an archive: because decoded streams persist
     * BEFORE the raw batch is enqueued, dropping the oldest raw — synced or not — never loses a decoded
     * metric. This is confirmed by the Swift `pruneRaw` implementation and pinned by its own
     * `testEvictionAppliesToSyncedAndUnsyncedAlike` (`Packages/WhoopStore/Tests/WhoopStoreTests/
     * PruneTests.swift`) — a prior read of this task's carry-over note (which paraphrased Policy 2 as
     * "never dropping unsynced rows") did not match the actual source; the Swift source and its own test
     * suite are the ground truth this port follows.
     *
     * @param now current unix-second timestamp used to compute the synced-aging cutoff.
     * @param keepWindowSeconds synced batches older than `now - keepWindowSeconds` are removed.
     * @param maxUnsyncedBytes total raw-footprint cap; oldest batches beyond it are evicted regardless of
     *   sync status (see above).
     */
    suspend fun prune(now: Long, keepWindowSeconds: Long, maxUnsyncedBytes: Long): Int = transactor.run {
        var pruned = 0
        val cutoff = now - keepWindowSeconds
        pruned += dao.pruneSyncedBefore(cutoff)

        val newestFirst = dao.byteSizesNewestFirst()
        var cumulative = 0L
        val evict = mutableListOf<Long>()
        for (row in newestFirst) {
            cumulative += row.byteSize
            if (cumulative > maxUnsyncedBytes) evict += row.rowId
        }
        if (evict.isNotEmpty()) pruned += dao.deleteByRowIds(evict)
        pruned
    }

    // -- Cursors: Kotlin twins of Cursors.swift, same method names, same cursor name-string prefixes. --

    /** Sets a named cursor's value (insert-or-update-latest). */
    suspend fun setCursor(name: String, value: Long) = dao.upsertCursor(OutboxCursorRow(name, value))

    /** A named cursor's current value, or null if never written. */
    suspend fun cursor(name: String): Long? = dao.cursorValue(name)

    /** Upload watermark for `stream` — cursor name `"highwater:" + stream`. */
    suspend fun setHighwater(stream: String, ts: Long) = setCursor("highwater:$stream", ts)

    /** Reads the upload watermark for `stream`. */
    suspend fun highwater(stream: String): Long? = cursor("highwater:$stream")

    /**
     * Server-pull watermark for `stream` — cursor name `"read:" + stream`, a DISTINCT prefix so the pull
     * cursor never collides with the upload `highwater:` cursor for the same stream.
     */
    suspend fun setReadHighwater(stream: String, ts: Long) = setCursor("read:$stream", ts)

    /** Reads the server-pull watermark for `stream`. */
    suspend fun readHighwater(stream: String): Long? = cursor("read:$stream")
}
