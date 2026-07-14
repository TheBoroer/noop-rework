import Shared
import Foundation
import WhoopProtocol

/// Bridge marker proving the Kotlin shared framework links into this package.
/// Real delegation lands file by file; see the unification design doc.
enum SharedStoreBridge {
    /// Kotlin: com.noop.update.UpdateCheck-adjacent pure helper is NOT exposed;
    /// use a trivially pure shared symbol to pin linkage.
    static func kotlinLinkProbe() -> String {
        // SharedSmoke lives in commonMain since Phase 1 Task 4.
        SharedSmoke.shared.MODULE
    }
}

// MARK: - Outbox bridge (Phase 2c-2 Task 3)

/// Swift-facing bridge over the Kotlin `OutboxStore` (Tasks 1-2, `shared/src/commonMain/kotlin/
/// com/noop/data/OutboxStore.kt`), the Room replacement for the GRDB outbox (`RawOutbox.swift` +
/// `Cursors.swift`). One `OutboxBridge` wraps ONE live Kotlin `WhoopDatabase` handle; every method
/// builds its own `OutboxStore` (a stateless facade over the handle's DAO plus write-transaction
/// boundary, cheap to construct per call by design, see the Kotlin type's own doc comment) rather
/// than caching one, so `db` itself is the only thing that needs guarding against the phantom x86_64
/// iOS-Simulator slice below.
///
/// Task 4 repointed `RawOutbox.swift`/`Cursors.swift`/`Reads.swift`'s `.room` branches onto this
/// bridge (which in turn repoints `Collector`/`Backfiller`/diagnostics, since they only ever call
/// through those `WhoopStore` methods): each method constructs `OutboxBridge(db: room)` FRESH,
/// scoped to the call, using the `WhoopDatabase` handle already carried by the actor's own
/// `.room(WhoopDatabase)` backend case — nothing stores an `OutboxBridge` in actor (or any other)
/// state. This mirrors the bridge's own per-call `OutboxStore(db:)` construction one level up, and
/// sidesteps the `Sendable` question below entirely: there is no stored-property call site to ever
/// trigger the compiler's check.
///
/// The frame-blob codec (`packFrames`/`unpackFrames`, `zlibCompressWithLength`/
/// `zlibDecompressWithLength`, still in `RawOutbox.swift`) stays Swift-side per the plan: this bridge
/// treats `framesBlob` as opaque `Data`, exactly like the Kotlin `OutboxStore` treats its `ByteArray`
/// as opaque. Callers pack and compress before `outboxEnqueue`, and decompress and unpack after
/// `outboxFrames`.
///
/// Cursor name strings ("strap_trim", "highwater:"+stream, "read:"+stream) pass through UNTOUCHED:
/// this bridge does not know or apply the prefixing convention (see `Cursors.swift` for the
/// Swift-side wrappers that do, over the legacy GRDB store).
///
/// Not declared `Sendable`: it holds a Kotlin/Native-bridged `WhoopDatabase` reference whose own
/// `Sendable` status is not asserted anywhere in the xcframework's generated interface, and no
/// package in this repo enables strict concurrency checking (`swift-tools-version: 5.9`
/// everywhere), so there is nothing here for the compiler to enforce yet either way. Task 4 resolved
/// this by never storing an `OutboxBridge` in actor state at all (see above) — it only ever exists
/// as a function-local value inside one `WhoopStore` method body, constructed and dropped within a
/// single `await`, so no cross-isolation-domain hop ever needs it to be `Sendable`.
public final class OutboxBridge {
    private let db: WhoopDatabase

    /// `db` is a live Kotlin `WhoopDatabase` handle: `WhoopStore`'s `.room(WhoopDatabase)` backend
    /// case in production, or `whoopDatabase(path:)` directly in tests (see `OutboxBridgeTests`).
    public init(db: WhoopDatabase) {
        self.db = db
    }

    /// Insert-or-already-present, keyed on `meta.batchId`: the Hard Invariant's commit signal. Only
    /// ack the strap's trim cursor after this returns `true`, never on enqueue-attempt alone. `true`
    /// means the row is VERIFIED durably present, whether this call performed the insert or an
    /// earlier/concurrent caller already had, matching the Kotlin `OutboxStore.enqueue` contract
    /// exactly: a Kotlin exception propagates as a Swift `throw`, never swallowed into a `false`.
    ///
    /// `meta` (Swift `RawBatchMeta`) has no `syncedAt` field, so there is nothing to carry across:
    /// Kotlin's own `enqueue` independently hardcodes `syncedAt = null` on every insert regardless of
    /// what a caller passes, so a newly-enqueued batch is always unsynced on both sides.
    public func outboxEnqueue(meta: RawBatchMeta, framesBlob: Data) async throws -> Bool {
        #if targetEnvironment(simulator) && arch(x86_64)
        throw WhoopStore.RoomBackendUnavailableError()
        #else
        let persisted = try await OutboxStore(db: db).enqueue(
            meta: Self.toKotlin(meta),
            framesBlob: framesBlob.toKotlinByteArray()
        )
        return Bool(truncating: persisted)
        #endif
    }

