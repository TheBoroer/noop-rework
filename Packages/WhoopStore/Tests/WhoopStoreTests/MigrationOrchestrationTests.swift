import XCTest
import GRDB
import Shared
@testable import WhoopStore

/// Phase 2c-1 Task 3: `WhoopStore.init(path:)`'s detect-migrate-open state machine. Exercises the
/// four init-matrix cases from the task brief against temp dirs seeded with the SYNTHETIC
/// `grdb-mini.sqlite` fixture (Task 2) as the legacy input, plus the retry-on-next-launch guarantee.
final class MigrationOrchestrationTests: XCTestCase {

    // MARK: - Fixture + temp dir plumbing

    /// `.../Packages/WhoopStore/Tests/WhoopStoreTests/MigrationOrchestrationTests.swift` -> repo root,
    /// mirroring `WhoopProtocolTests/DecoderOracleTests.swift`'s `#filePath`-relative fixture lookup.
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
            .appendingPathComponent("noop-migration-orch-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// The Room sibling `WhoopStore` derives from a legacy path: same directory, filename `noop.db`.
    private func roomPath(for legacyPath: String) -> String {
        URL(fileURLWithPath: legacyPath).deletingLastPathComponent().appendingPathComponent("noop.db").path
    }

    /// Copies the committed fixture into `dir/whoop.sqlite` so a bug can never dirty the repo file,
    /// and skips (rather than failing for the wrong reason) if a Swift-only checkout lacks it.
    private func copyFixture(into dir: URL) throws -> String {
        guard FileManager.default.fileExists(atPath: Self.fixtureURL.path) else {
            throw XCTSkip("grdb-mini.sqlite fixture not present at \(Self.fixtureURL.path)")
        }
        let dst = dir.appendingPathComponent("whoop.sqlite")
        try FileManager.default.copyItem(at: Self.fixtureURL, to: dst)
        return dst.path
    }

    /// Row count of `table` in the plain SQLite file at `path` (Room's own file is a standard
    /// SQLite database, so a fresh read-only GRDB queue can query it without going through Room).
    private func rowCount(atPath path: String, table: String) throws -> Int {
        let queue = try DatabaseQueue(path: path)
        return try queue.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM `\(table)`") ?? -1
        }
    }

    // MARK: - 1. Fresh install (no files)

