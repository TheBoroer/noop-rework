import Foundation

/// Phase 2c-2 Task 6: the one-shot that folds a legacy GRDB `whoop.sqlite` raw outbox — its pending
/// `rawBatch` rows plus the cursors worth keeping — into the live Room outbox, then archives the GRDB
/// file. The Room outbox (Tasks 1-4) is now the sole outbox for every WRITE; this closes the loop for a
/// device upgrading across the cutover, whose un-uploaded batches and last-known cursors were still
/// sitting in the GRDB file the Room store no longer writes to.
///
/// This is the testable core, deliberately placed in the WhoopStore package rather than the app target
/// (`Strand/Data/OutboxDrain.swift` is the thin production wrapper: it resolves the legacy path, adds
/// the app-level one-shot guard, and calls `run` below). Keeping the core here lets it run under
/// `swift test` against a real Room-backed store, since the app's own test target can't run yet (the
/// known project.yml TEST_HOST blocker).
///
/// **Opaque blobs.** The pending-batch payloads are opaque to this code end to end: `copyOutbox` reads
/// each batch's frames back through the store's existing `rawFrames` (which unpacks the store's own
/// `packFrames`+zlib framing) and re-enqueues them through `enqueueRawBatch` (which re-packs with the
/// identical codec). It never inspects, decodes further, or logs payload bytes.
///
/// **Idempotency.** Every step is safe to repeat, because a crash between them must be recoverable by
/// simply re-running on the next launch:
///  - `enqueueRawBatch` is keyed on `batchId` and conflict-ignored, so re-enqueuing an already-folded
///    batch is a no-op (the row set never duplicates).
///  - cursor copies are guarded UNSET-ONLY: a cursor is copied only when Room has no value for it yet,
///    so a re-run never clobbers a newer value the app wrote post-cutover.
///  - the rename is the done-marker (`noop-drained-<ts>.sqlite`, matching the `noop-replaced-<ts>`
///    philosophy: recoverable, never deleted). Until it happens, the legacy file is still present and a
///    re-run repeats the (idempotent) copy before renaming again.
public enum OutboxDrain {
    /// What one `run` did. `existed == false` is the fresh-install / already-drained no-op (no legacy
    /// file was present, nothing was opened, nothing archived).
    public struct Result: Equatable {
        public let existed: Bool
        public let batchesDrained: Int
        public let cursorsCopied: Int
        public let archivedURL: URL?
    }

    /// The cursors worth carrying across: the strap trim high-water (`strap_trim`) and every per-stream
    /// upload/read high-water (`highwater:*` / `read:*`). Anything else in the legacy `cursors` table is
    /// intentionally left behind.
    static func isKeepCursor(_ name: String) -> Bool {
        name == "strap_trim" || name.hasPrefix("highwater:") || name.hasPrefix("read:")
    }

    /// Full one-shot: no-op if the legacy file is absent; otherwise open it as a pure `.legacyGrdb`
    /// store (via `openLegacy`), fold its pending batches + keep-set cursors into `room`, and archive
    /// the GRDB file. `openLegacy`/`now`/`fileManager` are injectable so the package test drives the
    /// whole flow deterministically; production passes `WhoopStore.openLegacyGrdb`.
    public static func run(
        legacyURL: URL,
        room: WhoopStore,
        openLegacy: (URL) throws -> WhoopStore,
        now: () -> Int = { Int(Date().timeIntervalSince1970) },
        fileManager: FileManager = .default
    ) async throws -> Result {
        // Fresh install / already drained: the file-rename below removes `whoop.sqlite`, so its absence
        // is the "nothing to do" signal. (The app wrapper adds a persisted flag too, because the store's
        // own open recreates an empty `whoop.sqlite` every launch while GRDB code is still in tree.)
        guard fileManager.fileExists(atPath: legacyURL.path) else {
            return Result(existed: false, batchesDrained: 0, cursorsCopied: 0, archivedURL: nil)
        }
        let counts = try await drainReleasingSource(legacyURL: legacyURL, openLegacy: openLegacy, room: room)
        let archived = try archiveLegacyFile(at: legacyURL, now: now, fileManager: fileManager)
        return Result(existed: true, batchesDrained: counts.batches,
                      cursorsCopied: counts.cursors, archivedURL: archived)
    }

