import Foundation
import GRDB
import Shared

// MARK: - Device registry (pairedDevice / dayOwnership)
//
// DeviceRegistryStore.swift -- Task 6 (Phase 2c-1): kills the "sync bypass" this store used to be. It
// used to open its own synchronous handle directly onto WhoopStore's underlying GRDB writer
// (`WhoopStore.registryWriter`), skipping BOTH actor serialization and the Room backend entirely --
// harmless while every backend was GRDB, but incompatible with a suspend-based Room backend (the whole
// point of the cutover). Now a thin `Sendable` struct wrapping the `WhoopStore` actor itself: every
// method is `async` and routes through a matching `extension WhoopStore` method below, which switches
// on `backend` exactly like every other delegated store (LiveSessionStore.swift is the canonical
// reference; Task 4/5). The legacy branch keeps the original synchronous GRDB SQL verbatim (via
// `syncRead`/`syncWrite`); the Room branch calls `roomDb.whoopDao()` directly, the same surface every
// prior delegated store uses.
//
// I1 (at most one `.active`) and the 17-table `deleteAllData` fan-out both need one atomic transaction
// across two-plus DAO calls. Rather than bridge Kotlin's `DeviceRegistry` façade class from Swift (its
// `Transactor` abstraction is not a `fun interface` -- its one method is generic, so it isn't
// SAM-convertible -- and would be an unproven SKIE bridging surface), these two are exposed as
// `@Transaction`-annotated composite suspend methods directly on `WhoopDao` (`setActiveAtomic`,
// `deleteDeviceDataAtomic`, see WhoopDao.kt) and called through the same `roomDb.whoopDao()` surface as
// everything else, keeping this task's Swift-facing risk consistent with every prior delegated store.

public struct DeviceRegistryStore: Sendable {
    private let store: WhoopStore
    public init(store: WhoopStore) { self.store = store }

    public func all() async throws -> [PairedDevice] { try await store.registryAll() }
    public func activeDeviceId() async throws -> String? { try await store.registryActiveDeviceId() }
    public func add(_ d: PairedDevice) async throws { try await store.registryAdd(d) }

    /// I1: promoting one device demotes whatever was active, atomically (single write transaction).
    public func setActive(_ id: String) async throws { try await store.registrySetActive(id) }

    public func archive(_ id: String) async throws { try await store.registryArchive(id) }

    public func rename(_ id: String, nickname: String?) async throws {
        try await store.registryRename(id, nickname: nickname)
    }

    /// Adopt (or clear) the stable BLE identity for a registry row. `peripheralId` is the
    /// CBPeripheral.identifier.uuidString on iOS/Mac; passing nil un-adopts it.
    public func setPeripheralId(_ id: String, peripheralId: String?) async throws {
        try await store.registrySetPeripheralId(id, peripheralId: peripheralId)
    }

    /// Find the registry row that has adopted a given BLE peripheral, if any. Used to map a
    /// connected CBPeripheral back to its `PairedDevice` so multiple straps stay distinct.
    public func device(forPeripheralId peripheralId: String) async throws -> PairedDevice? {
        try await store.registryDevice(forPeripheralId: peripheralId)
    }

    /// Permanently delete every recorded sample/derived row belonging to one device, across all
    /// `deviceId`-keyed tables, in a single transaction (all-or-nothing). The `pairedDevice` registry
    /// row is left intact -- the caller archives/removes that separately. See
    /// `WhoopStore.deleteAllData(deviceId:)` for the backend-switched implementation.
    public func deleteAllData(deviceId: String) async throws {
        try await store.deleteAllData(deviceId: deviceId)
    }

    // MARK: day ownership
    public struct DayOwner: Equatable, Sendable { public let deviceId: String; public let locked: Bool }

    public func setDayOwner(day: String, deviceId: String, locked: Bool) async throws {
        try await store.registrySetDayOwner(day: day, deviceId: deviceId, locked: locked)
    }

    public func dayOwner(_ day: String) async throws -> DayOwner? {
        try await store.registryDayOwner(day)
    }
}

extension WhoopStore {

    /// Every table whose rows are keyed by `deviceId` (the per-device sample/derived tables). This is
    /// the authoritative list `deleteAllData`'s legacy branch clears -- kept in sync with the
    /// `deviceId`-keyed tables in `Database.swift`, and with the Room side's individual `delete*For`
    /// DAO methods (`DeviceRegistryDao.kt`) that `deleteDeviceDataAtomic` fans out to. The `pairedDevice`
    /// registry row itself is NOT here (a delete-data operation empties the device's recordings;
    /// archiving/removing the registry entry is a separate op).
    static let deviceScopedTables = [
        "hrSample", "rrInterval", "spo2Sample", "skinTempSample", "respSample", "gravitySample",
        "stepSample", "ppgHrSample", "event", "battery", "dailyMetric", "sleepSession",
        "journal", "workout", "appleDaily", "metricSeries", "dayOwnership",
    ]

