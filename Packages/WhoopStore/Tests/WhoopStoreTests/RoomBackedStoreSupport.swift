import Foundation
import XCTest
@testable import WhoopStore

/// Task 4 (streams delegation) test harness: builds a WhoopStore whose `backend` is `.room`, so the
/// existing streams suites (Insert/Read/Biometric/PpgHr/Step/SleepState/LatestSample) exercise the
/// Room delegation paths with their assertions UNCHANGED. Uses the fresh-install branch of
/// `detectMigrateOpen`: a unique empty temp dir means no legacy file and no Room file, so the store
/// opens shared Room directly (no ETL) and every data method routes through the Kotlin DAO. The
/// legacy GRDB pool still exists beside it (the schema introspection helpers stay on it, by design).
///
/// Phase 2c-2 Task 4 update: rawBatch/cursors no longer stay on the legacy GRDB pool for a store
/// built here — `enqueueRawBatch`/`rawFrames`/`pendingRawBatches`/`markRawBatchSynced`/`pruneRaw` and
/// `setCursor`/`cursor` (plus the `highwater`/`readHighwater` prefix wrappers) now route through
/// `OutboxBridge` onto this store's own Room `outboxBatch`/`outboxCursor` tables instead. See
/// `RoomBackedRawOutboxTests.swift` for that coverage, which uses this same helper.
///
/// The GRDB legacy branch keeps its own coverage via the pinned subset in
/// `LegacyGrdbStreamsPinTests` (which stays on `WhoopStore.inMemory()`, always `.legacyGrdb`).
extension WhoopStore {
    struct WrongBackendError: Error, CustomStringConvertible {
        let got: WhoopStore.StorageBackend
        var description: String { "expected a .room store for the streams suites, got \(got)" }
    }

    /// A fresh Room-backed store in its own temp dir. Throws (fails the test) if the open somehow
    /// fell back to legacy, so a broken cutover can never silently run these suites against GRDB.
    static func roomBackedForTest() async throws -> WhoopStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("noop-room-streams-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = try await WhoopStore(path: dir.appendingPathComponent("whoop.sqlite").path)
        guard store.storageBackend == .room else { throw WrongBackendError(got: store.storageBackend) }
        return store
    }
}