    /// Opens the legacy source, copies, checkpoints, and lets the source pool go out of scope BEFORE
    /// the caller renames the file — so the archived `.sqlite` is self-contained (WAL folded in) and no
    /// live pool is mid-write on the inode being renamed.
    private static func drainReleasingSource(
        legacyURL: URL, openLegacy: (URL) throws -> WhoopStore, room: WhoopStore
    ) async throws -> (batches: Int, cursors: Int) {
        let legacy = try openLegacy(legacyURL)
        let counts = try await copyOutbox(from: legacy, to: room)
        // Fold the WAL into the main file so the file we archive next carries everything. Best-effort:
        // a checkpoint failure doesn't invalidate the copy that already succeeded.
        try? await legacy.checkpointWAL()
        return counts
    }

    /// Copies the legacy raw outbox into Room, idempotently. Returns how many batches were seen and how
    /// many cursors were freshly copied this pass (a re-run reports 0 fresh cursor copies once Room
    /// already holds them). Split out from `run` so the crash-before-rename idempotency case is directly
    /// testable: call it twice, then archive.
    static func copyOutbox(from legacy: WhoopStore, to room: WhoopStore) async throws -> (batches: Int, cursors: Int) {
        // Pending (un-synced) batches only: synced batches were already uploaded and need no re-enqueue.
        // A single large page — the outbox is device-local working data, never an unbounded archive.
        let pending = try await legacy.pendingRawBatches(limit: Int(Int32.max))
        for meta in pending {
            let frames = try await legacy.rawFrames(batchId: meta.batchId)
            // Idempotent: conflict-ignored on `batchId`, so a re-run re-enqueuing the same batch is a
            // no-op and the Room row set never duplicates.
            try await room.enqueueRawBatch(meta, frames: frames)
        }

        var cursorsCopied = 0
        let names = try await legacy.legacyCursorNames()
        for name in names where isKeepCursor(name) {
            // UNSET-ONLY guard: never overwrite a value Room already holds — that value is either this
            // drain's own earlier (crashed) pass or, more importantly, a NEWER value the app wrote after
            // cutover. Either way the legacy value must not win.
            let existing = try await room.cursor(name)
            guard existing == nil else { continue }
            guard let value = try await legacy.cursor(name) else { continue }   // NULL legacy value: nothing to carry.
            try await room.setCursor(name, value)
            cursorsCopied += 1
        }
        return (pending.count, cursorsCopied)
    }

    /// Renames `whoop.sqlite` (+ its `-wal`/`-shm` siblings) to `noop-drained-<ts>.sqlite` in the same
    /// directory — the recoverable done-marker. The main-file move is the atomic commit point; the
    /// sidecar moves are best-effort (a missing or locked WAL/SHM must not fail the drain — the archived
    /// main file is already self-contained after `drainReleasingSource`'s checkpoint). Returns the
    /// archived main-file URL.
    ///
    /// Note (resolved by Task 7): while GRDB read code is still in tree, the live `.room` store keeps its
    /// own `DatabasePool` open on this same `whoop.sqlite` (its now-vestigial `dbWriter`), so this rename
    /// happens out from under an open — but idle — pool. That is safe on Darwin (POSIX `rename` on an
    /// open fd keeps the fd valid) and the pool is write-idle for a `.room` store (the outbox and decoded
    /// reads both route to Room); the only transient effect is `databaseFileSizeBytes` reading a stale
    /// path for the rest of the session, self-correcting next launch. Task 7 stops opening the legacy
    /// pool for a `.room` store, removing the overlap entirely.
    static func archiveLegacyFile(at legacyURL: URL, now: () -> Int, fileManager fm: FileManager) throws -> URL {
        let dir = legacyURL.deletingLastPathComponent()
        let dest = dir.appendingPathComponent("noop-drained-\(now()).sqlite")
        if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
        try fm.moveItem(at: legacyURL, to: dest)
        for suffix in ["-wal", "-shm"] {
            let src = URL(fileURLWithPath: legacyURL.path + suffix)
            guard fm.fileExists(atPath: src.path) else { continue }
            let sidecarDest = URL(fileURLWithPath: dest.path + suffix)
            if fm.fileExists(atPath: sidecarDest.path) { try? fm.removeItem(at: sidecarDest) }
            try? fm.moveItem(at: src, to: sidecarDest)
        }
        return dest
    }
}
