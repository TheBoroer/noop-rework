import Foundation
import Compression
import GRDB
import WhoopProtocol

public struct ClockRef: Equatable, Codable {
    public let device: Int
    public let wall: Int
    public init(device: Int, wall: Int) { self.device = device; self.wall = wall }
}

public struct RawBatchMeta: Equatable {
    public let batchId: String
    public let deviceId: String
    public let clockRef: ClockRef
    public let capturedAt: Int
    public let startTs: Int
    public let endTs: Int
    public let frameCount: Int
    public let byteSize: Int
    public init(batchId: String, deviceId: String, clockRef: ClockRef, capturedAt: Int,
                startTs: Int, endTs: Int, frameCount: Int, byteSize: Int) {
        self.batchId = batchId; self.deviceId = deviceId; self.clockRef = clockRef
        self.capturedAt = capturedAt; self.startTs = startTs; self.endTs = endTs
        self.frameCount = frameCount; self.byteSize = byteSize
    }
}

extension WhoopStore {
    // MARK: - frame (de)serialization
    // Layout: [count u32 LE]{ [len u32 LE][bytes] } x count. zlib-compressed as a whole.

    static func packFrames(_ frames: [[UInt8]]) -> Data {
        var buf = Data()
        func appendU32(_ v: Int) {
            let u = UInt32(v)
            buf.append(UInt8(u & 0xFF)); buf.append(UInt8((u >> 8) & 0xFF))
            buf.append(UInt8((u >> 16) & 0xFF)); buf.append(UInt8((u >> 24) & 0xFF))
        }
        appendU32(frames.count)
        for f in frames {
            appendU32(f.count)
            buf.append(contentsOf: f)
        }
        return buf
    }

    static func unpackFrames(_ data: Data) -> [[UInt8]] {
        let bytes = [UInt8](data)
        var off = 0
        func readU32() -> Int? {
            guard off + 4 <= bytes.count else { return nil }
            let v = Int(bytes[off]) | (Int(bytes[off + 1]) << 8)
                | (Int(bytes[off + 2]) << 16) | (Int(bytes[off + 3]) << 24)
            off += 4
            return v
        }
        guard let count = readU32() else { return [] }
        var out: [[UInt8]] = []
        out.reserveCapacity(count)
        for _ in 0..<count {
            guard let len = readU32(), off + len <= bytes.count else { break }
            out.append(Array(bytes[off..<off + len]))
            off += len
        }
        return out
    }

    // MARK: - zlib helpers using Apple Compression framework

    /// Decompress a blob that was produced by `zlibCompressWithLength`.
    /// The first 4 bytes are the uncompressed length (UInt32 LE); the rest is the zlib payload.
    static func zlibDecompressWithLength(_ input: Data) throws -> Data {
        // Read the 4-byte uncompressed-length prefix (UInt32 LE).
        guard input.count >= 4 else { throw CocoaError(.fileReadUnknown) }
        let n = Int(input[input.startIndex])
            | (Int(input[input.startIndex + 1]) << 8)
            | (Int(input[input.startIndex + 2]) << 16)
            | (Int(input[input.startIndex + 3]) << 24)
        let compressed = input.dropFirst(4)
        // n == 0 means packFrames returned empty data; return empty.
        guard n > 0 else { return Data() }
        var dst = [UInt8](repeating: 0, count: n)
        let written: Int = compressed.withUnsafeBytes { src in
            guard let srcPtr = src.baseAddress else { return 0 }
            return compression_decode_buffer(&dst, n, srcPtr, compressed.count, nil, COMPRESSION_ZLIB)
        }
        // If written != n the blob is genuinely corrupt (not a sizing issue).
        guard written == n else { throw CocoaError(.fileReadCorruptFile) }
        return Data(dst)
    }

    /// Compress `input` and prepend its uncompressed length as a UInt32 LE prefix.
    static func zlibCompressWithLength(_ input: Data) throws -> Data {
        let sourceSize = input.count
        let dstCapacity = max(64, sourceSize * 2 + 64)
        var dst = [UInt8](repeating: 0, count: dstCapacity)
        let written: Int = input.withUnsafeBytes { src in
            guard let srcPtr = src.baseAddress else { return 0 }
            return compression_encode_buffer(&dst, dstCapacity, srcPtr, sourceSize, nil, COMPRESSION_ZLIB)
        }
        guard written > 0 else { throw CocoaError(.fileWriteUnknown) }
        // Prepend uncompressed length as UInt32 LE.
        let u = UInt32(sourceSize)
        var blob = Data(capacity: 4 + written)
        blob.append(UInt8(u & 0xFF)); blob.append(UInt8((u >> 8) & 0xFF))
        blob.append(UInt8((u >> 16) & 0xFF)); blob.append(UInt8((u >> 24) & 0xFF))
        blob.append(contentsOf: dst[0..<written])
        return blob
    }

