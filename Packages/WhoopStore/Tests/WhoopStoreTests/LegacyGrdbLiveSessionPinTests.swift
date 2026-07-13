import XCTest
@testable import WhoopStore

/// Task 5: the PINNED legacy-mode subset for LiveSessionStore.swift. LiveSessionStoreTests now runs
/// against the ROOM backend; this file keeps the GRDB fallback branch of every delegated method
/// covered by running a condensed but representative slice of the same assertions against
/// `WhoopStore.inMemory()`, which is always `.legacyGrdb`.
final class LegacyGrdbLiveSessionPinTests: XCTestCase {

    private func legacyStore() async throws -> WhoopStore {
        let store = try await WhoopStore.inMemory()
        guard case .legacyGrdb = store.storageBackend else {
            XCTFail("inMemory() must be the legacy GRDB backend")
            throw XCTSkip("unreachable")
        }
        return store
    }

    private func started(_ startTs: Int) -> LiveSessionRow {
        LiveSessionRow(startTs: startTs, endTs: nil, chargeAtStart: 41, floorBpm: 128, ceilingBpm: 148,
                       inBandSec: 0, belowSec: 0, aboveSec: 0, pushCount: 0, easeCount: 0, hrSource: "whoop")
    }

    /// Start (endTs nil) then end (same natural key, final totals) upserts one row, not two;
    /// recentLiveSessions returns newest-first and is device-scoped; chargeAtStart may be nil.
    func testLegacyStartThenEndUpsertsSameRowNewestFirstDeviceScoped() async throws {
        let store = try await legacyStore()
        _ = try await store.upsertLiveSession(started(1000), deviceId: "my-whoop")
        let ended = LiveSessionRow(startTs: 1000, endTs: 3400, chargeAtStart: 41, floorBpm: 128, ceilingBpm: 148,
                                   inBandSec: 1800, belowSec: 300, aboveSec: 120, pushCount: 2, easeCount: 1,
                                   hrSource: "whoop")
        _ = try await store.upsertLiveSession(ended, deviceId: "my-whoop")

        var rows = try await store.recentLiveSessions(deviceId: "my-whoop", limit: 10)
        XCTAssertEqual(rows.count, 1, "same (deviceId, startTs) upserts one row")
        XCTAssertEqual(rows.first, ended)

        _ = try await store.upsertLiveSession(started(5000), deviceId: "my-whoop")
        _ = try await store.upsertLiveSession(started(9000), deviceId: "other")

        rows = try await store.recentLiveSessions(deviceId: "my-whoop", limit: 10)
        XCTAssertEqual(rows.map { $0.startTs }, [5000, 1000], "newest first, other device excluded")

        let noCharge = LiveSessionRow(startTs: 6000, endTs: nil, chargeAtStart: nil, floorBpm: 120,
                                      ceilingBpm: 150, inBandSec: 0, belowSec: 0, aboveSec: 0,
                                      pushCount: 0, easeCount: 0, hrSource: "strap")
        _ = try await store.upsertLiveSession(noCharge, deviceId: "my-whoop")
        let latest = try await store.recentLiveSessions(deviceId: "my-whoop", limit: 1)
        XCTAssertNil(latest.first?.chargeAtStart)
        XCTAssertEqual(latest.first?.hrSource, "strap")
    }
}