    func registryAll() async throws -> [PairedDevice] {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let rows = try await roomDb.whoopDao().pairedDevices()
            return rows.map(Self.decodeRoom)
            #endif
        case .legacyGrdb:
            return try syncRead { db in
                try Row.fetchAll(db, sql: "SELECT * FROM pairedDevice ORDER BY addedAt ASC").map(Self.decodeGrdb)
            }
        }
    }

    func registryActiveDeviceId() async throws -> String? {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            return try await roomDb.whoopDao().activeDeviceId()
            #endif
        case .legacyGrdb:
            return try syncRead { db in
                try String.fetchOne(db, sql: "SELECT id FROM pairedDevice WHERE status = 'active' LIMIT 1")
            }
        }
    }

    func registryAdd(_ d: PairedDevice) async throws {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            try await roomDb.whoopDao().upsertPairedDevice(row: Self.encodeRoom(d))
            #endif
        case .legacyGrdb:
            try syncWrite { db in try Self.upsertGrdb(db, d) }
        }
    }

    /// I1: promoting one device demotes whatever was active, atomically (single write transaction).
    func registrySetActive(_ id: String) async throws {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            try await roomDb.whoopDao().setActiveAtomic(id: id, now: Int64(Date().timeIntervalSince1970))
            #endif
        case .legacyGrdb:
            try syncWrite { db in
                try db.execute(sql: "UPDATE pairedDevice SET status = 'paired' WHERE status = 'active'")
                try db.execute(sql: "UPDATE pairedDevice SET status = 'active', lastSeenAt = ? WHERE id = ?",
                               arguments: [Int(Date().timeIntervalSince1970), id])
            }
        }
    }

    func registryArchive(_ id: String) async throws {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            try await roomDb.whoopDao().archiveDevice(id: id)
            #endif
        case .legacyGrdb:
            try syncWrite { db in
                try db.execute(sql: "UPDATE pairedDevice SET status = 'archived' WHERE id = ?", arguments: [id])
            }
        }
    }

    func registryRename(_ id: String, nickname: String?) async throws {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            try await roomDb.whoopDao().renameDevice(id: id, nickname: nickname)
            #endif
        case .legacyGrdb:
            try syncWrite { db in
                try db.execute(sql: "UPDATE pairedDevice SET nickname = ? WHERE id = ?", arguments: [nickname, id])
            }
        }
    }

    func registrySetPeripheralId(_ id: String, peripheralId: String?) async throws {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            try await roomDb.whoopDao().setPeripheralId(id: id, peripheralId: peripheralId)
            #endif
        case .legacyGrdb:
            try syncWrite { db in
                try db.execute(sql: "UPDATE pairedDevice SET peripheralId = ? WHERE id = ?",
                               arguments: [peripheralId, id])
            }
        }
    }

    func registryDevice(forPeripheralId peripheralId: String) async throws -> PairedDevice? {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            guard let row = try await roomDb.whoopDao().deviceForPeripheralId(peripheralId: peripheralId)
            else { return nil }
            return Self.decodeRoom(row)
            #endif
        case .legacyGrdb:
            return try syncRead { db in
                try Row.fetchOne(db, sql: "SELECT * FROM pairedDevice WHERE peripheralId = ? LIMIT 1",
                                 arguments: [peripheralId]).map(Self.decodeGrdb)
            }
        }
    }

    /// Permanently delete every recorded sample/derived row for one device across all `deviceId`-keyed
    /// tables, in a single transaction (all-or-nothing). The `pairedDevice` registry row is left intact
    /// (archiving/removing it is a separate op). The "Delete all of this device's data" and "Remove
    /// Apple Health data" actions previously ran this synchronously on the main actor and froze the UI
    /// on a large dataset; this runs on the actor's own serial executor, off the main thread, either way.
    ///
    /// Task 6 fix: this used to unconditionally build a `DeviceRegistryStore` over the legacy `dbWriter`
    /// regardless of `backend`, so a Room-backed store's "delete all data" silently only cleared legacy
    /// GRDB tables (stale/absent once Room is live) and never touched Room. Now properly backend-switched,
    /// same as every other method here.
    public func deleteAllData(deviceId: String) async throws {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            try await roomDb.whoopDao().deleteDeviceDataAtomic(deviceId: deviceId)
            #endif
        case .legacyGrdb:
            try syncWrite { db in
                for table in Self.deviceScopedTables {
                    try db.execute(sql: "DELETE FROM \(table) WHERE deviceId = ?", arguments: [deviceId])
                }
            }
        }
    }

    // MARK: day ownership

    func registrySetDayOwner(day: String, deviceId: String, locked: Bool) async throws {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            try await roomDb.whoopDao().setDayOwner(
                row: Shared.DayOwnershipRow(day: day, deviceId: deviceId, locked: locked))
            #endif
        case .legacyGrdb:
            try syncWrite { db in
                try db.execute(sql: """
                    INSERT INTO dayOwnership (day, deviceId, locked) VALUES (?, ?, ?)
                    ON CONFLICT(day) DO UPDATE SET deviceId = excluded.deviceId, locked = excluded.locked
                """, arguments: [day, deviceId, locked ? 1 : 0])
            }
        }
    }

    func registryDayOwner(_ day: String) async throws -> DeviceRegistryStore.DayOwner? {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            guard let row = try await roomDb.whoopDao().dayOwner(day: day) else { return nil }
            return DeviceRegistryStore.DayOwner(deviceId: row.deviceId, locked: row.locked)
            #endif
        case .legacyGrdb:
            return try syncRead { db in
                guard let row = try Row.fetchOne(db, sql: "SELECT deviceId, locked FROM dayOwnership WHERE day = ?",
                                                  arguments: [day])
                else { return nil }
                return DeviceRegistryStore.DayOwner(deviceId: row["deviceId"], locked: (row["locked"] as Int) == 1)
            }
        }
    }

    // MARK: mapping

    fileprivate static func upsertGrdb(_ db: Database, _ d: PairedDevice) throws {
        try db.execute(sql: """
            INSERT INTO pairedDevice (id, brand, model, nickname, peripheralId, sourceKind, capabilities, status, addedAt, lastSeenAt)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET brand=excluded.brand, model=excluded.model, nickname=excluded.nickname,
                peripheralId=excluded.peripheralId, sourceKind=excluded.sourceKind, capabilities=excluded.capabilities,
                status=excluded.status, lastSeenAt=excluded.lastSeenAt
        """, arguments: [d.id, d.brand, d.model, d.nickname, d.peripheralId, d.sourceKind.rawValue,
                         d.capabilities.map(\.rawValue).sorted().joined(separator: ","),
                         d.status.rawValue, d.addedAt, d.lastSeenAt])
    }

    fileprivate static func decodeGrdb(_ row: Row) -> PairedDevice {
        let caps = (row["capabilities"] as String).split(separator: ",").compactMap { Metric(rawValue: String($0)) }
        return PairedDevice(id: row["id"], brand: row["brand"], model: row["model"], nickname: row["nickname"],
                            peripheralId: row["peripheralId"],
                            sourceKind: SourceKind(rawValue: row["sourceKind"]) ?? .liveBLE,
                            capabilities: Set(caps), status: DeviceStatus(rawValue: row["status"]) ?? .paired,
                            addedAt: row["addedAt"], lastSeenAt: row["lastSeenAt"])
    }

    fileprivate static func encodeRoom(_ d: PairedDevice) -> Shared.PairedDeviceRow {
        Shared.PairedDeviceRow(id: d.id, brand: d.brand, model: d.model, nickname: d.nickname,
                                peripheralId: d.peripheralId, sourceKind: d.sourceKind.rawValue,
                                capabilities: d.capabilities.map(\.rawValue).sorted().joined(separator: ","),
                                status: d.status.rawValue, addedAt: Int64(d.addedAt), lastSeenAt: Int64(d.lastSeenAt))
    }

    fileprivate static func decodeRoom(_ row: Shared.PairedDeviceRow) -> PairedDevice {
        let caps = row.capabilities.split(separator: ",").compactMap { Metric(rawValue: String($0)) }
        return PairedDevice(id: row.id, brand: row.brand, model: row.model, nickname: row.nickname,
                            peripheralId: row.peripheralId,
                            sourceKind: SourceKind(rawValue: row.sourceKind) ?? .liveBLE,
                            capabilities: Set(caps), status: DeviceStatus(rawValue: row.status) ?? .paired,
                            addedAt: Int(row.addedAt), lastSeenAt: Int(row.lastSeenAt))
    }
}
