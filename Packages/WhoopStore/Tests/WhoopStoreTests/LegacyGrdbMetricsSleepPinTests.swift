import XCTest
@testable import WhoopStore

/// Task 5: the PINNED legacy-mode subset for MetricsCache.swift. MetricsCacheTests / SleepMotionStateTests /
/// SleepDeleteUndoStoreTests now run against the ROOM backend; this file keeps the GRDB fallback branch of
/// every delegated method covered by running a condensed but representative slice of the same assertions
/// against `WhoopStore.inMemory()`, which is always `.legacyGrdb` (a real ETL-failure fallback must stay
/// fully functional). If a refactor ever breaks only the legacy branch, this is the net.
final class LegacyGrdbMetricsSleepPinTests: XCTestCase {

    private func legacyStore() async throws -> WhoopStore {
        let store = try await WhoopStore.inMemory()
        guard case .legacyGrdb = store.storageBackend else {
            XCTFail("inMemory() must be the legacy GRDB backend")
            throw XCTSkip("unreachable")
        }
        return store
    }

    /// Sleep-session upsert idempotency, applySleepEdit's onset/wake correction, and the recompute-upsert
    /// guard that preserves a userEdited night's bounds while still refreshing derived vitals.
    func testLegacySleepUpsertEditAndRecomputePreservesUserEditedBounds() async throws {
        let store = try await legacyStore()
        try await store.upsertSleepSessions(
            [CachedSleepSession(startTs: 1000, endTs: 5000, efficiency: 0.90,
                                restingHr: 52, avgHrv: 60, stagesJSON: "[\"orig\"]")],
            deviceId: "devA")
        var rows = try await store.sleepSessions(deviceId: "devA", from: 0, to: 100_000, limit: 100)
        XCTAssertEqual(rows.count, 1)

        // Onset AND wake correction; the detected key stays 1000.
        let changed = try await store.applySleepEdit(deviceId: "devA", detectedStartTs: 1000,
                                                      newStartTs: 1300, newEndTs: 4200)
        XCTAssertEqual(changed, 1)
        rows = try await store.sleepSessions(deviceId: "devA", from: 0, to: 100_000, limit: 100)
        XCTAssertEqual(rows.count, 1, "editing must not create a duplicate row")
        XCTAssertEqual(rows[0].startTsAdjusted, 1300)
        XCTAssertEqual(rows[0].endTs, 4200)
        XCTAssertTrue(rows[0].userEdited)

        // A re-sync recompute (userEdited=false incoming) must preserve the edit but still refresh vitals.
        try await store.upsertSleepSessions(
            [CachedSleepSession(startTs: 1000, endTs: 5000, efficiency: 0.95,
                                restingHr: 49, avgHrv: 71, stagesJSON: "[\"resync\"]")],
            deviceId: "devA")
        rows = try await store.sleepSessions(deviceId: "devA", from: 0, to: 100_000, limit: 100)
        XCTAssertEqual(rows[0].startTsAdjusted, 1300, "onset edit survives re-sync")
        XCTAssertEqual(rows[0].endTs, 4200, "wake edit survives re-sync")
        XCTAssertTrue(rows[0].userEdited)
        XCTAssertEqual(rows[0].efficiency, 0.95, "vitals still refresh")
    }

