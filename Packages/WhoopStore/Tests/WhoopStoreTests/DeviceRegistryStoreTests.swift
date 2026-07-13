import XCTest
@testable import WhoopStore

/// Task 6: runs against the ROOM backend (see `WhoopStore.roomBackedForTest()`), same shape as Task 4/5's
/// converted suites. Every assertion is unchanged from the pre-Task-6 sync suite; only the call sites
/// gained `try await` and construction moved from `DeviceRegistryStore(dbQueue:)` to
/// `DeviceRegistryStore(store:)`. The GRDB legacy branch keeps its own coverage via the pinned subset in
/// `LegacyGrdbDeviceRegistryPinTests` (which stays on `WhoopStore.inMemory()`, always `.legacyGrdb`).
final class DeviceRegistryStoreTests: XCTestCase {
    private func makeStore() async throws -> DeviceRegistryStore {
        DeviceRegistryStore(store: try await WhoopStore.roomBackedForTest())
    }

    func testSeededWhoopIsActive() async throws {
        let store = try await makeStore()
        let devices = try await store.all()
        XCTAssertEqual(devices.count, 1)
        XCTAssertEqual(devices.first?.id, "my-whoop")
        let activeId = try await store.activeDeviceId()
        XCTAssertEqual(activeId, "my-whoop")
    }

    func testSetActiveEnforcesSingleActive() async throws {
        let store = try await makeStore()
        try await store.add(PairedDevice(id: "polar-1", brand: "Polar", model: "H10", sourceKind: .liveBLE,
                                         capabilities: [.hr, .hrv], status: .paired, addedAt: 1, lastSeenAt: 1))
        try await store.setActive("polar-1")
        let activeId = try await store.activeDeviceId()
        XCTAssertEqual(activeId, "polar-1")
        let statuses = Dictionary(uniqueKeysWithValues: try await store.all().map { ($0.id, $0.status) })
        XCTAssertEqual(statuses["polar-1"], .active)
        XCTAssertEqual(statuses["my-whoop"], .paired)   // the previously-active device was demoted
        let activeCount = try await store.all().filter { $0.status == .active }.count
        XCTAssertEqual(activeCount, 1)  // I1
    }

    func testReAddPreservesOriginalAddedAt() async throws {
        // Review fix (Task 6): `add` on the Room branch must match the GRDB upsert's SET list, which
        // deliberately omits addedAt, so re-adding an existing id (e.g. re-pairing via the Add wizard,
        // which builds addedAt = now) never resets the device's position in the addedAt ASC ordering.
        let store = try await makeStore()
        try await store.add(PairedDevice(id: "polar-1", brand: "Polar", model: "H10", sourceKind: .liveBLE,
                                         capabilities: [.hr], status: .paired, addedAt: 100, lastSeenAt: 100))
        try await store.add(PairedDevice(id: "polar-1", brand: "Polar", model: "H10", nickname: "Chest strap",
                                         sourceKind: .liveBLE, capabilities: [.hr, .hrv], status: .paired,
                                         addedAt: 999, lastSeenAt: 999))
        let fetched = try await store.all().first { $0.id == "polar-1" }
        XCTAssertEqual(fetched?.addedAt, 100)          // preserved, NOT 999
        XCTAssertEqual(fetched?.lastSeenAt, 999)       // updated
        XCTAssertEqual(fetched?.nickname, "Chest strap")
        XCTAssertEqual(fetched?.capabilities, [.hr, .hrv])
    }

    func testArchiveKeepsRowAndClearsActive() async throws {
        let store = try await makeStore()
        try await store.archive("my-whoop")
        let allDevices = try await store.all()
        XCTAssertEqual(allDevices.first?.status, .archived)   // I4: row kept
        let activeId = try await store.activeDeviceId()
        XCTAssertNil(activeId)
    }

    func testSeededWhoopHasNilPeripheralId() async throws {
        // The seeded my-whoop row exists with peripheralId nil (it connects to "any WHOOP" today; it
        // adopts its peripheral id later).
        let store = try await makeStore()
        let seeded = try await store.all().first
        XCTAssertEqual(seeded?.id, "my-whoop")
        XCTAssertNil(seeded?.peripheralId)
    }

    func testPeripheralIdRoundTripsThroughAddAndAll() async throws {
        let store = try await makeStore()
        let pid = "8E1A2B3C-4D5E-6F70-8192-A3B4C5D6E7F8"
        try await store.add(PairedDevice(id: "whoop-\(pid)", brand: "WHOOP", model: "WHOOP 5.0",
                                         peripheralId: pid, sourceKind: .liveBLE,
                                         capabilities: [.hr, .hrv], status: .paired, addedAt: 10, lastSeenAt: 10))
        let fetched = try await store.all().first { $0.id == "whoop-\(pid)" }
        XCTAssertEqual(fetched?.peripheralId, pid)
    }

