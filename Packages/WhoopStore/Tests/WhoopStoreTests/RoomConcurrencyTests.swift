import XCTest
import WhoopProtocol
import Shared
@testable import WhoopStore

/// Phase 2c-1 Task 8 — the Room-backend port of `DatabasePoolConcurrencyTests`' guarantee, the gate
/// that DECIDES whether the storage cutover ships.
///
/// The GRDB store used a `DatabasePool` (WAL) precisely so the dashboard's reads run CONCURRENTLY with
/// the backfill's bulk writes instead of serializing behind them (#755): a read issued while a write is
/// in flight returns the last committed snapshot at once, it does not block until the writer commits.
/// `DatabasePoolConcurrencyTests` pinned that for GRDB. These tests pin the SAME property for the Room
/// backend (Room + `BundledSQLiteDriver`, WAL, `setQueryCoroutineContext(Dispatchers.IO)`): a bulk
/// insert transaction in flight must not block a concurrent committed-data read beyond a small bound.
///
/// FINDINGS (measured on this Mac, arm64, recorded in the Task 8 report):
///  - Room's default `BundledSQLiteDriver` connection pool ALREADY satisfies the guarantee out of the
///    box: no `setQueryCoroutineContext`/pool-size tuning was needed. The KMP builder defaults to WAL,
///    and the pool opens the writer connection plus separate reader connections, so a `SELECT` during
///    an open multi-row `@Insert` transaction reads the pre-write WAL snapshot without waiting.
///  - Direct-DAO path: during a 500k-row insert transaction (~1.4s in flight) ~30k concurrent
///    `countHr()` reads each completed in single-digit milliseconds (max ~6ms), and every concurrent
///    read observed EITHER the pre-write count OR the fully-committed total, never a torn/partial value
///    (snapshot isolation holds). A serialized backend would have blocked each read ~1.4s.
///  - Production actor path (`WhoopStore.insert`): actor reentrancy at the `await` suspension lets a
///    concurrent read interleave with the in-flight bulk write; reads stayed responsive and always
///    returned committed data.
///
/// SIZING NOTE: the plan names "50k rows". On this hardware 50k commits in ~0.13s, too brief to place a
/// meaningfully-small ABSOLUTE latency bound below it. So the direct-DAO gate below drives the in-flight
/// transaction with 500k rows (a ~1.4s window) for a wide-margin absolute bound; it is the identical
/// guarantee, strictly harder to pass. The production actor-path test keeps the plan's 50k chunk.
final class RoomConcurrencyTests: XCTestCase {

    /// A read that overlaps an in-flight bulk write must return within this bound. It is small in
    /// absolute terms (a dashboard query) yet far below the multi-hundred-ms-to-multi-second window a
    /// SERIALIZED backend would impose, so it cleanly separates "served from a WAL snapshot" (few ms)
    /// from "blocked behind the writer".
    private static let readBound: Duration = .milliseconds(250)

    private enum ConcurrencyTimeout: Error { case readBlockedOnWriter }

    private func freshRoomDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("noop-room-concurrency-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func hrRows(device: String, count: Int, tsBase: Int64) -> [Shared.HrSample] {
        (0..<count).map { i in
            Shared.HrSample(deviceId: device, ts: tsBase + Int64(i), bpm: Int32(60 + (i % 40)), synced: 0)
        }
    }

    // MARK: - Gate 1: direct Room/SQLite layer (faithful port of DatabasePoolConcurrencyTests)

