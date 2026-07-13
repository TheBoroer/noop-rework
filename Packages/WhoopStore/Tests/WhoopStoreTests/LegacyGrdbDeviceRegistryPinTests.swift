import XCTest
@testable import WhoopStore

/// Task 6: the PINNED legacy-mode subset for DeviceRegistryStore.swift. DeviceRegistryStoreTests now
/// runs against the ROOM backend; this file keeps the GRDB fallback branch of every delegated registry
/// method covered by running a condensed but representative slice of the same assertions against
/// `WhoopStore.inMemory()`, which is always `.legacyGrdb` (and applies the full migrator, including the
/// v15 seed of the "my-whoop" active row).
final class LegacyGrdbDeviceRegistryPinTests: XCTestCase {

    private func legacyStore() async throws -> WhoopStore {
        let store = try await WhoopStore.inMemory()
        guard case .legacyGrdb = store.storageBackend else {
            XCTFail("inMemory() must be the legacy GRDB backend")
            throw XCTSkip("unreachable")
        }
        return store
    }

    /// Fresh install seeds "my-whoop" active (v15 migration); promoting another device demotes it to
    /// paired, never deletes it, and I1 (at most one active) holds.
    func testLegacySeededWhoopActiveAndSetActiveEnforcesSingleActive() async throws {
        let registry = DeviceRegistryStore(store: try await legacyStore())
        let seededIds = try await registry.all().map(\.id)
        XCTAssertEqual(seededIds, ["my-whoop"])
        let seededActive = try await registry.activeDeviceId()
        XCTAssertEqual(seededActive, "my-whoop")

        try await registry.add(PairedDevice(id: "polar-1", brand: "Polar", model: "H10", sourceKind: .liveBLE,
                                            capabilities: [.hr, .hrv], status: .paired, addedAt: 1, lastSeenAt: 1))
        try await registry.setActive("polar-1")

        let activeId = try await registry.activeDeviceId()
        XCTAssertEqual(activeId, "polar-1")
        let statuses = Dictionary(uniqueKeysWithValues: try await registry.all().map { ($0.id, $0.status) })
        XCTAssertEqual(statuses["polar-1"], .active)
        XCTAssertEqual(statuses["my-whoop"], .paired)   // demoted, not deleted
        let activeCount = try await registry.all().filter { $0.status == .active }.count
        XCTAssertEqual(activeCount, 1)  // I1
    }

    /// I4: archiving keeps the row (never a hard delete) and clears activeDeviceId.
    func testLegacyArchiveKeepsRowAndClearsActive() async throws {
        let registry = DeviceRegistryStore(store: try await legacyStore())
        try await registry.archive("my-whoop")
        let allDevices = try await registry.all()
        XCTAssertEqual(allDevices.first?.status, .archived)
        let activeId = try await registry.activeDeviceId()
        XCTAssertNil(activeId)
    }

    /// peripheralId adoption round-trips through add/setPeripheralId, nil un-adopts it, and
    /// device(forPeripheralId:) resolves a connected BLE peripheral back to its registry row.
    func testLegacyPeripheralIdRoundTripAndLookup() async throws {
        let registry = DeviceRegistryStore(store: try await legacyStore())
        let pid = "11111111-2222-3333-4444-555555555555"
        let none = try await registry.device(forPeripheralId: pid)
        XCTAssertNil(none)

        try await registry.setPeripheralId("my-whoop", peripheralId: pid)
        let afterSet = try await registry.all().first { $0.id == "my-whoop" }
        XCTAssertEqual(afterSet?.peripheralId, pid)
        let found = try await registry.device(forPeripheralId: pid)
        XCTAssertEqual(found?.id, "my-whoop")

        try await registry.setPeripheralId("my-whoop", peripheralId: nil)
        let afterClear = try await registry.all().first { $0.id == "my-whoop" }
        XCTAssertNil(afterClear?.peripheralId)
        let notFound = try await registry.device(forPeripheralId: pid)
        XCTAssertNil(notFound)
    }

    /// ah-delete (#616), legacy branch: deleteAllData(deviceId:) clears a representative deviceId-keyed
    /// table (appleDaily) for only the target device, leaving another device's rows and the registry
    /// row itself untouched.
    func testLegacyDeleteAllDataClearsOnlyTargetDevicesRows() async throws {
        let underlying = try await legacyStore()
        let registry = DeviceRegistryStore(store: underlying)

        for dev in ["apple-health", "my-whoop"] {
            _ = try await underlying.upsertAppleDaily(
                [AppleDaily(day: "2026-06-15", steps: 1234, activeKcal: nil, basalKcal: nil, vo2max: nil,
                           avgHr: nil, maxHr: nil, walkingHr: nil, weightKg: nil)], deviceId: dev)
        }
        let beforeCount = try await underlying.appleDaily(deviceId: "apple-health", from: "2026-06-01", to: "2026-06-30").count
        XCTAssertEqual(beforeCount, 1)

        try await registry.deleteAllData(deviceId: "apple-health")

        let appleHealthAfter = try await underlying.appleDaily(deviceId: "apple-health", from: "2026-06-01", to: "2026-06-30").count
        XCTAssertEqual(appleHealthAfter, 0)
        let myWhoopAfter = try await underlying.appleDaily(deviceId: "my-whoop", from: "2026-06-01", to: "2026-06-30").count
        XCTAssertEqual(myWhoopAfter, 1)
        let registryCount = try await registry.all().count
        XCTAssertEqual(registryCount, 1)   // the registry row is never touched
        let activeId = try await registry.activeDeviceId()
        XCTAssertEqual(activeId, "my-whoop")
    }

    /// Day ownership upsert-by-natural-key: re-writing the same day replaces the owner + locked flag
    /// (no duplicate row).
    func testLegacyDayOwnershipUpsertAndRead() async throws {
        let registry = DeviceRegistryStore(store: try await legacyStore())
        try await registry.setDayOwner(day: "2026-06-15", deviceId: "my-whoop", locked: true)
        let owner1 = try await registry.dayOwner("2026-06-15")
        XCTAssertEqual(owner1?.deviceId, "my-whoop")
        XCTAssertEqual(owner1?.locked, true)
        let noOwner = try await registry.dayOwner("2000-01-01")
        XCTAssertNil(noOwner)

        try await registry.setDayOwner(day: "2026-06-15", deviceId: "polar-1", locked: false)
        let owner2 = try await registry.dayOwner("2026-06-15")
        XCTAssertEqual(owner2?.deviceId, "polar-1")
        XCTAssertEqual(owner2?.locked, false)
    }
}