    func testSetPeripheralIdUpdatesIt() async throws {
        let store = try await makeStore()
        let before = try await store.all().first { $0.id == "my-whoop" }
        XCTAssertNil(before?.peripheralId)
        let pid = "11111111-2222-3333-4444-555555555555"
        try await store.setPeripheralId("my-whoop", peripheralId: pid)
        let afterSet = try await store.all().first { $0.id == "my-whoop" }
        XCTAssertEqual(afterSet?.peripheralId, pid)
        // passing nil un-adopts it
        try await store.setPeripheralId("my-whoop", peripheralId: nil)
        let afterClear = try await store.all().first { $0.id == "my-whoop" }
        XCTAssertNil(afterClear?.peripheralId)
    }

    func testDeviceForPeripheralIdFindsIt() async throws {
        let store = try await makeStore()
        let pid = "ABCDEF01-2345-6789-ABCD-EF0123456789"
        let none = try await store.device(forPeripheralId: pid)
        XCTAssertNil(none)   // none adopted yet
        try await store.setPeripheralId("my-whoop", peripheralId: pid)
        let found = try await store.device(forPeripheralId: pid)
        XCTAssertEqual(found?.id, "my-whoop")
        let notFound = try await store.device(forPeripheralId: "no-such-peripheral")
        XCTAssertNil(notFound)
    }

    // ah-delete (#616): deleteAllData(deviceId: "apple-health") clears every row stored under the
    // Apple-Health source across the deviceId-keyed tables, while leaving another device's rows untouched.
    // This is the byte-level arbiter for the full 17-table Room fan-out (`deleteDeviceDataAtomic`);
    // `WhoopDaoDeviceRegistryQueryTest.kt` exercises a representative spread at the Kotlin/DAO level.
    func testDeleteAllDataClearsOnlyTheTargetDevicesRows() async throws {
        let underlying = try await WhoopStore.roomBackedForTest()
        let store = DeviceRegistryStore(store: underlying)

        // Seed apple-health + my-whoop rows in two device-scoped tables (appleDaily + metricSeries),
        // through the same delegated stores the app actually writes through.
        for dev in ["apple-health", "my-whoop"] {
            _ = try await underlying.upsertAppleDaily(
                [AppleDaily(day: "2026-06-15", steps: 1234, activeKcal: nil, basalKcal: nil, vo2max: nil,
                           avgHr: nil, maxHr: nil, walkingHr: nil, weightKg: nil)], deviceId: dev)
            _ = try await underlying.upsertMetricSeries(
                [MetricPoint(day: "2026-06-15", key: "steps", value: 1234.0)], deviceId: dev)
        }

        // Both devices start with a row in each table.
        var appleHealthDaily = try await underlying.appleDaily(deviceId: "apple-health", from: "2026-06-01", to: "2026-06-30")
        var myWhoopDaily = try await underlying.appleDaily(deviceId: "my-whoop", from: "2026-06-01", to: "2026-06-30")
        XCTAssertEqual(appleHealthDaily.count, 1)
        XCTAssertEqual(myWhoopDaily.count, 1)
        var appleHealthSeries = try await underlying.metricSeries(deviceId: "apple-health", key: "steps", from: "2026-06-01", to: "2026-06-30")
        XCTAssertEqual(appleHealthSeries.count, 1)

        try await store.deleteAllData(deviceId: "apple-health")

        // The apple-health rows are gone everywhere; my-whoop's rows survive.
        appleHealthDaily = try await underlying.appleDaily(deviceId: "apple-health", from: "2026-06-01", to: "2026-06-30")
        myWhoopDaily = try await underlying.appleDaily(deviceId: "my-whoop", from: "2026-06-01", to: "2026-06-30")
        appleHealthSeries = try await underlying.metricSeries(deviceId: "apple-health", key: "steps", from: "2026-06-01", to: "2026-06-30")
        let myWhoopSeries = try await underlying.metricSeries(deviceId: "my-whoop", key: "steps", from: "2026-06-01", to: "2026-06-30")
        XCTAssertEqual(appleHealthDaily.count, 0)
        XCTAssertEqual(appleHealthSeries.count, 0)
        XCTAssertEqual(myWhoopDaily.count, 1)
        XCTAssertEqual(myWhoopSeries.count, 1)

        // The registry row itself is never touched by a delete-data op (the seeded my-whoop remains).
        let registryCount = try await store.all().count
        XCTAssertEqual(registryCount, 1)
        let activeId = try await store.activeDeviceId()
        XCTAssertEqual(activeId, "my-whoop")
    }

    func testDayOwnershipUpsertAndRead() async throws {
        let store = try await makeStore()
        try await store.setDayOwner(day: "2026-06-15", deviceId: "my-whoop", locked: true)
        let owner1 = try await store.dayOwner("2026-06-15")
        XCTAssertEqual(owner1?.deviceId, "my-whoop")
        XCTAssertEqual(owner1?.locked, true)
        let noOwner = try await store.dayOwner("2000-01-01")
        XCTAssertNil(noOwner)
        // upsert: re-writing the same day replaces the owner + locked flag (no duplicate row)
        try await store.setDayOwner(day: "2026-06-15", deviceId: "polar-1", locked: false)
        let owner2 = try await store.dayOwner("2026-06-15")
        XCTAssertEqual(owner2?.deviceId, "polar-1")
        XCTAssertEqual(owner2?.locked, false)
    }
}
