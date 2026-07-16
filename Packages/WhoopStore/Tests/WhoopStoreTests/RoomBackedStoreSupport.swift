import Foundation
import XCTest
@testable import WhoopStore

/// Task 4 (streams delegation) test harness: builds a WhoopStore whose `backend` is `.room`, so the
/// existing streams suites (Insert/Read/Biometric/PpgHr/Step/SleepState/LatestSample) exercise the
/// Room delegation paths with their assertions UNCHANGED. Uses the fresh-install branch of
/// `detectMigrateOpen`: a unique empty temp dir means no legacy file and no Room file, so the store
/// opens shared Room directly (no ETL) and every data method routes through the Kotlin DAO.
///
/// Phase 2c-2 Task 4 update: rawBatch/cursors no longer stay on the legacy GRDB pool for a store
/// built here — `enqueueRawBatch`/`rawFrames`/`pendingRawBatches`/`markRawBatchSynced`/`pruneRaw` and
/// `setCursor`/`cursor` (plus the `highwater`/`readHighwater` prefix wrappers) now route through
/// `OutboxBridge` onto this store's own Room `outboxBatch`/`outboxCursor` tables instead. See
/// `RoomBackedRawOutboxTests.swift` for that coverage, which uses this same helper.
///
/// GRDB-removal Task 4: `WhoopStore.inMemory()` itself now builds exactly this store (temp dir →
/// fresh-install Room open, guarded against a legacy fallback), so this helper is a thin delegate
/// kept only for its established name in the streams suites. The `LegacyGrdb*PinTests` that used
/// to pin the GRDB branch are deleted — there is no legacy branch left to pin.
extension WhoopStore {
    /// A fresh Room-backed store in its own temp dir. Delegates to `inMemory()` (same pattern,
    /// same guard: throws if the open somehow fell back to legacy, so a broken cutover can never
    /// silently run these suites against GRDB).
    static func roomBackedForTest() async throws -> WhoopStore {
        try await WhoopStore.inMemory()
    }
}