    // MARK: - Public API (Phase 2c-2 Task 4: backend-aware — `.room` routes through `OutboxBridge`
    // over Room's `outboxBatch` table, `.legacyGrdb` keeps the original GRDB SQL byte-for-byte via the
    // `*Grdb` helpers below.)
    //
    // `OutboxBridge(db: room)` is constructed FRESH inside each `.room` case, never stored in actor
    // state: it mirrors the bridge's own documented design (it builds its own `OutboxStore` per call
    // rather than caching one) and means `OutboxBridge`'s `Sendable` status never comes up — there is
    // no stored-property call site to trigger the check. Codec helpers (`packFrames`/
    // `zlibCompressWithLength` and their inverses) stay Swift-side and run identically for both
    // backends; the bridge only ever sees the already-packed, already-compressed `Data` blob.

    /// Compress raw frames into the outbox and store batch meta. Hard Invariant: for `.room`, only a
    /// verified durable insert (`OutboxBridge.outboxEnqueue` returning `true`) returns normally; a
    /// `false` (would mean the post-insert existence check somehow failed) throws
    /// `RawBatchNotPersistedError` rather than returning as if the batch were durably enqueued, and any
    /// thrown Kotlin exception propagates untouched. Callers (Backfiller/Collector, via
    /// `BackfillStoreWriting`/`StoreWriting`) already gate their ack-the-strap signal on this method
    /// throwing vs. returning — this preserves that contract without changing either protocol.
    public func enqueueRawBatch(_ meta: RawBatchMeta, frames: [[UInt8]]) async throws {
        switch backend {
        case .room(let room):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = room; throw RoomBackendUnavailableError()
            #else
            let packed = WhoopStore.packFrames(frames)
            let blob = try WhoopStore.zlibCompressWithLength(packed)
            let persisted = try await OutboxBridge(db: room).outboxEnqueue(meta: meta, framesBlob: blob)
            try WhoopStore.requirePersisted(persisted, batchId: meta.batchId)
            #endif
        case .legacyGrdb:
            try enqueueRawBatchGrdb(meta, frames: frames)
        }
    }

    /// Extracted from what used to be an inline `guard` so the Hard Invariant guard itself (not just
    /// `RawBatchNotPersistedError`'s fields) is directly testable: `OutboxBridge` has no protocol seam
    /// (Task 3/4 deliberately keep it a concrete Kotlin-bridging class — see its doc comment), and the
    /// Kotlin `OutboxStore.enqueue` it wraps always verifies-or-throws in practice, so nothing in this
    /// repo can naturally drive `outboxEnqueue` to return `false`. This static, pure (no I/O, no `self`)
    /// helper is byte-identical to the guard that used to sit inline above — pulling it out changes
    /// nothing about production behavior, it only gives a test a way to call the guard directly with a
    /// forced `true`/`false`.
    static func requirePersisted(_ persisted: Bool, batchId: String) throws {
        guard persisted else {
            throw RawBatchNotPersistedError(batchId: batchId)
        }
    }

    private func enqueueRawBatchGrdb(_ meta: RawBatchMeta, frames: [[UInt8]]) throws {
        let packed = WhoopStore.packFrames(frames)
        let blob = try WhoopStore.zlibCompressWithLength(packed)
        try syncWrite { db in
            try db.execute(sql: """
                INSERT INTO rawBatch
                    (batchId, deviceId, capturedAt, deviceClockRef, wallClockRef,
                     startTs, endTs, frameCount, byteSize, framesBlob, syncedAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                ON CONFLICT(batchId) DO NOTHING
                """, arguments: [
                    meta.batchId, meta.deviceId, meta.capturedAt,
                    meta.clockRef.device, meta.clockRef.wall,
                    meta.startTs, meta.endTs, meta.frameCount, meta.byteSize, blob])
        }
    }

    /// Decompress and return the exact frame bytes for a batch (empty if unknown).
    public func rawFrames(batchId: String) async throws -> [[UInt8]] {
        switch backend {
        case .room(let room):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = room; throw RoomBackendUnavailableError()
            #else
            guard let blob = try await OutboxBridge(db: room).outboxFrames(batchId: batchId) else {
                return []
            }
            let raw = try WhoopStore.zlibDecompressWithLength(blob)
            return WhoopStore.unpackFrames(raw)
            #endif
        case .legacyGrdb:
            return try rawFramesGrdb(batchId: batchId)
        }
    }