    /// deleteSleepSession removes the natural-keyed row; insertManualSleepSession is additive-only and a
    /// same-onset insert is a no-op (never clobbers an existing session).
    func testLegacyDeleteAndManualInsertNoopOnConflict() async throws {
        let store = try await legacyStore()
        try await store.upsertSleepSessions(
            [CachedSleepSession(startTs: 9000, endTs: 12_000, efficiency: 0.9,
                                restingHr: 50, avgHrv: 60, stagesJSON: "[\"detected\"]")],
            deviceId: "devA")

        let conflictInsert = try await store.insertManualSleepSession(
            deviceId: "devA", startTs: 9000, endTs: 99_999, efficiency: 0.1, stagesJSON: "[\"nap\"]")
        XCTAssertEqual(conflictInsert, 0, "a same-onset insert is a no-op")

        let napInsert = try await store.insertManualSleepSession(
            deviceId: "devA", startTs: 50_000, endTs: 51_800, efficiency: 0.8, stagesJSON: "[\"nap\"]")
        XCTAssertEqual(napInsert, 1)
        var rows = try await store.sleepSessions(deviceId: "devA", from: 0, to: 100_000, limit: 100)
        XCTAssertEqual(rows.count, 2)

        let deleted = try await store.deleteSleepSession(deviceId: "devA", startTs: 9000)
        XCTAssertEqual(deleted, 1)
        rows = try await store.sleepSessions(deviceId: "devA", from: 0, to: 100_000, limit: 100)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].startTs, 50_000)
    }

    /// updateSleepStages replaces ONLY the stage breakdown of an already user-edited night and is a no-op
    /// on an un-edited row.
    func testLegacyUpdateSleepStagesScopedToUserEdited() async throws {
        let store = try await legacyStore()
        try await store.upsertSleepSessions(
            [CachedSleepSession(startTs: 1000, endTs: 5000, efficiency: 0.9,
                                restingHr: 52, avgHrv: 60, stagesJSON: "[\"detected\"]")],
            deviceId: "devA")

        let noopChanged = try await store.updateSleepStages(deviceId: "devA", detectedStartTs: 1000,
                                                             stagesJSON: "[\"should-not-apply\"]")
        XCTAssertEqual(noopChanged, 0, "un-edited nights are left untouched")

        try await store.applySleepEdit(deviceId: "devA", detectedStartTs: 1000, newStartTs: 1200,
                                       newEndTs: 6000,
                                       stagesJSON: "[{\"start\":1200,\"end\":6000,\"stage\":\"wake\"}]")
        let real = "[{\"start\":1200,\"end\":4000,\"stage\":\"deep\"}]"
        let changed = try await store.updateSleepStages(deviceId: "devA", detectedStartTs: 1000, stagesJSON: real)
        XCTAssertEqual(changed, 1)
        let rows = try await store.sleepSessions(deviceId: "devA", from: 0, to: 100_000, limit: 10)
        XCTAssertEqual(rows[0].stagesJSON, real)
        XCTAssertEqual(rows[0].startTsAdjusted, 1200, "corrected onset preserved")
        XCTAssertEqual(rows[0].endTs, 6000, "corrected wake preserved")
    }

    /// Per-epoch motionJSON / sleepStateJSON: round-trip, empty-clears-to-nil, and the batched multi-start
    /// reads match what N single reads would return.
    func testLegacyMotionAndSleepStateRoundTripAndBatched() async throws {
        let store = try await legacyStore()
        let dev = "devA"
        let starts = [1000, 2000, 3000]
        for s in starts {
            try await store.upsertSleepSessions(
                [CachedSleepSession(startTs: s, endTs: s + 8 * 3_600, efficiency: 0.9,
                                    restingHr: 52, avgHrv: 70, stagesJSON: "[]")],
                deviceId: dev)
        }

        let motionN = try await store.persistSessionMotion(deviceId: dev, sessionStart: starts[0],
                                                            motionEpochs: [0.0, 1.5, 3.0])
        XCTAssertEqual(motionN, 1)
        let stateN = try await store.persistSessionSleepState(deviceId: dev, sessionStart: starts[1],
                                                               states: [0, 1, 2])
        XCTAssertEqual(stateN, 1)

        let motion = try await store.sessionMotion(deviceId: dev, sessionStart: starts[0])
        XCTAssertEqual(motion, [0.0, 1.5, 3.0])
        let state = try await store.sessionSleepState(deviceId: dev, sessionStart: starts[1])
        XCTAssertEqual(state, [0, 1, 2])

        // Empty array clears to nil.
        _ = try await store.persistSessionMotion(deviceId: dev, sessionStart: starts[0], motionEpochs: [])
        let cleared = try await store.sessionMotion(deviceId: dev, sessionStart: starts[0])
        XCTAssertNil(cleared)

        // Batched reads match singles: re-populate starts[0], leave starts[2] never written.
        _ = try await store.persistSessionMotion(deviceId: dev, sessionStart: starts[0], motionEpochs: [9.0])
        let batched = try await store.sessionMotions(deviceId: dev, sessionStarts: starts)
        XCTAssertEqual(batched, [starts[0]: [9.0]])

        let batchedStates = try await store.sessionSleepStates(deviceId: dev, from: starts[0], to: starts[2])
        XCTAssertEqual(batchedStates, [starts[1]: [0, 1, 2]])
    }

    /// Daily-metrics upsert idempotency, day-range filter, and the windowed delete that keeps out-of-range
    /// and other-namespace rows untouched.
    func testLegacyDailyMetricsUpsertRangeAndWindowedDelete() async throws {
        let store = try await legacyStore()
        let bare: (String) -> DailyMetric = { day in
            DailyMetric(day: day, totalSleepMin: nil, efficiency: nil, deepMin: nil, remMin: nil,
                        lightMin: nil, disturbances: nil, restingHr: nil, avgHrv: nil,
                        recovery: nil, strain: nil, exerciseCount: nil)
        }
        try await store.upsertDailyMetrics(
            [bare("2026-05-09"), bare("2026-05-10"), bare("2026-05-11"), bare("2026-05-12")],
            deviceId: "my-whoop-noop")
        try await store.upsertDailyMetrics([bare("2026-05-11")], deviceId: "my-whoop")

        // Re-upsert same day with new values → no duplicate, value updated.
        let updated = DailyMetric(day: "2026-05-10", totalSleepMin: 400, efficiency: 0.88, deepMin: 80,
                                  remMin: 100, lightMin: 220, disturbances: 5, restingHr: 55,
                                  avgHrv: 58.0, recovery: 0.6, strain: 14.0, exerciseCount: 2)
        try await store.upsertDailyMetrics([updated], deviceId: "my-whoop-noop")
        let rows = try await store.dailyMetrics(deviceId: "my-whoop-noop", from: "2026-05-10", to: "2026-05-10")
        XCTAssertEqual(rows.count, 1, "same (deviceId,day) must not duplicate")
        XCTAssertEqual(rows[0].totalSleepMin, 400)

        let deleted = try await store.deleteDailyMetrics(
            deviceId: "my-whoop-noop", from: "2026-05-10", to: "2026-05-12")
        XCTAssertEqual(deleted, 3, "only the 3 in-range computed rows are removed")

        let computed = try await store.dailyMetrics(deviceId: "my-whoop-noop", from: "2026-05-01", to: "2026-05-31")
        XCTAssertEqual(computed.map { $0.day }, ["2026-05-09"])
        let imported = try await store.dailyMetrics(deviceId: "my-whoop", from: "2026-05-01", to: "2026-05-31")
        XCTAssertEqual(imported.map { $0.day }, ["2026-05-11"], "other namespace untouched")
    }
}