    /// CORE GATE: a 500k-row insert transaction is held in flight against a Room `WhoopDatabase`; a
    /// tight loop of `countHr()` reads runs concurrently, EACH raced against `readBound`. If any read
    /// blocks past the bound (the signature of a backend that serializes reads behind the writer), the
    /// timeout task throws and fails the test. We further assert (a) many reads genuinely overlapped the
    /// write (the gate is not vacuous), (b) every concurrent read saw only committed data — the pre-write
    /// count or the fully-committed total, never a torn in-between value (snapshot isolation), and
    /// (c) after the writer commits, the full row set is visible.
    func testInFlightBulkInsertDoesNotBlockConcurrentReads() async throws {
        let dir = try freshRoomDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let db = whoopDatabase(path: dir.appendingPathComponent("noop.db").path)
        let dao = db.whoopDao()

        // Commit a small known snapshot first so a concurrent read has a definite committed value.
        _ = try await dao.countHr()   // force the connection open
        _ = try await dao.insertHr(rows: hrRows(device: "seed", count: 5, tsBase: 1_000))
        let seedCount = Int(truncating: try await dao.countHr())
        XCTAssertEqual(seedCount, 5)

        let bulkCount = 500_000
        let bulk = hrRows(device: "bulk", count: bulkCount, tsBase: 1_000_000)
        let writerDone = ConcurrencyFlag()
        let stats = ConcurrencyStats()

        try await withThrowingTaskGroup(of: Void.self) { group in
            // Writer: one multi-row @Insert = one transaction, held open for the whole copy.
            group.addTask {
                _ = try await dao.insertHr(rows: bulk)
                writerDone.set()
            }
            // Reader: hammer committed-data reads until the writer finishes; each read is bounded.
            group.addTask {
                while !writerDone.get() {
                    let started = ContinuousClock.now
                    let count = try await Self.bounded {
                        Int(truncating: try await dao.countHr())
                    }
                    let elapsed = ContinuousClock.now - started
                    // Only rows read WHILE the writer was still in flight prove concurrency.
                    if !writerDone.get() {
                        stats.recordConcurrent(count: count, latency: elapsed)
                    }
                }
            }
            try await group.waitForAll()
        }

        XCTAssertGreaterThan(stats.concurrentReads, 100,
            "expected many reads to overlap the in-flight bulk write; got \(stats.concurrentReads)")
        let allowed: Set<Int> = [seedCount, seedCount + bulkCount]
        XCTAssertTrue(stats.distinctConcurrentCounts.isSubset(of: allowed),
            "a concurrent read saw a torn/partial write; observed \(stats.distinctConcurrentCounts.sorted()), allowed \(allowed.sorted())")
        XCTAssertLessThan(stats.maxConcurrentLatency, Self.readBound,
            "a concurrent read exceeded the bound (\(stats.maxConcurrentLatency)); reads are serializing behind the writer")
        let finalCount = Int(truncating: try await dao.countHr())
        XCTAssertEqual(finalCount, seedCount + bulkCount, "all committed rows must be visible after the writer commits")
    }

    // MARK: - Gate 2: production actor path (WhoopStore.insert), the plan's 50k chunk

