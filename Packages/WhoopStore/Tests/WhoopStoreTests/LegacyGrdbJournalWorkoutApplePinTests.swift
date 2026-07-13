import XCTest
@testable import WhoopStore

/// Task 5: the PINNED legacy-mode subset for JournalWorkoutAppleCache.swift. JournalWorkoutAppleCacheTests
/// now runs against the ROOM backend; this file keeps the GRDB fallback branch of every delegated method
/// covered by running a condensed but representative slice of the same assertions against
/// `WhoopStore.inMemory()`, which is always `.legacyGrdb`.
final class LegacyGrdbJournalWorkoutApplePinTests: XCTestCase {

    private func legacyStore() async throws -> WhoopStore {
        let store = try await WhoopStore.inMemory()
        guard case .legacyGrdb = store.storageBackend else {
            XCTFail("inMemory() must be the legacy GRDB backend")
            throw XCTSkip("unreachable")
        }
        return store
    }

    /// Journal upsert idempotency (conflict updates in place), source-scoped delete (an identical
    /// (day, question) under a different deviceId survives), and numericValue round-trip.
    func testLegacyJournalUpsertDeleteAndNumericValue() async throws {
        let store = try await legacyStore()
        let e = JournalEntry(day: "2026-05-23", question: "Caffeine (mg)", answeredYes: true,
                             notes: "two cups", numericValue: 180)
        try await store.upsertJournal([e], deviceId: "noop-journal")
        try await store.upsertJournal([e], deviceId: "my-whoop")

        var rows = try await store.journalEntries(deviceId: "noop-journal", from: "2026-05-01", to: "2026-05-31")
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].numericValue, 180)

        // Re-upsert same natural key with changed values → no duplicate, value updated.
        let e2 = JournalEntry(day: "2026-05-23", question: "Caffeine (mg)", answeredYes: false, notes: nil)
        try await store.upsertJournal([e2], deviceId: "noop-journal")
        rows = try await store.journalEntries(deviceId: "noop-journal", from: "2026-05-01", to: "2026-05-31")
        XCTAssertEqual(rows.count, 1, "same (deviceId,day,question) must not duplicate")
        XCTAssertNil(rows[0].numericValue, "overwrite with a plain answer clears numericValue")

        let deleted = try await store.deleteJournal(deviceId: "noop-journal", day: "2026-05-23",
                                                     question: "Caffeine (mg)")
        XCTAssertEqual(deleted, 1)
        let imported = try await store.journalEntries(deviceId: "my-whoop", from: "2026-05-01", to: "2026-05-31")
        XCTAssertEqual(imported.count, 1, "the delete is source-scoped, imported row survives")
    }

    /// replaceJournalRange atomically clears [from, to] then upserts rows in one transaction, and
    /// never touches journal outside the range or under a different deviceId.
    func testLegacyReplaceJournalRangeAtomicAndBounded() async throws {
        let store = try await legacyStore()
        try await store.upsertJournal([
            JournalEntry(day: "2026-05-01", question: "Q", answeredYes: true, notes: "stale-out-of-range"),
            JournalEntry(day: "2026-05-10", question: "Q", answeredYes: true, notes: "stale-in-range"),
        ], deviceId: "my-whoop")
        try await store.upsertJournal([
            JournalEntry(day: "2026-05-10", question: "Q", answeredYes: true, notes: "other-device"),
        ], deviceId: "devB")

        let replaced = try await store.replaceJournalRange([
            JournalEntry(day: "2026-05-10", question: "Q", answeredYes: false, notes: "fresh"),
        ], deviceId: "my-whoop", from: "2026-05-05", to: "2026-05-15")
        XCTAssertEqual(replaced, 1)

        let rows = try await store.journalEntries(deviceId: "my-whoop", from: "2026-05-01", to: "2026-05-31")
        XCTAssertEqual(rows.map { $0.day }, ["2026-05-01", "2026-05-10"], "out-of-range row untouched")
        XCTAssertEqual(rows[1].notes, "fresh", "in-range row replaced")

        let other = try await store.journalEntries(deviceId: "devB", from: "2026-05-01", to: "2026-05-31")
        XCTAssertEqual(other.count, 1, "another device's journal is never touched")
    }

    /// Workout upsert idempotency, same-startTs-different-sport coexistence, range+limit read, and
    /// deleteWorkouts scoped to (deviceId, sport, range).
    func testLegacyWorkoutUpsertRangeLimitAndScopedDelete() async throws {
        let store = try await legacyStore()
        let w = WorkoutRow(startTs: 1_000, endTs: 4_600, sport: "run", source: "apple",
                           durationS: 3600, energyKcal: 520.5, avgHr: 148, maxHr: 176,
                           strain: 12.4, distanceM: 8000, zonesJSON: "{\"z1\":10}", notes: "easy")
        try await store.upsertWorkouts([w], deviceId: "devA")
        var rows = try await store.workouts(deviceId: "devA", from: 0, to: 100_000, limit: 100)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0], w)

        // Re-upsert same natural key with updated values → no duplicate, value updated.
        let w2 = WorkoutRow(startTs: 1_000, endTs: 5_000, sport: "run", source: "whoop",
                            durationS: 4000, energyKcal: 600, avgHr: 150, maxHr: 180,
                            strain: 14.0, distanceM: 9000, zonesJSON: nil, notes: nil)
        try await store.upsertWorkouts([w2], deviceId: "devA")
        rows = try await store.workouts(deviceId: "devA", from: 0, to: 100_000, limit: 100)
        XCTAssertEqual(rows.count, 1, "same (deviceId,startTs,sport) must not duplicate")
        XCTAssertEqual(rows[0], w2)

        // Same startTs, different sport: coexists as a distinct row.
        try await store.upsertWorkouts([
            WorkoutRow(startTs: 1_000, endTs: 1_500, sport: "cycle", source: "apple",
                       durationS: nil, energyKcal: nil, avgHr: nil, maxHr: nil, strain: nil,
                       distanceM: nil, zonesJSON: nil, notes: nil),
            WorkoutRow(startTs: 5_000, endTs: 5_600, sport: "run", source: "devA-noop",
                       durationS: nil, energyKcal: nil, avgHr: nil, maxHr: nil, strain: nil,
                       distanceM: nil, zonesJSON: nil, notes: nil),
        ], deviceId: "devA")
        let all = try await store.workouts(deviceId: "devA", from: 0, to: 100_000, limit: 100)
        XCTAssertEqual(all.count, 3)
        let ranged = try await store.workouts(deviceId: "devA", from: 0, to: 4_000, limit: 100)
        XCTAssertEqual(ranged.map { $0.startTs }.sorted(), [1_000, 1_000])
        let limited = try await store.workouts(deviceId: "devA", from: 0, to: 100_000, limit: 1)
        XCTAssertEqual(limited.count, 1, "limit honoured, oldest first")

        let deleted = try await store.deleteWorkouts(deviceId: "devA", sport: "run", from: 0, to: 4_000)
        XCTAssertEqual(deleted, 1, "only the in-range run row of the matching sport")
        let left = try await store.workouts(deviceId: "devA", from: 0, to: 100_000, limit: 100)
        XCTAssertEqual(left.count, 2, "the cycle row and the out-of-range run row survive")
    }

    /// Apple-Health daily aggregates: upsert idempotency (conflict update), nullable metrics
    /// round-trip as nil, and the day-range filter.
    func testLegacyAppleDailyUpsertNullableAndRangeFilter() async throws {
        let store = try await legacyStore()
        let a = AppleDaily(day: "2026-05-23", steps: 9123, activeKcal: 540.2, basalKcal: 1600.0,
                           vo2max: 48.5, avgHr: 62, maxHr: 171, walkingHr: 98, weightKg: 78.4)
        try await store.upsertAppleDaily([a], deviceId: "devA")
        var rows = try await store.appleDaily(deviceId: "devA", from: "2026-05-01", to: "2026-05-31")
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0], a)

        let a2 = AppleDaily(day: "2026-05-23", steps: nil, activeKcal: nil, basalKcal: nil,
                            vo2max: nil, avgHr: nil, maxHr: nil, walkingHr: nil, weightKg: nil)
        try await store.upsertAppleDaily([a2], deviceId: "devA")
        rows = try await store.appleDaily(deviceId: "devA", from: "2026-05-01", to: "2026-05-31")
        XCTAssertEqual(rows.count, 1, "same (deviceId,day) must not duplicate")
        XCTAssertNil(rows[0].steps, "conflict update clears back to nil")

        try await store.upsertAppleDaily([
            AppleDaily(day: "2026-06-01", steps: 1, activeKcal: nil, basalKcal: nil, vo2max: nil,
                       avgHr: nil, maxHr: nil, walkingHr: nil, weightKg: nil),
        ], deviceId: "devA")
        let mayOnly = try await store.appleDaily(deviceId: "devA", from: "2026-05-01", to: "2026-05-31")
        XCTAssertEqual(mayOnly.map { $0.day }, ["2026-05-23"], "range filter excludes June")
    }
}
