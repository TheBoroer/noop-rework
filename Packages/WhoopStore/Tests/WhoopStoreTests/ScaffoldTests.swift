import XCTest
@testable import WhoopStore

final class ScaffoldTests: XCTestCase {
    func testSQLiteIsLinkedAndUsable() throws {
        // #65: proves the raw-SQLite test plumbing works (GRDB is gone from this target).
        let path = FileManager.default.temporaryDirectory
            .appendingPathComponent("scaffold-\(UUID().uuidString).sqlite").path
        defer { try? FileManager.default.removeItem(atPath: path) }
        let answer = try TestSQLite.queryInt(atPath: path, "SELECT 42")
        XCTAssertEqual(answer, 42)
    }

    func testLibraryVersionMarkerPresent() {
        XCTAssertEqual(WhoopStoreInfo.schemaVersion, 18)
    }
}
