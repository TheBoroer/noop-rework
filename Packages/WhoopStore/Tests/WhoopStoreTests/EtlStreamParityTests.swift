import XCTest
@testable import WhoopStore

/// Phase 2c-1 Task 4: Room-vs-GRDB parity over the REAL ETL fixture (`grdb-mini.sqlite`, ~44k HR
/// rows). After a legacy-only open runs the one-time ETL, `storageStats` and the per-table counts
/// served by the ROOM backend must match direct GRDB counts over the retained legacy file exactly.
///
/// Phase 2c-2 Task 4 update: `rawBatch`/outbox aggregates no longer "keep coming from the legacy
/// handle" — `storageStats()` is now backend-aware (`RawOutbox.swift`/`Reads.swift`), so a Room-
/// backed store reports its OWN `outboxBatch` pending-batch footprint via `OutboxBridge`, not the
/// retained legacy file's `rawBatch` table. The ETL that cuts a legacy-only install over to Room
/// (Task 2's `GrdbMigrator`, exercised here) only migrates the 8 decoded tables — it does not drain
/// the legacy raw-frame outbox into Room's `outboxBatch` (that one-time drain is Phase 2c-2 Task 6,
/// not yet implemented), so immediately after a fresh cutover Room's outbox is legitimately empty
/// even though the retained legacy file still physically holds its original `rawBatch` rows. This
/// test pins that divergence rather than asserting stale cross-backend parity.
///
/// Also runs the (non-asserting) performance sanity from the brief: wall-clock timings for
/// `hrSamples` / `hrBuckets` over the fixture's densest device, Room-backed store vs the same SQL on
/// a plain GRDB queue, printed for the task report. Task 8 owns real perf gates.
final class EtlStreamParityTests: XCTestCase {

