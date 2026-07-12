import Foundation
import GRDB
import WhoopProtocol
import Shared

/// OpenWhoop persistence library — decoded streams are durable; raw frames are a
/// transient, compressed, prunable outbox. Built on GRDB/SQLite.
public enum WhoopStoreInfo {
    /// Bumped whenever the migrator gains a new migration.
    public static let schemaVersion = 18
}

/// WhoopStore is an `actor`: its public API is `async`, and all GRDB work runs on the
/// actor's serial executor rather than the caller's (the main actor).
///
/// The connection is a GRDB `DatabasePool` (WAL): reads (`.read`) run CONCURRENTLY with the
/// backfill's bulk writes (`.write`) instead of serializing behind them (#755). A `DatabaseQueue`
/// funnels every read AND write through one serial executor, so the dashboard's ~40-55 reads
/// queued behind a multi-thousand-row import and froze Today for seconds. A Pool keeps a single
/// writer (writes still serialize, exactly as before, so every read-modify-write inside one
/// `.write` stays atomic) but serves reads from WAL snapshots in parallel (committed data only,
/// never a partial write). The actor still moves the synchronous-blocking GRDB calls off the
/// caller's (main) thread; what changed is read/write CONCURRENCY at the SQLite layer, not the
/// data or the query results.
public actor WhoopStore {
    let dbWriter: any DatabaseWriter

    /// Read-only handle to the underlying GRDB writer for the synchronous `DeviceRegistryStore`.
    /// `nonisolated` because a GRDB `DatabaseWriter` (here a `DatabasePool`) is `Sendable` and
    /// manages its own concurrency, so concurrent access alongside the actor's own DB work is safe
    /// (the Pool serializes writes and runs reads in parallel under WAL).
    public nonisolated var registryWriter: any DatabaseWriter { dbWriter }

    // MARK: - Room cutover (Phase 2c-1 Task 3)
    //
    // Every open decides which store serves the actor's per-feature methods (later tasks switch on
    // `backend`; this phase only decides it, the GRDB methods below are all still current code): a
    // fresh install or an already-migrated launch opens the shared Kotlin/Room database directly; a
    // legacy-only install runs a ONE-TIME ETL (Task 2's `GrdbMigrator`) with a progress stream the
    // app can show; an ETL failure falls back to the GRDB backend the store has always used.
    // `dbWriter` above stays open and migrated in EVERY case: rawBatch/cursors (the raw-frame
    // outbox) live there regardless of backend, and a fallback store must be fully functional on it.

    /// Which store serves this instance's per-feature methods. Internal: later tasks (Streams,
    /// Metrics, Device registry, Backup) switch on this to pick the GRDB or the Room code path.
    /// `.room` carries the live Kotlin `WhoopDatabase` handle so those methods don't need to reopen
    /// it. `.legacyGrdb` needs no payload of its own: `dbWriter` above already IS that connection,
    /// always open, whichever case this is.
    enum Backend {
        case room(WhoopDatabase)
        case legacyGrdb
    }
    let backend: Backend

    /// Public diagnostic surface (Settings / support screens, and this init's own tests): which
    /// backend actually ended up live, and why. `nonisolated`: decided once, at init, and never
    /// changes afterward, so callers can read it without an `await`.
    public enum StorageBackend: Equatable, Sendable {
        /// Room is live: a fresh install, a completed one-time ETL, or a prior launch already
        /// finished the cutover.
        case room
        /// Every read/write still hits the legacy GRDB store. `fallbackReason` is `nil` for the
        /// in-memory test store (a deliberate choice, not a failure); non-nil when a legacy-only
        /// launch's ETL failed partway. The store is fully functional either way; a non-nil reason
        /// means the NEXT launch retries the cutover (no partial Room file is left behind to skip
        /// past the retry).
        case legacyGrdb(fallbackReason: String?)
    }
    public nonisolated let storageBackend: StorageBackend

    /// Progress of an in-flight one-time ETL, `0...1`, or `nil` when this open ran no migration
    /// (fresh install, already-Room, or the in-memory test store). The stream finishes once the ETL
    /// returns, whether it succeeded or failed. `nonisolated` for the same reason as
    /// `storageBackend`: fixed at init, safe to read without hopping onto the actor.
    public nonisolated let migrationProgress: AsyncStream<Double>?

    /// Number of tables `GrdbMigrator.kt`'s `TABLES` list copies (Phase 2c-1 Task 2: 22 as of this
    /// writing). Kotlin's `Progress` callback only reports the CURRENT table's own row count, not a
    /// whole-migration total, so this constant is what turns a stream of per-table callbacks into
    /// one overall fraction. If Kotlin's mapped-table list ever grows or shrinks, bump this
    /// alongside it: a drift shows up immediately as `migrationProgress`'s last reported value no
    /// longer reaching 1.0, which the init-path tests pin.
    private static let migratedTableCount = 22

    private init(dbWriter: any DatabaseWriter, backend: Backend, storageBackend: StorageBackend,
                 migrationProgress: AsyncStream<Double>?) {
        self.dbWriter = dbWriter
        self.backend = backend
        self.storageBackend = storageBackend
        self.migrationProgress = migrationProgress
    }

    /// Open (creating if needed) a database at `path` and run migrations, then detect-migrate-open
    /// the shared Room store alongside it (Task 3): a fresh install and a both-files-present launch
    /// both open Room directly (no re-ETL); a legacy-only launch runs the one-time ETL (progress on
    /// `migrationProgress`); an ETL failure falls back to `.legacyGrdb` rather than throwing, so the
    /// store is always usable once this returns. Uses a `DatabasePool` for the legacy handle, which
    /// enables WAL automatically, plus a 5-second busy timeout so two handles to the same file
    /// (BLEManager + MetricsRepository) don't deadlock on write contention.
    public init(path: String) async throws {
        // Self-heal a foreign DB left in place by a bad cross-platform restore (#222): an Android
        // (Room) backup that slipped past the import guard replaces our file with one that has our
        // data tables but NO `grdb_migrations` bookkeeping. The migrator then thinks nothing is
        // applied, re-runs v1, and crashes with `table "device" already exists` on every open — the
        // store never bootstraps. Quarantine such a file BEFORE opening so we start fresh instead of
        // looping forever. (A normal GRDB backup carries grdb_migrations and is left untouched.)
        WhoopStore.quarantineIncompatibleDatabase(at: path)

        let fm = FileManager.default
        let roomPath = WhoopStore.roomPath(forLegacyPath: path)
        // Snapshot existence BEFORE anything below creates either file, so "fresh install" is never
        // mistaken for "legacy-only" just because opening the GRDB pool below creates `path`.
        let legacyExistedBefore = fm.fileExists(atPath: path)
        let roomExistedBefore = fm.fileExists(atPath: roomPath)

        var config = Configuration()
        config.prepareDatabase { db in
            // `DatabasePool` puts the database in WAL mode itself (reads run as concurrent snapshots
            // alongside the single writer, #755), so there is no explicit `PRAGMA journal_mode = WAL`.
            // Bulk-write/read tuning. NORMAL is the durable, recommended pairing with WAL (only an
            // OS crash/power loss can lose the last transaction — acceptable here). Bigger page cache
            // + mmap + in-memory temp tables speed the multi-thousand-row import/backfill writes.
            try db.execute(sql: "PRAGMA synchronous = NORMAL")
            try db.execute(sql: "PRAGMA cache_size = -16000")     // ~16 MB page cache
            try db.execute(sql: "PRAGMA mmap_size = 268435456")   // 256 MB memory-mapped I/O
            try db.execute(sql: "PRAGMA temp_store = MEMORY")
        }
        config.busyMode = .timeout(5)
        let legacyPool = try DatabasePool(path: path, configuration: config)
        // Always applied, regardless of which backend ends up live below: rawBatch/cursors are
        // GRDB-only forever (Task 2), and a `.legacyGrdb` fallback must be on the CURRENT schema.
        try WhoopStore.makeMigrator().migrate(legacyPool)

        if roomExistedBefore {
            // Both files present, or a prior launch already finished the cutover: open Room as-is,
            // no re-ETL (re-running it would blow away any Room-only writes made since that launch).
            let room = whoopDatabase(path: roomPath)
            self.init(dbWriter: legacyPool, backend: .room(room), storageBackend: .room,
                      migrationProgress: nil)
        } else if !legacyExistedBefore {
            // Fresh install: nothing to migrate. `whoopDatabase` builds the Room handle, but Room
            // opens its connection lazily on first use, not inside `.build()`; force it now so the
            // file genuinely exists on disk once this init returns, matching the init-matrix
            // contract ("fresh install creates Room").
            let room = whoopDatabase(path: roomPath)
            try await WhoopStore.warmUpRoomDatabase(room)
            self.init(dbWriter: legacyPool, backend: .room(room), storageBackend: .room,
                      migrationProgress: nil)
        } else {
            // Legacy-only: one-time ETL. `GrdbMigrator.migrate` is a blocking Kotlin call that
            // invokes `onProgress` synchronously, once per committed batch, on this same thread;
            // bridge those callbacks into an `AsyncStream` the app can observe.
            var progressContinuation: AsyncStream<Double>.Continuation?
            let progressStream = AsyncStream<Double> { progressContinuation = $0 }
            var lastTable: String?
            var tableIndex = -1
            let result = GrdbMigrator.shared.migrate(legacyPath: path, roomPath: roomPath) { progress in
                if progress.table != lastTable {
                    tableIndex += 1
                    lastTable = progress.table
                }
                let tableFraction = progress.totalEstimate > 0
                    ? Double(progress.copied) / Double(progress.totalEstimate)
                    : 1.0 // an empty table reports its one completion callback as already done.
                let overall = (Double(tableIndex) + tableFraction) / Double(WhoopStore.migratedTableCount)
                progressContinuation?.yield(min(max(overall, 0), 1))
            }
            progressContinuation?.finish()

            switch onEnum(of: result) {
            case .done:
                let room = whoopDatabase(path: roomPath)
                self.init(dbWriter: legacyPool, backend: .room(room), storageBackend: .room,
                          migrationProgress: progressStream)
            case .failed(let failure):
                // Never leave a partial Room file behind: the next launch must see "no Room file"
                // (the legacy-only branch above) and retry cleanly, not "Room exists" (which would
                // skip straight past the retry with a half-populated store).
                WhoopStore.deletePartialRoomDatabase(at: roomPath)
                let reason = "\(failure.table): \(failure.message)"
                self.init(dbWriter: legacyPool, backend: .legacyGrdb,
                          storageBackend: .legacyGrdb(fallbackReason: reason),
                          migrationProgress: progressStream)
            }
        }
    }

    /// Forces Room's on-disk file to actually exist: Room's builder opens its bundled-driver
    /// connection lazily on first real use, not inside `.build()` itself, so a fresh (empty) Room
    /// database otherwise wouldn't be on disk yet when `init(path:)` returns. A cheap DAO read is
    /// enough to open the connection; the fresh schema always has zero rows.
    private static func warmUpRoomDatabase(_ room: WhoopDatabase) async throws {
        _ = try await room.whoopDao().countHr()
    }

    /// The Room sibling of a legacy GRDB path: same directory, filename `noop.db` (mirrors
    /// `StorePaths.roomDatabasePath()` in the app target, which this package cannot import).
    private static func roomPath(forLegacyPath legacyPath: String) -> String {
        URL(fileURLWithPath: legacyPath)
            .deletingLastPathComponent()
            .appendingPathComponent("noop.db")
            .path
    }

    /// Removes a Room database file (plus its `-wal`/`-shm`/`-journal` sidecars) left behind by an
    /// ETL that failed partway, so the next launch's existence check sees "no Room file" and retries
    /// the cutover instead of opening a half-populated store. Best-effort + silent, matching
    /// `quarantineIncompatibleDatabase`'s style: `GrdbMigrator` always closes its handle before
    /// returning `Failed`, so the file is never locked when this runs.
    private static func deletePartialRoomDatabase(at path: String) {
        let fm = FileManager.default
        for suffix in ["", "-wal", "-shm", "-journal"] {
            try? fm.removeItem(atPath: path + suffix)
        }
    }

    /// Move aside a database file that has our data tables but no GRDB migration bookkeeping — the
    /// signature of a foreign (Android/Room) DB dropped over ours by a bad restore (#222). Opening it
    /// would make the migrator re-run v1 and throw `table "device" already exists` forever. Moving it
    /// to a `.incompatible-<ts>` sidecar lets the next open create a clean store. A valid GRDB DB
    /// (has `grdb_migrations`) and a fresh/empty file are both left untouched. Best-effort + silent.
    static func quarantineIncompatibleDatabase(at path: String) {
        let fm = FileManager.default
        guard fm.fileExists(atPath: path) else { return }
        let names: Set<String>
        do {
            // Read-only probe of sqlite_master; a raw queue does NOT run migrations.
            let probe = try DatabaseQueue(path: path)
            names = try probe.read { db in
                try Set(String.fetchAll(db, sql: "SELECT name FROM sqlite_master WHERE type = 'table'"))
            }
        } catch {
            return // unreadable/locked → let the real open + migrator deal with it
        }
        let isForeign = !names.contains("grdb_migrations")
            && (names.contains("device") || names.contains("hrSample"))
        guard isForeign else { return }
        let stamp = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "")
        let quarantine = "\(path).incompatible-\(stamp)"
        try? fm.removeItem(atPath: quarantine)
        do { try fm.moveItem(atPath: path, toPath: quarantine) } catch { return }
        // Drop the now-orphaned WAL/SHM sidecars so the fresh DB starts clean.
        for suffix in ["-wal", "-shm"] { try? fm.removeItem(atPath: path + suffix) }
    }

    /// An in-memory store (migrations applied). For tests. Always `.legacyGrdb`: there is no file
    /// path to derive a Room sibling from, and every existing test exercising the GRDB data methods
    /// expects them to keep running unchanged against this queue.
    ///
    /// Backed by a `DatabaseQueue`, not a `DatabasePool`: GRDB has no in-memory `DatabasePool`
    /// (a Pool needs a real file so its reader connections can open WAL snapshots of it). A
    /// `DatabaseQueue` is also a `DatabaseWriter`, so this is API-identical; only the concurrency
    /// differs, which an in-memory test store doesn't exercise. The production `init(path:)` path
    /// is the one that gets the Pool (#755). Tests that need real read/write concurrency open a
    /// file-backed Pool directly.
    public static func inMemory() async throws -> WhoopStore {
        let dbWriter = try DatabaseQueue()
        try WhoopStore.makeMigrator().migrate(dbWriter)
        return WhoopStore(dbWriter: dbWriter, backend: .legacyGrdb,
                           storageBackend: .legacyGrdb(fallbackReason: nil), migrationProgress: nil)
    }

    // MARK: - Synchronous GRDB helpers
    // GRDB 6 marks its sync read/write overloads @_disfavoredOverload so that in an async
    // context Swift would otherwise pick the async overloads. These thin wrappers are
    // regular (non-async) functions, so overload resolution always selects the synchronous
    // GRDB API — which then blocks on the actor's serial executor (off main thread).

    @inline(__always)
    func syncRead<T>(_ block: (Database) throws -> T) throws -> T {
        try dbWriter.read(block)
    }

    @inline(__always)
    func syncWrite<T>(_ block: (Database) throws -> T) throws -> T {
        try dbWriter.write(block)
    }

    // MARK: - Maintenance

    /// Fully checkpoint the WAL into the main database file and truncate the -wal file.
    /// Used before a file-level backup so the single `whoop.sqlite` carries all committed data
    /// (the -wal/-shm siblings can then be ignored). Runs outside a transaction — `wal_checkpoint`
    /// must. Best-effort: throws on a hard SQLite error so callers can fall back to a plain copy.
    public func checkpointWAL() async throws {
        try checkpointWALImpl()
    }

    /// Non-async so GRDB's synchronous `writeWithoutTransaction` overload is chosen (mirrors the
    /// syncRead/syncWrite pattern). Runs on the actor's executor, off the main thread.
    private func checkpointWALImpl() throws {
        try dbWriter.writeWithoutTransaction { db in
            try db.execute(sql: "PRAGMA wal_checkpoint(TRUNCATE)")
        }
    }

    /// Permanently delete every recorded sample/derived row for one device across all `deviceId`-keyed
    /// tables (16+ `DELETE FROM <table> WHERE deviceId = ?` in one GRDB transaction). Wraps the
    /// synchronous `DeviceRegistryStore.deleteAllData` so the heavy multi-table write runs on the actor's
    /// own serial executor, OFF the main thread. The "Delete all of this device's data" and "Remove
    /// Apple Health data" actions previously ran this same store write synchronously on the main actor and
    /// froze the UI on a large dataset. The `pairedDevice` registry row is left intact (archiving/removing
    /// it is a separate op). Async entry point; the actual write is on `deleteAllDataImpl`.
    public func deleteAllData(deviceId: String) async throws {
        try deleteAllDataImpl(deviceId: deviceId)
    }

    /// Non-async so the synchronous `DeviceRegistryStore.deleteAllData` (a blocking GRDB write) is called
    /// directly (mirrors the syncRead/syncWrite pattern). Runs on the actor's executor, off the main
    /// thread. Builds the synchronous registry wrapper over the same GRDB writer the store owns.
    private func deleteAllDataImpl(deviceId: String) throws {
        try DeviceRegistryStore(dbQueue: dbWriter).deleteAllData(deviceId: deviceId)
    }

    /// Total on-disk size of the database — the main file plus its `-wal`/`-shm` siblings — in bytes.
    /// Drives the iOS Storage diagnostics screen (#590). `nil` for an in-memory store (no path). Runs
    /// on the actor's executor, off the main thread. Note (#755): under the `DatabasePool` the `-wal`
    /// component can stay non-zero while a reader connection holds an open snapshot, so a `checkpointWAL`
    /// may not fully truncate it; this total stays correct (it always includes the sidecars) but can
    /// read a little higher than the old single-connection `DatabaseQueue` did right after a checkpoint.
    public func databaseFileSizeBytes() async -> Int64? {
        let base = dbWriter.path
        guard base != ":memory:", !base.isEmpty else { return nil }
        let fm = FileManager.default
        var total: Int64 = 0
        var found = false
        for suffix in ["", "-wal", "-shm"] {
            let path = base + suffix
            if let size = (try? fm.attributesOfItem(atPath: path))?[.size] as? NSNumber {
                total += size.int64Value
                found = true
            }
        }
        return found ? total : nil
    }

    // MARK: - Introspection (used by tests)

    public func tableNames() async throws -> Set<String> {
        try syncRead { db in
            try Set(String.fetchAll(db,
                sql: "SELECT name FROM sqlite_master WHERE type = 'table'"))
        }
    }

    public func primaryKeyColumns(_ table: String) async throws -> [String] {
        try syncRead { db in
            try db.primaryKey(table).columns
        }
    }

    public func columnNamesForTest(table: String) async throws -> [String] {
        try syncRead { db in
            try db.columns(in: table).map(\.name)
        }
    }

    public func indexNamesForTest(table: String) async throws -> Set<String> {
        try syncRead { db in
            try Set(db.indexes(on: table).map(\.name))
        }
    }
}