    /// The real production path: a bulk insert goes through the `WhoopStore` actor's `insert(_:deviceId:)`
    /// write funnel while the dashboard reads a committed value through the same actor. Actor reentrancy
    /// at the `await` lets the read interleave with the in-flight write, and Room serves it from a WAL
    /// snapshot. Seeds a committed device row, then reads it (raced against `readBound`) throughout a
    /// 50k-row insert; the read must keep returning the committed row, and many reads must overlap the
    /// write (proving the dashboard stays responsive rather than freezing behind the backfill).
    func testConcurrentReadStaysResponsiveThroughActor() async throws {
        let store = try await WhoopStore.roomBackedForTest()
        try await store.upsertDevice(id: "seed", mac: "SEED", name: "before")

        let bulk = WhoopProtocol.Streams(hr: (0..<50_000).map { HRSample(ts: 1_000_000 + $0, bpm: 60 + ($0 % 40)) })
        let writerDone = ConcurrencyFlag()
        let sawUncommitted = ConcurrencyFlag()
        let stats = ConcurrencyStats()

        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                _ = try await store.insert(bulk, deviceId: "bulk")
                writerDone.set()
            }
            group.addTask {
                while !writerDone.get() {
                    let started = ContinuousClock.now
                    let row = try await Self.bounded {
                        try await store.deviceRowForTest(id: "seed")
                    }
                    let elapsed = ContinuousClock.now - started
                    if !writerDone.get() {
                        // Record any non-committed observation in a flag asserted after the group, so a
                        // violation fails deterministically rather than relying on XCTAssert from a task.
                        if row?.mac != "SEED" || row?.name != "before" { sawUncommitted.set() }
                        stats.recordConcurrent(count: 0, latency: elapsed)
                    }
                }
            }
            try await group.waitForAll()
        }

        XCTAssertGreaterThan(stats.concurrentReads, 20,
            "expected reads to overlap the in-flight bulk write through the actor; got \(stats.concurrentReads)")
        XCTAssertFalse(sawUncommitted.get(),
            "a concurrent read returned a non-committed row; the actor/Room path leaked a partial write")
        XCTAssertLessThan(stats.maxConcurrentLatency, Self.readBound,
            "a concurrent read through the actor exceeded the bound (\(stats.maxConcurrentLatency))")
        let stored = try await store.storageStats_rowCountsForTest()
        XCTAssertEqual(stored.hr, 50_000, "the bulk insert must have committed every row")
        let seedRow = try await store.deviceRowForTest(id: "seed")
        XCTAssertEqual(seedRow?.name, "before", "the seeded committed row survives the concurrent bulk write")
    }

    // MARK: - Bench driver (env-gated; run by Tools/bench-etl.sh, skipped in the normal gate)

    /// Drives `GrdbMigrator` over a large synthetic legacy fixture and prints the migration wall time,
    /// for `Tools/bench-etl.sh` to wrap in `/usr/bin/time -l` (peak memory). SKIPPED unless
    /// `NOOP_BENCH_ETL_FIXTURE` points at a legacy GRDB sqlite file, so the normal `swift test` gate
    /// never runs the multi-minute 5M-row migration. Wall time is measured strictly around
    /// `GrdbMigrator.shared.migrate`, excluding the XCTest/build overhead the surrounding process adds.
    func testBenchGrdbMigrator() throws {
        guard let fixture = ProcessInfo.processInfo.environment["NOOP_BENCH_ETL_FIXTURE"], !fixture.isEmpty else {
            throw XCTSkip("set NOOP_BENCH_ETL_FIXTURE to a legacy GRDB sqlite path to run the ETL bench")
        }
        let workDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("noop-bench-etl-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: workDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: workDir) }
        let roomPath = workDir.appendingPathComponent("noop.db").path

        let started = ContinuousClock.now
        let result = GrdbMigrator.shared.migrate(legacyPath: fixture, roomPath: roomPath) { _ in }
        let elapsed = ContinuousClock.now - started

        switch onEnum(of: result) {
        case .done(let done):
            let total = done.rowCounts.values.reduce(0) { $0 + Int64(truncating: $1) }
            print("BENCH_ETL_RESULT wall_seconds=\(elapsed.wallSeconds) total_rows=\(total)")
        case .failed(let failed):
            XCTFail("ETL bench failed at \(failed.table): \(failed.message)")
        }
    }

    // MARK: - Helpers

    /// Race `op` against `readBound`; if the bound elapses first the read is blocked behind the writer,
    /// so throw (fails the test), mirroring `DatabasePoolConcurrencyTests`' timeout guard.
    private static func bounded<T: Sendable>(_ op: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T?.self) { group in
            group.addTask { try await op() }
            group.addTask {
                try await Task.sleep(for: readBound)
                throw ConcurrencyTimeout.readBlockedOnWriter
            }
            guard let first = try await group.next(), let value = first else {
                throw ConcurrencyTimeout.readBlockedOnWriter
            }
            group.cancelAll()
            return value
        }
    }
}

/// Thread-safe writer→reader handoff flag (no external deps).
final class ConcurrencyFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    func set() { lock.lock(); value = true; lock.unlock() }
    func get() -> Bool { lock.lock(); defer { lock.unlock() }; return value }
}

/// Thread-safe accumulator for reads observed WHILE the writer is in flight. The reader runs in a child
/// task, so all mutation is lock-guarded rather than captured `var`s.
final class ConcurrencyStats: @unchecked Sendable {
    private let lock = NSLock()
    private(set) var concurrentReads = 0
    private(set) var maxConcurrentLatency: Duration = .zero
    private(set) var distinctConcurrentCounts = Set<Int>()

    func recordConcurrent(count: Int, latency: Duration) {
        lock.lock(); defer { lock.unlock() }
        concurrentReads += 1
        if latency > maxConcurrentLatency { maxConcurrentLatency = latency }
        distinctConcurrentCounts.insert(count)
    }
}

private extension Duration {
    /// Whole + fractional seconds as a Double, for human-readable bench output.
    var wallSeconds: Double {
        let c = components
        return Double(c.seconds) + Double(c.attoseconds) / 1e18
    }
}