    private static var repoRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // WhoopStoreTests
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // WhoopStore
            .deletingLastPathComponent()  // Packages
            .deletingLastPathComponent()  // repo root
    }

    private static var fixtureURL: URL {
        repoRoot.appendingPathComponent("shared/src/commonTest/fixtures/grdb-mini.sqlite")
    }

    private func tempDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("noop-etl-parity-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func copyFixture(into dir: URL) throws -> String {
        guard FileManager.default.fileExists(atPath: Self.fixtureURL.path) else {
            throw XCTSkip("grdb-mini.sqlite fixture not present at \(Self.fixtureURL.path)")
        }
        let dst = dir.appendingPathComponent("whoop.sqlite")
        try FileManager.default.copyItem(at: Self.fixtureURL, to: dst)
        return dst.path
    }

    /// COUNT(*) per stream table, straight off the legacy GRDB file (the ETL's source of truth).
    private func legacyCounts(atPath path: String) throws
        -> (hr: Int, rr: Int, events: Int, battery: Int,
            spo2: Int, skinTemp: Int, resp: Int, gravity: Int,
            rawBatches: Int, rawBytes: Int) {
        func count(_ table: String) throws -> Int {
            try TestSQLite.queryInt(atPath: path, "SELECT COUNT(*) FROM `\(table)`") ?? -1
        }
        let bytes = try TestSQLite.queryInt(
            atPath: path, "SELECT COALESCE(SUM(byteSize), 0) FROM rawBatch") ?? -1
        return (try count("hrSample"), try count("rrInterval"), try count("event"),
                try count("battery"), try count("spo2Sample"), try count("skinTempSample"),
                try count("respSample"), try count("gravitySample"),
                try count("rawBatch"), bytes)
    }

    func testStorageStatsParityAfterEtlAndReadTimings() async throws {
        let dir = try tempDir()
        let legacyPath = try copyFixture(into: dir)

        let store = try await WhoopStore(path: legacyPath)
        XCTAssertEqual(store.storageBackend, .room, "the ETL fixture open must cut over to Room")

        let legacy = try legacyCounts(atPath: legacyPath)

        // Per-table parity: the Room-backed count helpers must reproduce the legacy file exactly
        // (GrdbMigrator.verify already checked this at ETL time; this pins the READ path on top).
        let roomCounts = try await store.storageStats_rowCountsForTest()
        XCTAssertEqual(roomCounts.hr, legacy.hr)
        XCTAssertEqual(roomCounts.rr, legacy.rr)
        XCTAssertEqual(roomCounts.events, legacy.events)
        XCTAssertEqual(roomCounts.battery, legacy.battery)
        XCTAssertEqual(roomCounts.spo2, legacy.spo2)
        XCTAssertEqual(roomCounts.skinTemp, legacy.skinTemp)
        XCTAssertEqual(roomCounts.resp, legacy.resp)
        XCTAssertEqual(roomCounts.gravity, legacy.gravity)

        // storageStats: decoded rows come from Room (ETL'd), matching the legacy file's per-table
        // counts exactly. The raw-outbox aggregate is now backend-aware and reports Room's OWN
        // `outboxBatch` pending footprint, not the legacy file's `rawBatch` table — see the header
        // comment. The ETL migrates decoded tables only; the raw outbox drain is Task 6 (not yet
        // implemented), so right after a fresh cutover Room's outbox is legitimately empty (0
        // batches, 0 bytes) even though the retained legacy file's own `rawBatch` still holds its
        // original fixture rows untouched (asserted below via `legacyCounts`, not lost, just not
        // reflected by this diagnostic until Task 6's drain runs).
        let stats = try await store.storageStats()
        let expectedDecoded = legacy.hr + legacy.rr + legacy.events + legacy.battery
            + legacy.spo2 + legacy.skinTemp + legacy.resp + legacy.gravity
        XCTAssertEqual(stats.decodedRows, expectedDecoded)
        XCTAssertEqual(stats.rawBatches, 0, "Room's outboxBatch is empty until Task 6's one-time drain")
        XCTAssertEqual(stats.rawBytes, 0, "Room's outboxBatch is empty until Task 6's one-time drain")
        XCTAssertGreaterThan(legacy.rawBatches, 0,
                              "sanity: the retained legacy file itself must still carry its original rawBatch rows")

        // The fixture actually exercises the read (44k+ HR rows), not an empty schema.
        XCTAssertGreaterThan(legacy.hr, 40_000, "fixture must carry the dense HR stream")

        // Densest device for the perf sanity pass.
        let densest = try TestSQLite.queryString(atPath: legacyPath, """
            SELECT deviceId FROM hrSample GROUP BY deviceId ORDER BY COUNT(*) DESC LIMIT 1
            """)
        let deviceId = try XCTUnwrap(densest)

        // Room-backed store reads (through the shared DAO).
        var t0 = Date()
        let roomSamples = try await store.hrSamples(deviceId: deviceId, from: 0,
                                                    to: 4_000_000_000, limit: 200_000)
        let roomSamplesMs = Date().timeIntervalSince(t0) * 1000
        t0 = Date()
        let roomBuckets = try await store.hrBuckets(deviceId: deviceId, from: 0,
                                                    to: 4_000_000_000, bucketSeconds: 300)
        let roomBucketsMs = Date().timeIntervalSince(t0) * 1000

        // The same SQL straight over the legacy file via raw sqlite3 (reference timing,
        // same machine/run). GRDB is gone from the test target; TestSQLite is the reference.
        t0 = Date()
        let grdbSampleCount = try XCTUnwrap(TestSQLite.queryInt(atPath: legacyPath, """
            SELECT COUNT(*) FROM (
                SELECT ts, bpm FROM (
                    SELECT ts, bpm FROM hrSample
                    WHERE deviceId = '\(deviceId)' AND ts >= 0 AND ts <= 4000000000
                    UNION ALL
                    SELECT p.ts, CAST(ROUND(p.bpm) AS INTEGER) AS bpm FROM ppgHrSample p
                    WHERE p.deviceId = '\(deviceId)' AND p.ts >= 0 AND p.ts <= 4000000000
                      AND NOT EXISTS (
                        SELECT 1 FROM hrSample h WHERE h.deviceId = p.deviceId AND h.ts = p.ts)
                )
                ORDER BY ts ASC LIMIT 200000
            )
            """))
        let grdbSamplesMs = Date().timeIntervalSince(t0) * 1000
        t0 = Date()
        let grdbBucketCount = try XCTUnwrap(TestSQLite.queryInt(atPath: legacyPath, """
            SELECT COUNT(*) FROM (
                SELECT (ts / 300) * 300 AS bucket, AVG(bpm) AS avgBpm FROM (
                    SELECT ts, bpm FROM hrSample
                    WHERE deviceId = '\(deviceId)' AND ts >= 0 AND ts <= 4000000000
                    UNION ALL
                    SELECT p.ts, p.bpm FROM ppgHrSample p
                    WHERE p.deviceId = '\(deviceId)' AND p.ts >= 0 AND p.ts <= 4000000000
                      AND NOT EXISTS (
                        SELECT 1 FROM hrSample h WHERE h.deviceId = p.deviceId AND h.ts = p.ts)
                ) GROUP BY ts / 300
            )
            """))
        let grdbBucketsMs = Date().timeIntervalSince(t0) * 1000

        // Result parity: the Room read surfaces the same row/bucket counts as the reference SQL.
        XCTAssertEqual(roomSamples.count, grdbSampleCount)
        XCTAssertEqual(roomBuckets.count, grdbBucketCount)

        // Timings for the task report. NO assertion (Task 8 owns perf gates); printed so the run
        // log carries the numbers.
        print("""
            [task4-perf] device=\(deviceId) rows=\(roomSamples.count) buckets=\(roomBuckets.count)
            [task4-perf] hrSamples room=\(String(format: "%.1f", roomSamplesMs))ms \
            grdb=\(String(format: "%.1f", grdbSamplesMs))ms
            [task4-perf] hrBuckets room=\(String(format: "%.1f", roomBucketsMs))ms \
            grdb=\(String(format: "%.1f", grdbBucketsMs))ms
            """)
    }
}