    func testFreshInstallOpensRoomDirectly() async throws {
        let dir = try tempDir()
        let legacyPath = dir.appendingPathComponent("whoop.sqlite").path
        let roomPath = roomPath(for: legacyPath)
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacyPath), "precondition: no legacy file")
        XCTAssertFalse(FileManager.default.fileExists(atPath: roomPath), "precondition: no room file")

        let store = try await WhoopStore(path: legacyPath)

        XCTAssertEqual(store.storageBackend, .room)
        XCTAssertNil(store.migrationProgress, "a fresh install runs no ETL")
        XCTAssertTrue(FileManager.default.fileExists(atPath: roomPath), "Room database created")
        // rawBatch/cursors always live on the legacy GRDB handle, so it must exist too, migrated.
        let tables = try await store.tableNames()
        XCTAssertTrue(tables.contains("hrSample"))
        XCTAssertTrue(tables.contains("rawBatch"))
    }

    // MARK: - 2. Legacy-only (ETL runs, with progress)

    func testLegacyOnlyRunsEtlThenOpensRoom() async throws {
        let dir = try tempDir()
        let legacyPath = try copyFixture(into: dir)
        let roomPath = roomPath(for: legacyPath)
        XCTAssertFalse(FileManager.default.fileExists(atPath: roomPath), "precondition: no room file yet")

        let store = try await WhoopStore(path: legacyPath)

        XCTAssertEqual(store.storageBackend, .room)
        let progress = try XCTUnwrap(store.migrationProgress, "a legacy-only launch must report ETL progress")
        var values: [Double] = []
        for await v in progress { values.append(v) }
        XCTAssertFalse(values.isEmpty)
        for v in values {
            XCTAssertGreaterThanOrEqual(v, 0)
            XCTAssertLessThanOrEqual(v, 1)
        }
        XCTAssertEqual(values, values.sorted(), "progress must be monotonically non-decreasing")
        XCTAssertEqual(values.last ?? -1, 1.0, accuracy: 0.0001, "a completed ETL reaches 1.0")

        // The Room file actually holds the copied data (not just an empty schema).
        XCTAssertEqual(try rowCount(atPath: roomPath, table: "hrSample"), 44_401)
        XCTAssertEqual(try rowCount(atPath: roomPath, table: "device"), 3)

        // The legacy file is retained (never deleted), and still has its data intact.
        XCTAssertTrue(FileManager.default.fileExists(atPath: legacyPath))
        XCTAssertEqual(try rowCount(atPath: legacyPath, table: "hrSample"), 44_401)
    }

    // MARK: - 3. Both present (Room wins, no re-ETL)

    func testBothPresentOpensRoomWithoutReEtl() async throws {
        let dir = try tempDir()
        let legacyPath = try copyFixture(into: dir)
        let roomPath = roomPath(for: legacyPath)

        // Pre-build the Room file directly (bypassing WhoopStore), simulating a prior launch that
        // already finished the cutover.
        let first = GrdbMigrator.shared.migrate(legacyPath: legacyPath, roomPath: roomPath) { _ in }
        guard case .done = onEnum(of: first) else {
            return XCTFail("precondition: the direct ETL must succeed, got \(first)")
        }
        // Plant a sentinel row that only a re-ETL's truncate-then-copy would wipe.
        do {
            let roomQueue = try DatabaseQueue(path: roomPath)
            try await roomQueue.write { db in
                try db.execute(
                    sql: "INSERT INTO device (id, mac, name, firstSeen, lastSeen) VALUES (?, ?, ?, ?, ?)",
                    arguments: ["sentinel-no-reetl", nil, "sentinel", 0, 0])
            }
        }

        let store = try await WhoopStore(path: legacyPath)

        XCTAssertEqual(store.storageBackend, .room)
        XCTAssertNil(store.migrationProgress, "both files present must not trigger a re-ETL")
        let queue = try DatabaseQueue(path: roomPath)
        let stillThere = try await queue.read { db in
            try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM device WHERE id = 'sentinel-no-reetl')") ?? false
        }
        XCTAssertTrue(stillThere, "a re-ETL would have truncated device and wiped the sentinel row")
    }

    // MARK: - 4. ETL failure (fallback to legacy GRDB, diagnostic flag, retry next launch)

    /// Plants a non-integer REAL in an INTEGER column (mirrors `GrdbMigratorTest.kt`'s
    /// `nonIntegerRealInAnIntegerColumnFailsLoudly`, which proves this forces `Result.Failed`).
    /// The brief's suggested alternative, truncating the file, was tried first and rejected: SQLite's
    /// own page-count header check makes ANY truncation (even one page) fail to open at all, which
    /// would break the legacy GRDB handle this task requires to ALWAYS stay open, not just the ETL.
    private func plantNonIntegerBpm(atPath path: String) throws {
        let queue = try DatabaseQueue(path: path)
        try queue.write { db in
            try db.execute(sql: "UPDATE hrSample SET bpm = 65.5 WHERE deviceId = 'whoop-beta'")
        }
    }

    func testEtlFailureFallsBackToLegacyGrdbWithDiagnosticFlag() async throws {
        let dir = try tempDir()
        let legacyPath = try copyFixture(into: dir)
        try plantNonIntegerBpm(atPath: legacyPath)
        let roomPath = roomPath(for: legacyPath)

        let store = try await WhoopStore(path: legacyPath)

        guard case .legacyGrdb(let reason) = store.storageBackend else {
            return XCTFail("expected a legacyGrdb fallback, got \(store.storageBackend)")
        }
        let message = try XCTUnwrap(reason, "a real ETL failure must carry a diagnostic reason")
        XCTAssertTrue(message.contains("hrSample"), message)
        XCTAssertTrue(message.contains("65.5"), message)

        // No partial Room file left behind: the next launch must retry cleanly, not skip past.
        XCTAssertFalse(FileManager.default.fileExists(atPath: roomPath))

        // The store is still fully functional on the legacy GRDB handle: reads AND writes work.
        try await store.upsertDevice(id: "still-works", mac: nil, name: "WHOOP")
        let tables = try await store.tableNames()
        XCTAssertTrue(tables.contains("hrSample"))

        // Progress was still observable (device, the first table, completes before hrSample fails).
        let progress = try XCTUnwrap(store.migrationProgress)
        var values: [Double] = []
        for await v in progress { values.append(v) }
        XCTAssertFalse(values.isEmpty, "the device table completes before the hrSample failure")
        XCTAssertLessThan(values.last ?? 1, 1.0, "a failed ETL must never report full completion")
    }

    func testEtlFailureRetriesOnNextLaunch() async throws {
        let dir = try tempDir()
        let legacyPath = try copyFixture(into: dir)
        try plantNonIntegerBpm(atPath: legacyPath)

        let firstOpen = try await WhoopStore(path: legacyPath)
        guard case .legacyGrdb = firstOpen.storageBackend else {
            return XCTFail("precondition: first open must fall back")
        }

        // Second launch, same still-corrupted legacy file: must attempt the ETL again (a fresh
        // `migrationProgress`, not `nil`), not silently stay on a stale "Room exists" shortcut.
        let secondOpen = try await WhoopStore(path: legacyPath)
        XCTAssertNotNil(secondOpen.migrationProgress, "a retried launch must attempt the ETL again")
        guard case .legacyGrdb(let reason) = secondOpen.storageBackend else {
            return XCTFail("expected the retry to fall back again while the file stays corrupt")
        }
        XCTAssertNotNil(reason)
    }
}
