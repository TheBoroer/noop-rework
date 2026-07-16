import XCTest
@testable import WhoopStore

/// #222 self-heal, REWRITTEN for the Phase 2c-1 Task 7 storage flip. A foreign database dropped over
/// ours by a bad cross-platform restore would strand the store on open (the GRDB migrator re-runs v1
/// → `table "device" already exists`, or Room meets a schema it never created). `WhoopStore` now
/// stores in Room `noop.db` beside the legacy GRDB `whoop.sqlite`, so quarantine is PATH-AWARE and
/// pins this matrix:
///
///   | file schema                    | at legacy `whoop.sqlite` | at Room `noop.db` |
///   |--------------------------------|--------------------------|-------------------|
///   | GRDB (`grdb_migrations`)       | fine — native legacy     | QUARANTINE        |
///   | Room (`room_master_table`)     | (n/a here)               | fine — native     |
///   | unknown schema, holds data     | QUARANTINE               | QUARANTINE        |
///   | empty / housekeeping only      | left untouched           | left untouched    |
///
/// Most cases test the pure `quarantineIncompatibleDatabase(at:expecting:)` gate directly; one drives
/// the whole self-heal end to end through `WhoopStore.init`.
final class ForeignDatabaseQuarantineTests: XCTestCase {

    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("noop-quarantine-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    // MARK: - Fixtures

    /// GRDB migrator bookkeeping + a data table + a row: a valid GRDB store's signature.
    private static let grdbSchema = [
        "CREATE TABLE grdb_migrations (identifier TEXT NOT NULL PRIMARY KEY)",
        "INSERT INTO grdb_migrations (identifier) VALUES ('v1')",
        "CREATE TABLE device (id TEXT NOT NULL PRIMARY KEY)",
        "INSERT INTO device (id) VALUES ('mine')",
    ]

    /// Room's identity table + a data table + a row: a valid Room store's signature.
    private static let roomSchema = [
        "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
        "INSERT INTO room_master_table (id, identity_hash) VALUES (42, '0df28b445fbde09ef5d4b64485b99b1f')",
        "CREATE TABLE hrSample (deviceId TEXT, ts INTEGER, bpm INTEGER)",
        "INSERT INTO hrSample (deviceId, ts, bpm) VALUES ('d', 1, 60)",
    ]

    /// A valid SQLite file that holds data but carries NEITHER migrator's bookkeeping: some other
    /// app's database.
    private static let unknownSchema = [
        "CREATE TABLE foo (id INTEGER PRIMARY KEY, note TEXT)",
        "INSERT INTO foo (id, note) VALUES (1, 'not a noop store')",
    ]

    /// Build a SQLite file at `path` with the given DDL/DML, releasing the handle before returning so
    /// quarantine can move/probe it freely.
    private func makeSqlite(at path: String, _ statements: [String]) throws {
        try TestSQLite.exec(atPath: path, statements.joined(separator: ";\n"))
        // TestSQLite closes the handle before returning, so quarantine can move/probe freely.
    }

    private func exists(_ path: String) -> Bool { FileManager.default.fileExists(atPath: path) }

    /// True when a `<name>.incompatible-<ts>` quarantine sidecar was written next to `path`.
    private func quarantined(_ path: String) -> Bool {
        let folder = (path as NSString).deletingLastPathComponent
        let base = (path as NSString).lastPathComponent
        let siblings = (try? FileManager.default.contentsOfDirectory(atPath: folder)) ?? []
        return siblings.contains { $0.hasPrefix(base + ".incompatible-") }
    }

    // MARK: - grdb file at legacy path = fine legacy

    func testGrdbFileAtLegacyPathIsNotQuarantined() throws {
        let path = dir.appendingPathComponent("whoop.sqlite").path
        try makeSqlite(at: path, Self.grdbSchema)

        WhoopStore.quarantineIncompatibleDatabase(at: path, expecting: .grdb)

        XCTAssertTrue(exists(path), "a valid legacy GRDB store must be left in place")
        XCTAssertFalse(quarantined(path), "no quarantine sidecar for a native GRDB file")
    }

    // MARK: - room file at room path = native

    func testRoomFileAtRoomPathIsNotQuarantined() throws {
        let path = dir.appendingPathComponent("noop.db").path
        try makeSqlite(at: path, Self.roomSchema)

        WhoopStore.quarantineIncompatibleDatabase(at: path, expecting: .room)

        XCTAssertTrue(exists(path), "a valid Room store at the Room path is native and must be kept")
        XCTAssertFalse(quarantined(path), "no quarantine sidecar for a native Room file")
    }

    // MARK: - grdb file at room path = quarantine

    func testGrdbFileAtRoomPathIsQuarantined() throws {
        let path = dir.appendingPathComponent("noop.db").path
        try makeSqlite(at: path, Self.grdbSchema)

        WhoopStore.quarantineIncompatibleDatabase(at: path, expecting: .room)

        XCTAssertFalse(exists(path), "a GRDB file where Room belongs must be moved aside")
        XCTAssertTrue(quarantined(path), "the GRDB-at-Room-path file was quarantined")
    }

    // MARK: - unknown schema anywhere = quarantine

    func testUnknownSchemaAtRoomPathIsQuarantined() throws {
        let path = dir.appendingPathComponent("noop.db").path
        try makeSqlite(at: path, Self.unknownSchema)

        WhoopStore.quarantineIncompatibleDatabase(at: path, expecting: .room)

        XCTAssertFalse(exists(path))
        XCTAssertTrue(quarantined(path), "a data-holding unknown schema at the Room path was quarantined")
    }

    func testUnknownSchemaAtLegacyPathIsQuarantined() throws {
        let path = dir.appendingPathComponent("whoop.sqlite").path
        try makeSqlite(at: path, Self.unknownSchema)

        WhoopStore.quarantineIncompatibleDatabase(at: path, expecting: .grdb)

        XCTAssertFalse(exists(path))
        XCTAssertTrue(quarantined(path), "a data-holding unknown schema at the legacy path was quarantined")
    }

    // MARK: - empty / housekeeping-only file is never quarantined

    func testEmptyFileIsNeverQuarantined() throws {
        let path = dir.appendingPathComponent("noop.db").path
        // Creates an empty SQLite file with no user tables (a no-op statement forces materialization).
        try TestSQLite.exec(atPath: path, "PRAGMA user_version = 0")

        WhoopStore.quarantineIncompatibleDatabase(at: path, expecting: .room)
        WhoopStore.quarantineIncompatibleDatabase(at: path, expecting: .grdb)

        XCTAssertTrue(exists(path), "an empty/fresh file holds no data and is always left untouched")
        XCTAssertFalse(quarantined(path))
    }

    // MARK: - End-to-end self-heal through WhoopStore.init

    func testForeignFileAtRoomPathSelfHealsOnOpen() async throws {
        // A GRDB-shaped file sitting where the Room `noop.db` belongs — a bad cross-platform restore.
        // Opening the store must quarantine it and start Room fresh, never strand.
        let legacyPath = dir.appendingPathComponent("whoop.sqlite").path // deliberately absent
        let roomPath = dir.appendingPathComponent("noop.db").path
        try makeSqlite(at: roomPath, Self.grdbSchema)
        XCTAssertFalse(try tableNames(at: roomPath).contains("room_master_table"),
                       "precondition: the planted file lacks Room's bookkeeping")

        let store = try await WhoopStore(path: legacyPath)

        XCTAssertEqual(store.storageBackend, .room,
                       "a fresh Room store opened once the foreign file was quarantined")
        XCTAssertTrue(quarantined(roomPath), "the foreign Room-path file was quarantined")
        XCTAssertTrue(exists(roomPath), "a clean Room store now lives at noop.db")
        // Room-only since #65: no legacy GRDB pool exists to create `whoop.sqlite` anymore.
        XCTAssertFalse(exists(legacyPath),
                       "nothing writes the legacy path once the GRDB pool is gone")
    }

    private func tableNames(at path: String) throws -> Set<String> {
        try TestSQLite.tableNames(atPath: path)
    }
}