    /// Un-synced batches, oldest-first, capped at `limit`: the Kotlin twin of Swift
    /// `pendingRawBatches`.
    public func outboxPending(limit: Int) async throws -> [RawBatchMeta] {
        #if targetEnvironment(simulator) && arch(x86_64)
        throw WhoopStore.RoomBackendUnavailableError()
        #else
        return try await OutboxStore(db: db).pending(limit: Int32(limit)).map(Self.toSwift)
        #endif
    }

    /// The raw (still packed and zlib-compressed) frame payload for `batchId`, or `nil` if no such
    /// batch exists: the Kotlin twin of Swift `rawFrames`, except this returns the blob itself rather
    /// than pre-decoding it (decode is the caller's job, via `unpackFrames`/
    /// `zlibDecompressWithLength`).
    public func outboxFrames(batchId: String) async throws -> Data? {
        #if targetEnvironment(simulator) && arch(x86_64)
        throw WhoopStore.RoomBackendUnavailableError()
        #else
        return try await OutboxStore(db: db).framesBlob(batchId: batchId)?.toData()
        #endif
    }

    /// Marks a batch uploaded at `at` (caller-supplied unix-second timestamp): the Kotlin twin of
    /// Swift `markRawBatchSynced`. A no-op (not an error) if `batchId` is unknown.
    public func outboxMarkSynced(batchId: String, at: Int) async throws {
        #if targetEnvironment(simulator) && arch(x86_64)
        throw WhoopStore.RoomBackendUnavailableError()
        #else
        try await OutboxStore(db: db).markSynced(batchId: batchId, at: Int64(at))
        #endif
    }

    /// Prunes outbox rows (both eviction policies; see the Kotlin `OutboxStore.prune` doc comment for
    /// the exact GRDB-parity semantics, including Policy 2's byte-cap eviction applying to synced AND
    /// unsynced batches alike, despite the `maxUnsyncedBytes` parameter name). Returns the number of
    /// rows deleted.
    @discardableResult
    public func outboxPrune(now: Int, keepWindowSeconds: Int, maxUnsyncedBytes: Int) async throws -> Int {
        #if targetEnvironment(simulator) && arch(x86_64)
        throw WhoopStore.RoomBackendUnavailableError()
        #else
        let pruned = try await OutboxStore(db: db).prune(
            now: Int64(now), keepWindowSeconds: Int64(keepWindowSeconds),
            maxUnsyncedBytes: Int64(maxUnsyncedBytes)
        )
        return Int(truncating: pruned)
        #endif
    }

    /// Sets a named cursor's value (insert-or-update-latest). `name` passes through untouched.
    public func outboxSetCursor(_ name: String, _ value: Int) async throws {
        #if targetEnvironment(simulator) && arch(x86_64)
        throw WhoopStore.RoomBackendUnavailableError()
        #else
        try await OutboxStore(db: db).setCursor(name: name, value: Int64(value))
        #endif
    }

    /// A named cursor's current value, or `nil` if never written. `name` passes through untouched.
    public func outboxCursor(_ name: String) async throws -> Int? {
        #if targetEnvironment(simulator) && arch(x86_64)
        throw WhoopStore.RoomBackendUnavailableError()
        #else
        return try await OutboxStore(db: db).cursor(name: name).map { Int(truncating: $0) }
        #endif
    }

    // MARK: - Swift <-> Kotlin meta mapping

    private static func toKotlin(_ meta: RawBatchMeta) -> OutboxBatchMeta {
        OutboxBatchMeta(
            batchId: meta.batchId, deviceId: meta.deviceId,
            capturedAt: Int64(meta.capturedAt),
            deviceClockRef: Int64(meta.clockRef.device), wallClockRef: Int64(meta.clockRef.wall),
            startTs: Int64(meta.startTs), endTs: Int64(meta.endTs),
            frameCount: Int32(meta.frameCount), byteSize: Int32(meta.byteSize),
            syncedAt: nil
        )
    }

    private static func toSwift(_ row: OutboxBatchMeta) -> RawBatchMeta {
        RawBatchMeta(
            batchId: row.batchId, deviceId: row.deviceId,
            clockRef: ClockRef(device: Int(row.deviceClockRef), wall: Int(row.wallClockRef)),
            capturedAt: Int(row.capturedAt), startTs: Int(row.startTs), endTs: Int(row.endTs),
            frameCount: Int(row.frameCount), byteSize: Int(row.byteSize)
        )
    }
}
