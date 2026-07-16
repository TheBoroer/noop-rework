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

    /// Task 6 (Phase 2c-1): no longer `public`. `DeviceRegistryStore` now routes through the actor
    /// (async, backend-aware) instead of opening its own synchronous handle onto this GRDB writer
    /// directly -- that was the "sync bypass" that task killed: a raw `DatabaseWriter` handed out for
    /// direct synchronous use skips both actor serialization AND the Room backend entirely. `private`
    /// since Phase 2c-2 Task 8: its last external consumer (`DatabasePoolConcurrencyTests`) was deleted
    /// with the legacy outbox drain. `nonisolated` because a GRDB `DatabaseWriter` (here a
    /// `DatabasePool`) is `Sendable` and manages its own concurrency, so concurrent access alongside the
    /// actor's own DB work is safe (the Pool serializes writes and runs reads in parallel under WAL).
    private nonisolated var registryWriter: any DatabaseWriter { dbWriter }

    // MARK: - Room cutover (Phase 2c-1 Task 3)
    //
    // Every open decides which store serves the actor's per-feature methods (later tasks switch on
    // `backend`; this phase only decides it, the GRDB methods below are all still current code): a
    // fresh install or an already-migrated launch opens the shared Kotlin/Room database directly; a
    // legacy-only install runs a ONE-TIME ETL (Task 2's `GrdbMigrator`) with a progress stream the
    // app can show; an ETL failure falls back to the GRDB backend the store has always used.
    // `dbWriter` above stays open and migrated in EVERY case: rawBatch/cursors (the raw-frame
    // outbox) live there regardless of backend, and a fallback store must be fully functional on it.
    //
    // Fix pass (review): "already migrated" is decided by a completion SENTINEL next to `noop.db`
    // (`noop.db.migrated`), not by the Room file merely existing, so a hard crash mid-ETL can never
    // leave a partial Room file that a later launch mistakes for "done" forever, see
    // `detectMigrateOpen`. Progress is also reachable two ways now: `init(path:)` keeps its original
    // `migrationProgress` `AsyncStream` (replayed after the whole open returns, tests/back-compat),
    // and the `open(path:onProgress:)` factory additionally invokes `onProgress` LIVE, once per
    // committed ETL batch, for callers (the app) that want a real-time progress overlay.
    //
    // Phase 2c-2 Task 4 update: the note above ("rawBatch/cursors live there [dbWriter] regardless of
    // backend") is now only true for `.legacyGrdb`. `RawOutbox.swift`/`Cursors.swift` are
    // backend-aware: a `.room`-backed store routes the raw-frame outbox and cursor reads/writes
    // through `OutboxBridge` onto the Kotlin `WhoopDatabase` handle carried by this very `.room`
    // case instead. `dbWriter` still stays open and migrated in every case (a `.legacyGrdb` store
    // must be fully functional on it, and it also still backs decoded-table reads for that backend),
    // but it is no longer where the raw outbox lives for a Room-backed store.

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

    /// Thrown by a Room-backed method if it is somehow reached on the x86_64 iOS-Simulator slice.
    /// That slice is a phantom: `Shared.xcframework` ships no x86_64 iOS-Simulator slice (only arm64),
    /// so the SKIE Swift surface (`whoopDatabase` / `onEnum` / the async DAO members) is absent there,
    /// the app EXCLUDED_ARCHS-drops the arch at link (project.yml), and this compiled-but-unlinked
    /// slice is never run. Every Room branch is therefore `#if`-compiled out of that one slice and
    /// throws this instead, so the whole package still type-checks for `generic/platform=iOS Simulator`
    /// (the exact CI destination). The arm64 device/simulator and macOS slices carry the real Room path.
    struct RoomBackendUnavailableError: Error, CustomStringConvertible {
        var description: String {
            "Room is unavailable on the x86_64 iOS-Simulator slice (no Shared slice); never linked or run."
        }
    }

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
        /// means the NEXT launch retries the cutover. What actually gates that retry is the
        /// completion SENTINEL (`noop.db.migrated`, written only once `GrdbMigrator.verify` confirms
        /// every mapped table's row count matches), not whether a Room file happens to exist: this
        /// launch deletes any Room file it created before falling back here, so the next launch sees
        /// neither a Room file nor a sentinel and retries cleanly either way.
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

    /// The pieces `detectMigrateOpen` produces, handed to whichever entry point called it (`init(path:)`
    /// or `open(path:onProgress:)`) so it can build `self`/the returned instance via the designated
    /// init above. Not `public`: purely an internal handoff between the two open paths and the one
    /// place that actually knows how to construct a `WhoopStore`.
    private struct OpenResult {
        let dbWriter: any DatabaseWriter
        let backend: Backend
        let storageBackend: StorageBackend
        let migrationProgress: AsyncStream<Double>?
    }

    /// Open (creating if needed) a database at `path` and run migrations, then detect-migrate-open
    /// the shared Room store alongside it (Task 3): a fresh install and a both-files-present launch
    /// both open Room directly (no re-ETL); a legacy-only launch runs the one-time ETL (progress on
    /// `migrationProgress`); an ETL failure falls back to `.legacyGrdb` rather than throwing, so the
    /// store is always usable once this returns. Uses a `DatabasePool` for the legacy handle, which
    /// enables WAL automatically, plus a 5-second busy timeout so two handles to the same file
    /// (BLEManager + MetricsRepository) don't deadlock on write contention.
    ///
    /// Delegates to `detectMigrateOpen`, the same detect-migrate-open logic `open(path:onProgress:)`
    /// uses, but with no live progress callback: kept for tests and the other pre-Fix-2 call sites
    /// (BLEManager, the Backfill tool). `migrationProgress` still replays every value once the ETL
    /// finishes (an `AsyncStream` buffers what it's yielded, so iterating it after the fact still
    /// sees every step) -- only a LIVE callback during the ETL is unavailable through this entry
    /// point. Use `open(path:onProgress:)` for that (the single production creation site does).
    public init(path: String) async throws {
        let result = try await WhoopStore.detectMigrateOpen(path: path, onProgress: nil)
        self.init(dbWriter: result.dbWriter, backend: result.backend, storageBackend: result.storageBackend,
                  migrationProgress: result.migrationProgress)
    }

    /// Production entry point (Task 3 Fix 2): the identical detect-migrate-open flow as `init(path:)`,
    /// but when `onProgress` is non-nil it fires LIVE, once per committed ETL batch, bridged straight
    /// through from Kotlin's synchronous callback while this call is still awaiting, not after the
    /// fact from `migrationProgress`'s replay (which only becomes observable once the whole open,
    /// ETL included, has already finished). `Repository.ensureStore` uses this so the app's progress
    /// overlay tracks the real ETL instead of flashing through a finished replay.
    public static func open(path: String, onProgress: (@Sendable (Double) -> Void)? = nil) async throws -> WhoopStore {
        let result = try await WhoopStore.detectMigrateOpen(path: path, onProgress: onProgress)
        return WhoopStore(dbWriter: result.dbWriter, backend: result.backend,
                           storageBackend: result.storageBackend, migrationProgress: result.migrationProgress)
    }

    /// The detect-migrate-open state machine shared by `init(path:)` and `open(path:onProgress:)`.
    /// `onProgress`, when non-nil, is invoked LIVE, once per committed ETL batch, in addition to (not
    /// instead of) the `AsyncStream` both entry points still return on `migrationProgress`.
    private static func detectMigrateOpen(
        path: String, onProgress: (@Sendable (Double) -> Void)?
    ) async throws -> OpenResult {
        // Self-heal a foreign DB left in place by a bad cross-platform restore (#222). Phase 2c-1
        // Task 7 makes this path-aware to the flipped storage world: the legacy `whoop.sqlite` is
        // native GRDB, the sibling `noop.db` is native Room. A file that holds data but lacks the
        // marker its path expects (a GRDB file dropped where Room belongs, or any unknown schema)
        // would strand the store on open, so quarantine it FIRST and start fresh. A valid GRDB store
        // at the legacy path and a valid Room store at the Room path are both left untouched.
        let fm = FileManager.default
        let roomPath = WhoopStore.roomPath(forLegacyPath: path)
        WhoopStore.quarantineIncompatibleDatabase(at: path, expecting: .grdb)
        WhoopStore.quarantineIncompatibleDatabase(at: roomPath, expecting: .room)

        // Snapshot existence AFTER the quarantine (a foreign Room-path file just moved aside must read
        // as absent here) but BEFORE anything below creates either file, so "fresh install" is never
        // mistaken for "legacy-only" just because opening the GRDB pool below creates `path`.
        let legacyExistedBefore = fm.fileExists(atPath: path)
        let roomExistedBefore = fm.fileExists(atPath: roomPath)
        // Completion sentinel (Task 3 Fix 1): the both-present branch below requires THIS, not just
        // `roomExistedBefore`, so a hard crash mid-ETL (which leaves a partial `noop.db` on disk but
        // never gets to run this file's write) can never be mistaken for a finished migration.
        let sentinelExistedBefore = fm.fileExists(atPath: WhoopStore.migrationSentinelPath(forRoomPath: roomPath))

        var config = Configuration()
        config.prepareDatabase { db in
            // `DatabasePool` puts the database in WAL mode itself (reads run as concurrent snapshots
            // alongside the single writer, #755), so there is no explicit `PRAGMA journal_mode = WAL`.
            // Bulk-write/read tuning. NORMAL is the durable, recommended pairing with WAL (only an
            // OS crash/power loss can lose the last transaction, acceptable here). Bigger page cache
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

        #if targetEnvironment(simulator) && arch(x86_64)
        // Phantom x86_64 iOS-Simulator slice (see RoomBackendUnavailableError): Shared has no such
        // slice, so the Room/ETL machinery below (whoopDatabase / onEnum / suspend DAO calls) cannot
        // type-check here. This slice is never linked or run; return the always-open legacy GRDB
        // backend so the package compiles for `generic/platform=iOS Simulator`. The arm64 slices carry
        // the real detect-migrate-open cutover.
        _ = (roomPath, legacyExistedBefore, roomExistedBefore, sentinelExistedBefore)
        return OpenResult(dbWriter: legacyPool, backend: .legacyGrdb,
                          storageBackend: .legacyGrdb(fallbackReason: "unbuilt on x86_64 iOS Simulator"),
                          migrationProgress: nil)
        #else

        if roomExistedBefore && sentinelExistedBefore {
            // Both files present AND the sentinel confirms a PRIOR migration (ETL or fresh install)
            // actually completed: open Room as-is, no re-ETL (re-running it would blow away any
            // Room-only writes made since that launch).
            let room = whoopDatabase(path: roomPath)
            return OpenResult(dbWriter: legacyPool, backend: .room(room), storageBackend: .room,
                               migrationProgress: nil)
        }

        if roomExistedBefore {
            // Room exists but the sentinel doesn't: a previous launch crashed mid-ETL (or mid-fresh-
            // install) after writes had started but before this launch could confirm completion.
            // Delete the partial file (+ -wal/-shm, + any sentinel, though none is expected here) so
            // the branches below run a clean ETL, exactly like a legacy-only launch (safe because
            // `GrdbMigrator.migrate` truncates its mapped tables before copying, so redoing it from
            // scratch is idempotent).
            WhoopStore.deletePartialRoomDatabase(at: roomPath)
        }

        if !legacyExistedBefore && !roomExistedBefore {
            // Fresh install: nothing to migrate. `whoopDatabase` builds the Room handle, but Room
            // opens its connection lazily on first use, not inside `.build()`; force it now so the
            // file genuinely exists on disk once this returns, matching the init-matrix contract
            // ("fresh install creates Room"). The sentinel is written unconditionally here (Fix 1),
            // keeping later launches consistent, since there is no ETL copy to verify in this branch.
            let room = whoopDatabase(path: roomPath)
            try await WhoopStore.warmUpRoomDatabase(room)
            WhoopStore.writeFreshInstallMigrationSentinel(legacyPath: path, roomPath: roomPath)
            return OpenResult(dbWriter: legacyPool, backend: .room(room), storageBackend: .room,
                               migrationProgress: nil)
        }

        // Legacy-only: one-time ETL (this also covers a retry after the crash-window cleanup above,
        // or after a prior launch's explicit `.failed` cleanup below). `GrdbMigrator.migrate` is a
        // blocking Kotlin call that invokes `onProgress` synchronously, once per committed batch, on
        // this same thread; bridge those callbacks into an `AsyncStream` (replay, both entry points)
        // and, when the caller supplied one, into `onProgress` LIVE (Task 3 Fix 2).
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
            let overall = min(max((Double(tableIndex) + tableFraction) / Double(WhoopStore.migratedTableCount), 0), 1)
            progressContinuation?.yield(overall)
            onProgress?(overall)
        }
        progressContinuation?.finish()

        switch onEnum(of: result) {
        case .done:
            // Completion sentinel (Task 3 Fix 1): written only once `verify` confirms every mapped
            // table's row count actually matches between the two files. This is what closes the
            // crash window: file existence alone (the old gate) can't tell a finished ETL apart from
            // one a hard crash cut short partway.
            WhoopStore.verifyAndWriteEtlMigrationSentinel(legacyPath: path, roomPath: roomPath)
            let room = whoopDatabase(path: roomPath)
            return OpenResult(dbWriter: legacyPool, backend: .room(room), storageBackend: .room,
                               migrationProgress: progressStream)
        case .failed(let failure):
            // Never leave a partial Room file (or a stale sentinel) behind: the next launch must see
            // neither "Room file" nor "sentinel" (the legacy-only branch above) and retry cleanly,
            // not "Room exists, migrated" (which would skip straight past the retry with a
            // half-populated store).
            WhoopStore.deletePartialRoomDatabase(at: roomPath)
            let reason = "\(failure.table): \(failure.message)"
            return OpenResult(dbWriter: legacyPool, backend: .legacyGrdb,
                               storageBackend: .legacyGrdb(fallbackReason: reason),
                               migrationProgress: progressStream)
        }
        #endif
    }

    /// Forces Room's on-disk file to actually exist: Room's builder opens its bundled-driver
    /// connection lazily on first real use, not inside `.build()` itself, so a fresh (empty) Room
    /// database otherwise wouldn't be on disk yet when `init(path:)` returns. A cheap DAO read is
    /// enough to open the connection; the fresh schema always has zero rows.
    private static func warmUpRoomDatabase(_ room: WhoopDatabase) async throws {
        #if targetEnvironment(simulator) && arch(x86_64)
        _ = room  // Unreachable on the phantom x86_64 iOS-Simulator slice (see detectMigrateOpen).
        #else
        _ = try await room.whoopDao().countHr()
        #endif
    }

    /// The Room sibling of a legacy GRDB path: same directory, filename `noop.db` (mirrors
    /// `StorePaths.roomDatabasePath()` in the app target, which this package cannot import).
    private static func roomPath(forLegacyPath legacyPath: String) -> String {
        URL(fileURLWithPath: legacyPath)
            .deletingLastPathComponent()
            .appendingPathComponent("noop.db")
            .path
    }

    /// Completion sentinel (Task 3 Fix 1): its PRESENCE beside `noop.db`, not the Room file's own
    /// existence, is what the both-present branch of `detectMigrateOpen` actually requires before
    /// skipping a re-ETL. Filename is `noop.db.migrated`. Content (written by
    /// `writeMigrationSentinelContent` below) is informational only, one `table=count` line per
    /// `GrdbMigrator.verify` row, handy for support/diagnostics but never parsed back in: the
    /// invariant lives in the file existing at all, not in what it says.
    private static func migrationSentinelPath(forRoomPath roomPath: String) -> String {
        roomPath + ".migrated"
    }

    /// Writes the sentinel's content: one `table=count` line per `GrdbMigrator.verify` row (the
    /// Room-side count; the two sides already agree by the time either caller below reaches this).
    /// Best-effort: a write failure (e.g. a full disk) just leaves the sentinel unwritten, which is
    /// safe, the next launch simply finds no sentinel and retries as if this one had crashed.
    private static func writeMigrationSentinelContent(
        _ counts: [String: KotlinPair<KotlinLong, KotlinLong>], roomPath: String
    ) {
        let lines = counts.keys.sorted().map { table in
            "\(table)=\(counts[table]?.second?.int64Value ?? -1)"
        }
        let content = lines.joined(separator: "\n") + "\n"
        try? content.write(toFile: migrationSentinelPath(forRoomPath: roomPath), atomically: true, encoding: .utf8)
    }

    /// Fresh install (Task 3 Fix 1): writes the completion sentinel unconditionally, right after
    /// Room is created and warmed up. Nothing here needs verifying against `verify`'s row counts the
    /// way the ETL path below does: Room was built directly (never copied from legacy), so there is
    /// no copy fidelity to confirm. `verify`'s counts still feed the sentinel's (informational-only)
    /// content; they need NOT be 0 == 0 on the legacy side even for a brand-new database, GRDB's own
    /// migrations can seed rows independently of any ETL (v15 seeds one `pairedDevice` row so no
    /// existing WHOOP is orphaned), which Room's from-scratch schema has no equivalent of yet.
    private static func writeFreshInstallMigrationSentinel(legacyPath: String, roomPath: String) {
        let counts = GrdbMigrator.shared.verify(legacyPath: legacyPath, roomPath: roomPath)
        writeMigrationSentinelContent(counts, roomPath: roomPath)
    }

    /// One-time ETL (Task 3 Fix 1): writes the completion sentinel ONLY IF `GrdbMigrator.verify`
    /// confirms every mapped table's row count actually matches between the legacy and Room files.
    /// This is what closes the crash window: file existence alone (the old gate) can't tell a
    /// finished ETL apart from one a hard crash cut short partway. If any count disagrees, not
    /// expected given `Result.Done`'s own contract, but checked here defensively rather than trusted
    /// blindly, the sentinel is simply left unwritten: the NEXT launch then finds Room present
    /// without a sentinel and safely re-runs the (idempotent, truncate-first) ETL, exactly like the
    /// crash-window case in `detectMigrateOpen`.
    private static func verifyAndWriteEtlMigrationSentinel(legacyPath: String, roomPath: String) {
        let counts = GrdbMigrator.shared.verify(legacyPath: legacyPath, roomPath: roomPath)
        guard counts.values.allSatisfy({ $0.first?.int64Value == $0.second?.int64Value }) else { return }
        writeMigrationSentinelContent(counts, roomPath: roomPath)
    }

    /// Removes a Room database file (plus its `-wal`/`-shm`/`-journal` sidecars) left behind by an
    /// ETL that failed partway or crashed outright (Task 3 Fix 1), plus the completion sentinel
    /// beside it, so the next launch's checks see neither "Room file" nor "sentinel" and retry the
    /// cutover instead of opening a half-populated store, or skipping the retry entirely because a
    /// stale sentinel says it's already done. Best-effort + silent, matching
    /// `quarantineIncompatibleDatabase`'s style: `GrdbMigrator` always closes its handle before
    /// returning `Failed`, so the file is never locked when this runs.
    private static func deletePartialRoomDatabase(at path: String) {
        let fm = FileManager.default
        for suffix in ["", "-wal", "-shm", "-journal"] {
            try? fm.removeItem(atPath: path + suffix)
        }
        try? fm.removeItem(atPath: migrationSentinelPath(forRoomPath: path))
    }

    /// Which store a given path is EXPECTED to hold, so `quarantineIncompatibleDatabase` knows the
    /// native bookkeeping marker to check for (Phase 2c-1 Task 7): the legacy `whoop.sqlite` path is
    /// GRDB (`grdb_migrations`), the `noop.db` path is Room (`room_master_table`). A data-holding file
    /// missing the marker its path expects is foreign and gets quarantined.
    enum ExpectedSchema {
        case grdb
        case room

        var nativeMarker: String {
            switch self {
            case .grdb: return "grdb_migrations"
            case .room: return "room_master_table"
            }
        }
    }

    /// True when `names` holds real app data (any user-content table), vs an empty/fresh/housekeeping
    /// -only file. Mirrors the shared `BackupRestore.holdsData` byte-for-byte so the quarantine gate,
    /// the import origin gate, and Android all agree on "holds data".
    static func holdsData(_ names: Set<String>) -> Bool {
        let housekeeping: Set<String> = ["android_metadata", "sqlite_sequence", "room_master_table", "grdb_migrations"]
        return names.contains { !housekeeping.contains($0) && !$0.hasPrefix("sqlite_") }
    }

    /// Move aside a database file at `path` that holds data but lacks the migration bookkeeping its
    /// path expects — the signature of a foreign DB dropped over ours by a bad restore (#222).
    ///
    /// Phase 2c-1 Task 7 makes this path-aware to pin the flipped matrix: the legacy `whoop.sqlite`
    /// path is native GRDB (`expecting: .grdb`), the `noop.db` path is native Room (`expecting: .room`).
    /// So a valid GRDB store at the legacy path and a valid Room store at the Room path are both left
    /// untouched, while a GRDB file dropped at the Room path (`room_master_table` absent) — or any
    /// unknown schema that still holds data at either path — is quarantined. Opening a foreign file
    /// unquarantined would strand the store (the GRDB migrator re-runs v1 → `table "device" already
    /// exists`, or Room meets a schema it never created). Moving it to a `.incompatible-<ts>` sidecar
    /// lets the next open create a clean store. A fresh/empty file is always left untouched.
    /// Best-effort + silent.
    static func quarantineIncompatibleDatabase(at path: String, expecting expected: ExpectedSchema) {
        let fm = FileManager.default
        guard fm.fileExists(atPath: path) else { return }
        // Read-ONLY probe of sqlite_master over the system SQLite (RawSQLite, #65 T2 — no GRDB,
        // and a raw probe never runs migrations): a read-only open never creates -wal/-shm
        // siblings or flips the journal mode, so probing a valid Room file here can't disturb it.
        // If the probe can't open (locked, or a WAL file with no readable -shm), bail and let the
        // real open + migrator deal with it.
        guard let rows = try? RawSQLite.queryStrings(
            atPath: path, sql: "SELECT name FROM sqlite_master WHERE type = 'table'"
        ) else {
            return // unreadable/locked → let the real open + migrator deal with it
        }
        let names = Set(rows)
        let isForeign = !names.contains(expected.nativeMarker) && WhoopStore.holdsData(names)
        guard isForeign else { return }
        let stamp = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "")
        let quarantine = "\(path).incompatible-\(stamp)"
        try? fm.removeItem(atPath: quarantine)
        do { try fm.moveItem(atPath: path, toPath: quarantine) } catch { return }
        // Drop the now-orphaned WAL/SHM sidecars so the fresh DB starts clean.
        for suffix in ["-wal", "-shm"] { try? fm.removeItem(atPath: path + suffix) }
    }

    // MARK: - Backup import support (Phase 2c-1 Task 7)
    // The app's `DataBackup` (in the Strand target) cannot import the Shared framework — only this
    // package links it — so the two backup-import steps that need Kotlin live here as thin statics:
    // (a) running the ETL to convert a legacy GRDB `.noopbak` into a Room `noop.db`, and (b) writing
    // the completion sentinel that lets `detectMigrateOpen` open an imported/migrated Room file as-is
    // on the next launch instead of deleting it as a partial ETL.

    /// Thrown by `migrateLegacyGrdbArchive` when the ETL over a legacy GRDB backup fails; carries the
    /// failing table + message so `DataBackup` can surface an honest import-failure string.
    public struct LegacyBackupMigrationError: Error, CustomStringConvertible {
        public let table: String
        public let message: String
        public var description: String { "\(table): \(message)" }
    }

    /// Convert a STAGED legacy GRDB backup database (a pre-2c-1 Apple `.noopbak`, already extracted +
    /// integrity-checked by the caller) into a fresh Room `noop.db`, returning the path of that Room
    /// file for the caller to swap onto the live `noop.db`. Runs the same `GrdbMigrator` ETL the
    /// one-time in-place cutover uses (so a legacy backup restores by the identical, tested path);
    /// the produced Room file carries `room_master_table` and is therefore a native, forward-restorable
    /// backup. Writes into a fresh unique temp directory (its `-wal`/`-shm` are contained there); the
    /// caller owns removing the returned file once the swap lands. Throws on ETL failure, leaving
    /// nothing behind.
    public static func migrateLegacyGrdbArchive(atPath grdbPath: String) throws -> String {
        let fm = FileManager.default
        let workDir = fm.temporaryDirectory
            .appendingPathComponent("noop-legacy-import-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: workDir, withIntermediateDirectories: true)
        let roomPath = workDir.appendingPathComponent("noop.db").path

        #if targetEnvironment(simulator) && arch(x86_64)
        _ = (grdbPath, roomPath)
        throw RoomBackendUnavailableError()
        #else
        let result = GrdbMigrator.shared.migrate(legacyPath: grdbPath, roomPath: roomPath) { _ in }
        switch onEnum(of: result) {
        case .done:
            return roomPath
        case .failed(let failure):
            try? fm.removeItem(at: workDir)
            throw LegacyBackupMigrationError(table: failure.table, message: failure.message)
        }
        #endif
    }

    /// Write the completion sentinel beside an imported (Room-origin swap) or freshly-migrated
    /// (GRDB-origin ETL) `noop.db`, so the next launch's `detectMigrateOpen` opens it AS-IS instead of
    /// mistaking a sentinel-less Room file for a crashed partial ETL and deleting it. Its PRESENCE is
    /// the whole contract (content is informational-only, matching the ETL/fresh-install sentinels);
    /// this records that an import placed the file and when. Best-effort + silent.
    public static func markRoomDatabaseImported(roomPath: String) {
        let content = "imported=\(Int(Date().timeIntervalSince1970))\n"
        try? content.write(toFile: migrationSentinelPath(forRoomPath: roomPath),
                           atomically: true, encoding: .utf8)
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

    /// Fully checkpoint the WAL into the main database file and truncate the -wal file, so the single
    /// file carries all committed data (the -wal/-shm siblings can then be ignored). Used before a
    /// file-level backup. Backend-aware (Phase 2c-1 Task 7): a Room-backed store checkpoints the live
    /// Room `noop.db` (which is what the export now zips) through its own writer connection; a legacy
    /// GRDB store checkpoints the GRDB pool as before. Runs outside a transaction — `wal_checkpoint`
    /// must. Best-effort: throws on a hard SQLite error so callers can fall back to a plain copy.
    public func checkpointWAL() async throws {
        switch backend {
        case .room(let room):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = room; throw RoomBackendUnavailableError()
            #else
            try await room.checkpointWal()
            #endif
        case .legacyGrdb:
            try checkpointWALImpl()
        }
    }

    /// Non-async so GRDB's synchronous `writeWithoutTransaction` overload is chosen (mirrors the
    /// syncRead/syncWrite pattern). Runs on the actor's executor, off the main thread.
    private func checkpointWALImpl() throws {
        try dbWriter.writeWithoutTransaction { db in
            try db.execute(sql: "PRAGMA wal_checkpoint(TRUNCATE)")
        }
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