    private func rawFramesGrdb(batchId: String) throws -> [[UInt8]] {
        let row: Row? = try syncRead { db in
            try Row.fetchOne(db,
                sql: "SELECT framesBlob FROM rawBatch WHERE batchId = ?",
                arguments: [batchId])
        }
        guard let row = row else { return [] }
        let blob: Data = row["framesBlob"]
        let raw = try WhoopStore.zlibDecompressWithLength(blob)
        return WhoopStore.unpackFrames(raw)
    }

    private static func metaFromRow(_ row: Row) -> RawBatchMeta {
        RawBatchMeta(
            batchId: row["batchId"], deviceId: row["deviceId"],
            clockRef: ClockRef(device: row["deviceClockRef"], wall: row["wallClockRef"]),
            capturedAt: row["capturedAt"], startTs: row["startTs"], endTs: row["endTs"],
            frameCount: row["frameCount"], byteSize: row["byteSize"])
    }

    /// Un-synced batches (syncedAt IS NULL), oldest first, capped at `limit`.
    public func pendingRawBatches(limit: Int) async throws -> [RawBatchMeta] {
        switch backend {
        case .room(let room):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = room; throw RoomBackendUnavailableError()
            #else
            return try await OutboxBridge(db: room).outboxPending(limit: limit)
            #endif
        case .legacyGrdb:
            return try pendingRawBatchesGrdb(limit: limit)
        }
    }

    private func pendingRawBatchesGrdb(limit: Int) throws -> [RawBatchMeta] {
        try syncRead { db in
            try Row.fetchAll(db, sql: """
                SELECT batchId, deviceId, capturedAt, deviceClockRef, wallClockRef,
                       startTs, endTs, frameCount, byteSize
                FROM rawBatch
                WHERE syncedAt IS NULL
                ORDER BY capturedAt ASC
                LIMIT ?
                """, arguments: [limit]).map(WhoopStore.metaFromRow)
        }
    }

    /// Mark a batch synced (timestamp in unix seconds).
    public func markRawBatchSynced(batchId: String, at: Int) async throws {
        switch backend {
        case .room(let room):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = room; throw RoomBackendUnavailableError()
            #else
            try await OutboxBridge(db: room).outboxMarkSynced(batchId: batchId, at: at)
            #endif
        case .legacyGrdb:
            try markRawBatchSyncedGrdb(batchId: batchId, at: at)
        }
    }

    private func markRawBatchSyncedGrdb(batchId: String, at: Int) throws {
        try syncWrite { db in
            try db.execute(sql: "UPDATE rawBatch SET syncedAt = ? WHERE batchId = ?",
                           arguments: [at, batchId])
        }
    }
}

/// Thrown by `enqueueRawBatch`'s `.room` branch when `OutboxBridge.outboxEnqueue` returns `false`
/// (the post-insert existence check somehow found the row absent) rather than throwing. The Hard
/// Invariant (persist-before-ack) requires callers to gate their trim-cursor ack on this method
/// returning normally; silently treating a `false` as success would let an ack proceed against data
/// that isn't actually durable, so it is surfaced as a thrown error instead. In practice this should
/// never fire — `OutboxStore.enqueue` on the Kotlin side either verifies the row present (returns
/// `true`) or throws — but the bridge's return type is `Bool`, not `Never`-on-failure, so this method
/// must not silently swallow a `false`.
public struct RawBatchNotPersistedError: Error, CustomStringConvertible {
    public let batchId: String
    public var description: String {
        "Outbox enqueue for batch \(batchId) returned false (not verified durable); ack must not proceed."
    }
}

