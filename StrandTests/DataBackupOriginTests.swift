import XCTest
@testable import Strand

/// Pins the cross-platform backup classification after the Phase 2c-1 Task 7 flip. Import's magic
/// check passes for ANY SQLite file, so origin is judged by the migrator's bookkeeping table: Room
/// (this app now, and the Android app) writes `room_master_table`; GRDB (the pre-2c-1 Mac/iOS app)
/// writes `grdb_migrations`. `.room` is the NATIVE, straight-swap format; `.grdb` is a legacy backup
/// migrated through the ETL on import. Mirrors the shared `BackupRestore.backupOriginOf` (Room is the
/// accepted native on both platforms), so an Apple export and an Android export classify identically.
final class DataBackupOriginTests: XCTestCase {

    func testClassifiesByMigratorBookkeepingTable() {
        XCTAssertEqual(DataBackup.backupOrigin(of: ["room_master_table", "daily_metrics"]),
                       .room)
        XCTAssertEqual(DataBackup.backupOrigin(of: ["grdb_migrations", "dailyMetric", "hrSample"]),
                       .grdb)
        // Neither marker (empty/pre-migration file): fall through to the normal path.
        XCTAssertEqual(DataBackup.backupOrigin(of: ["some_table"]), .unknown)
        XCTAssertEqual(DataBackup.backupOrigin(of: []), .unknown)
    }

    func testRoomWinsWhenBothMarkersPresent() {
        // Degenerate both-present case: Room is this app's native store now, so it wins (the accepted
        // read), matching the shared engine's tie-break.
        XCTAssertEqual(DataBackup.backupOrigin(of: ["grdb_migrations", "room_master_table"]),
                       .room)
    }

    func testOlderRoomLayoutDetectedByAndroidMetadataPair() {
        // Pre-`room_master_table` Room/AndroidX backups still carry the android_metadata +
        // sqlite_sequence duo — classify those as Room (native) too.
        XCTAssertEqual(DataBackup.backupOrigin(of: ["android_metadata", "sqlite_sequence", "daily_metrics"]),
                       .room)
        // android_metadata alone (no Room sequence table) stays unknown — don't over-accept.
        XCTAssertEqual(DataBackup.backupOrigin(of: ["android_metadata"]), .unknown)
    }

    func testHoldsDataMatchesTheSharedDefinition() {
        // Any user-content table beyond the SQLite/Android/GRDB housekeeping ones counts as data.
        XCTAssertTrue(DataBackup.holdsData(["android_metadata", "hrSample"]))
        XCTAssertTrue(DataBackup.holdsData(["grdb_migrations", "device"]))
        XCTAssertFalse(DataBackup.holdsData(["android_metadata", "sqlite_sequence"]))
        XCTAssertFalse(DataBackup.holdsData(["room_master_table", "sqlite_stat1"]))
        XCTAssertFalse(DataBackup.holdsData([]))
    }
}
