import XCTest
@testable import WhoopStore

/// Task 5: the PINNED legacy-mode subset for MetricSeriesStore.swift. MetricSeriesStoreTests now runs
/// against the ROOM backend; this file keeps the GRDB fallback branch of every delegated method covered
/// by running a condensed but representative slice of the same assertions against
/// `WhoopStore.inMemory()`, which is always `.legacyGrdb`.
final class LegacyGrdbMetricSeriesPinTests: XCTestCase {

    private func legacyStore() async throws -> WhoopStore {
        let store = try await WhoopStore.inMemory()
        guard case .legacyGrdb = store.storageBackend else {
            XCTFail("inMemory() must be the legacy GRDB backend")
            throw XCTSkip("unreachable")
        }
        return store
    }

    /// Upsert idempotency (conflict updates value in place, no duplicate), range-filtered ordered read,
    /// distinct sorted metricKeys, and the earliest/latest metricDays span (present and nil-when-absent).
    func testLegacyUpsertReadKeysAndDaySpan() async throws {
        let store = try await legacyStore()
        try await store.upsertMetricSeries([
            MetricPoint(day: "2026-05-20", key: "restingHr", value: 54),
            MetricPoint(day: "2026-05-01", key: "restingHr", value: 58),
            MetricPoint(day: "2026-05-10", key: "restingHr", value: 56),
            MetricPoint(day: "2026-05-10", key: "recovery", value: 72),
        ], deviceId: "devA")

        // Re-upsert same natural key with a new value → no duplicate, value updated.
        let n = try await store.upsertMetricSeries([
            MetricPoint(day: "2026-05-10", key: "recovery", value: 88),
        ], deviceId: "devA")
        XCTAssertEqual(n, 1)

        let hr = try await store.metricSeries(deviceId: "devA", key: "restingHr",
                                              from: "2026-05-05", to: "2026-05-15")
        XCTAssertEqual(hr.map { $0.day }, ["2026-05-10"], "range filter + day ASC ordering")

        let rec = try await store.metricSeries(deviceId: "devA", key: "recovery",
                                               from: "2026-05-01", to: "2026-05-31")
        XCTAssertEqual(rec.count, 1, "conflict updated in place, not duplicated")
        XCTAssertEqual(rec[0].value, 88)

        let keys = try await store.metricKeys(deviceId: "devA")
        XCTAssertEqual(keys, ["recovery", "restingHr"], "distinct + sorted ascending")

        let span = try await store.metricDays(deviceId: "devA", key: "restingHr")
        XCTAssertEqual(span?.earliest, "2026-05-01")
        XCTAssertEqual(span?.latest, "2026-05-20")

        let missing = try await store.metricDays(deviceId: "devA", key: "unknown")
        XCTAssertNil(missing)
    }
}