extension WhoopStore {
    /// Prune raw outbox rows. Returns the number of rawBatch rows deleted.
    ///
    /// **Policy 1:** Delete SYNCED batches whose `syncedAt` timestamp is older than
    /// `now - keepWindowSeconds`. Synced raw is safe to drop because the decoded streams
    /// are persisted separately.
    ///
    /// **Policy 2 (size eviction, #27):** Cap the total on-disk raw footprint at
    /// `maxUnsyncedBytes`. Walk the surviving batches newest-first (`capturedAt DESC`),
    /// summing `byteSize`, and DELETE every row once the running total exceeds the cap —
    /// i.e. drop the OLDEST raw. Raw is transient working data, not an archive: the decoded
    /// streams are persisted BEFORE the raw batch is enqueued (Collector E2 invariant), so
    /// dropping the oldest raw never loses a decoded metric. Without this an experimental
    /// capture toggle could grow local storage without bound (a 5/MG user saw 19 GB).
    ///
    /// - Parameters:
    ///   - now: Current unix-second timestamp used to compute the synced-aging cutoff.
    ///   - keepWindowSeconds: Synced batches older than `now - keepWindowSeconds` are removed.
    ///   - maxUnsyncedBytes: Total raw-footprint cap; oldest batches beyond it are evicted.
    ///
    /// Backend-aware (Phase 2c-2 Task 4): `.room` routes through `OutboxBridge.outboxPrune`, which
    /// wraps the Kotlin `OutboxStore.prune` running the identical two policies in one Room
    /// transaction (see that method's doc comment for the GRDB-parity confirmation); `.legacyGrdb`
    /// keeps the exact SQL below.
    @discardableResult
    public func pruneRaw(now: Int, keepWindowSeconds: Int, maxUnsyncedBytes: Int) async throws -> Int {
        switch backend {
        case .room(let room):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = room; throw RoomBackendUnavailableError()
            #else
            return try await OutboxBridge(db: room).outboxPrune(
                now: now, keepWindowSeconds: keepWindowSeconds, maxUnsyncedBytes: maxUnsyncedBytes)
            #endif
        case .legacyGrdb:
            return try pruneRawGrdb(now: now, keepWindowSeconds: keepWindowSeconds,
                                    maxUnsyncedBytes: maxUnsyncedBytes)
        }
    }

    private func pruneRawGrdb(now: Int, keepWindowSeconds: Int, maxUnsyncedBytes: Int) throws -> Int {
        try syncWrite { db in
            var pruned = 0
            // Policy 1: aged synced batches.
            let cutoff = now - keepWindowSeconds
            try db.execute(sql: """
                DELETE FROM rawBatch WHERE syncedAt IS NOT NULL AND syncedAt < ?
                """, arguments: [cutoff])
            pruned += db.changesCount

            // Policy 2 (#27): size-based eviction of the OLDEST raw beyond the cap. Sum byteSize
            // newest-first; once the cumulative total exceeds maxUnsyncedBytes, the remaining (older)
            // rows are over budget and get dropped. rowid keeps the cutoff stable regardless of ties
            // on capturedAt. Decoded streams persist before raw (E2), so this loses no metric.
            let rows = try Row.fetchAll(db, sql: """
                SELECT rowid, byteSize FROM rawBatch ORDER BY capturedAt DESC, rowid DESC
                """)
            var cumulative = 0
            var evict: [Int64] = []
            for row in rows {
                let size: Int = row["byteSize"]
                let rowid: Int64 = row["rowid"]
                cumulative += size
                if cumulative > maxUnsyncedBytes {
                    evict.append(rowid)
                }
            }
            if !evict.isEmpty {
                let placeholders = evict.map { _ in "?" }.joined(separator: ",")
                try db.execute(
                    sql: "DELETE FROM rawBatch WHERE rowid IN (\(placeholders))",
                    arguments: StatementArguments(evict))
                pruned += db.changesCount
            }
            return pruned
        }
    }

    // MARK: - Test helper

    /// All batch ids, oldest-first. `.legacyGrdb` returns every row (synced or not), matching the
    /// original GRDB query. `.room` has no bridge method for an unfiltered scan — Task 3 only exposed
    /// `outboxPending` (unsynced-only, see `OutboxBridge`'s doc comment) — so this falls back to that,
    /// meaning a `.room`-backed store's result here excludes already-synced batches. That gap is
    /// unexercised in production: nothing in this repo ever calls `markRawBatchSynced`/
    /// `outboxMarkSynced` outside tests (grep confirms). Tests that exercise synced batches
    /// (`PruneTests`) assert survival via `rawFrames(batchId:)` instead of this pending-only scan.
    /// A future Room-backed caller that needs a true unfiltered scan will need a new Kotlin/bridge
    /// method; out of scope for #65 T4 (Swift-only).
    public func allBatchIdsForTest() async throws -> [String] {
        switch backend {
        case .room(let room):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = room; throw RoomBackendUnavailableError()
            #else
            return try await OutboxBridge(db: room).outboxPending(limit: Int(Int32.max)).map(\.batchId)
            #endif
        case .legacyGrdb:
            return try allBatchIdsForTestGrdb()
        }
    }

    private func allBatchIdsForTestGrdb() throws -> [String] {
        try syncRead { db in
            try String.fetchAll(db, sql: "SELECT batchId FROM rawBatch ORDER BY capturedAt ASC")
        }
    }
}
