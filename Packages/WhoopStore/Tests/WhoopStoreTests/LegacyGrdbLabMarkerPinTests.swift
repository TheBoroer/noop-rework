import XCTest
@testable import WhoopStore

/// Task 5: the PINNED legacy-mode subset for LabMarkerStore.swift. LabMarkerStoreTests now runs
/// against the ROOM backend; this file keeps the GRDB fallback branch of every delegated method
/// covered by running a condensed but representative slice of the same assertions against
/// `WhoopStore.inMemory()`, which is always `.legacyGrdb`.
final class LegacyGrdbLabMarkerPinTests: XCTestCase {

    private func legacyStore() async throws -> WhoopStore {
        let store = try await WhoopStore.inMemory()
        guard case .legacyGrdb = store.storageBackend else {
            XCTFail("inMemory() must be the legacy GRDB backend")
            throw XCTSkip("unreachable")
        }
        return store
    }

    private func mk(
        id: String, key: String, day: String, takenAt: Int,
        value: Double?, valueText: String? = nil, source: String = "manual",
        category: String = "bloodPanel", unit: String = "mmol/L"
    ) -> LabMarkerRow {
        LabMarkerRow(id: id, deviceId: "my-whoop", markerKey: key, category: category,
                     day: day, takenAt: takenAt, value: value, valueText: valueText,
                     unit: unit, source: source, note: nil, referenceText: nil)
    }

    /// Upsert idempotency by natural key (id-preserving on conflict), read by marker and by
    /// category, and distinct sorted markerKeysPresent.
    func testLegacyUpsertIdempotentAndReadByMarkerAndCategory() async throws {
        let store = try await legacyStore()
        try await store.upsertLabMarkers([
            mk(id: "a", key: "ldl", day: "2026-01-10", takenAt: 1_736_500_000, value: 3.4),
            mk(id: "b", key: "ldl", day: "2026-03-10", takenAt: 1_741_600_000, value: 3.1),
            mk(id: "c", key: "bp_systolic", day: "2026-03-10", takenAt: 1_741_600_500,
               value: 122, category: "bloodPressure", unit: "mmHg"),
        ])

        // Re-upsert the same natural key with a fresh id + new value → update in place, id
        // stays the original ("a"), not the caller's "different-id".
        try await store.upsertLabMarkers([
            mk(id: "different-id", key: "ldl", day: "2026-01-10", takenAt: 1_736_500_000, value: 2.9),
        ])

        let ldl = try await store.labMarkers(deviceId: "my-whoop", markerKey: "ldl")
        XCTAssertEqual(ldl.count, 2, "same natural key must not duplicate")
        XCTAssertEqual(ldl[0].id, "a", "id-preserving upsert keeps the original row's id")
        XCTAssertEqual(ldl[0].value, 2.9, "conflict updates value in place")

        let bp = try await store.labMarkers(deviceId: "my-whoop", category: "bloodPressure")
        XCTAssertEqual(bp.map { $0.markerKey }, ["bp_systolic"])

        let keys = try await store.markerKeysPresent(deviceId: "my-whoop")
        XCTAssertEqual(keys, ["bp_systolic", "ldl"], "distinct + sorted ascending")
    }

    /// Every write projects the latest-numeric-per-day reading into metricSeries under
    /// lab-book; a qualitative (valueText-only) reading does not project.
    func testLegacyWriteProjectsLatestNumericPerDayAndSkipsQualitative() async throws {
        let store = try await legacyStore()
        try await store.upsertLabMarkers([
            mk(id: "a", key: "ldl", day: "2026-01-10", takenAt: 1_736_500_000, value: 3.4),
            mk(id: "b", key: "ldl", day: "2026-01-10", takenAt: 1_736_590_000, value: 3.0),
            mk(id: "q", key: "covid_test", day: "2026-02-01", takenAt: 1_738_400_000,
               value: nil, valueText: "negative", category: "appointmentNote", unit: ""),
        ])

        let proj = try await store.metricSeries(deviceId: WhoopStore.labBookSourceId,
                                                key: "ldl", from: "2026-01-01", to: "2026-12-31")
        XCTAssertEqual(proj.map { $0.value }, [3.0], "latest-takenAt-per-day wins")

        let qualProj = try await store.metricSeries(deviceId: WhoopStore.labBookSourceId,
                                                    key: "covid_test", from: "2026-01-01", to: "2026-12-31")
        XCTAssertEqual(qualProj.count, 0, "valueText-only readings don't project a REAL cell")
    }

    /// Delete removes the marker row; if it was the last numeric reading for its cell the
    /// projected day is removed, otherwise the projection is recomputed from the remainder.
    func testLegacyDeleteRemovesRowAndReProjectsOrDropsCell() async throws {
        let store = try await legacyStore()
        try await store.upsertLabMarkers([
            mk(id: "a", key: "ldl", day: "2026-01-10", takenAt: 1_736_500_000, value: 3.4),
            mk(id: "b", key: "ldl", day: "2026-01-10", takenAt: 1_736_590_000, value: 3.0),
        ])

        // Delete the later (currently-projecting) reading → the earlier one re-projects.
        let deletedB = try await store.deleteLabMarker(id: "b")
        XCTAssertTrue(deletedB)
        var proj = try await store.metricSeries(deviceId: WhoopStore.labBookSourceId,
                                                key: "ldl", from: "2026-01-01", to: "2026-12-31")
        XCTAssertEqual(proj.map { $0.value }, [3.4], "remaining same-day reading re-projects")

        // Delete the last remaining reading → the projected day is dropped entirely.
        let deletedA = try await store.deleteLabMarker(id: "a")
        XCTAssertTrue(deletedA)
        proj = try await store.metricSeries(deviceId: WhoopStore.labBookSourceId,
                                            key: "ldl", from: "2026-01-01", to: "2026-12-31")
        XCTAssertEqual(proj.count, 0, "orphaned projected day removed")

        let missing = try await store.deleteLabMarker(id: "nonexistent")
        XCTAssertFalse(missing, "deleting an unknown id returns false")
    }
}
