import XCTest
@testable import WhoopStore

final class CursorTests: XCTestCase {
    func testV2CreatesCursorsTable() async throws {
        let store = try await WhoopStore.inMemory()
        let tables = try await store.tableNames()
        let pkCols = try await store.primaryKeyColumns("cursors")
        // `tableNames()` is Room-backed since #65 T4; the Room cursor table is `outboxCursor`.
        // The legacy GRDB handle still migrates its `cursors` table — the PK probe below reads it.
        XCTAssertTrue(tables.contains("outboxCursor"))
        XCTAssertEqual(pkCols, ["name"])
    }
    func testCursorRoundTrips() async throws {
        let store = try await WhoopStore.inMemory()
        let before = try await store.cursor("strap_trim")
        XCTAssertNil(before)
        try await store.setCursor("strap_trim", 12345)
        let after = try await store.cursor("strap_trim")
        XCTAssertEqual(after, 12345)
    }
    func testCursorUpsertsOnConflict() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.setCursor("strap_trim", 1)
        try await store.setCursor("strap_trim", 2)
        let v = try await store.cursor("strap_trim")
        XCTAssertEqual(v, 2)
    }
    func testHighwaterRoundTripsUnderPrefix() async throws {
        let store = try await WhoopStore.inMemory()
        let before = try await store.highwater("hr")
        XCTAssertNil(before)
        try await store.setHighwater("hr", 1_716_400_000)
        let hw = try await store.highwater("hr")
        XCTAssertEqual(hw, 1_716_400_000)
        let raw = try await store.cursor("highwater:hr")
        XCTAssertEqual(raw, 1_716_400_000)
    }
    func testHighwaterStreamsAreIndependent() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.setHighwater("hr", 100)
        try await store.setHighwater("rr", 200)
        let hr = try await store.highwater("hr")
        let rr = try await store.highwater("rr")
        XCTAssertEqual(hr, 100)
        XCTAssertEqual(rr, 200)
    }
}
