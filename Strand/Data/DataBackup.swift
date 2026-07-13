import Foundation
#if canImport(AppKit)
import AppKit
#elseif canImport(UIKit)
import UIKit
#endif
import SQLite3
import UniformTypeIdentifiers
import WhoopStore
import ZIPFoundation

/// Full-database EXPORT / IMPORT for device migration.
///
/// Phase 2c-1 Task 7 (the backup flip): the store is now the shared Room `noop.db` (beside the legacy
/// GRDB `whoop.sqlite`, which retains only device-local outbox/cursors). Export checkpoints the Room
/// WAL (so the single file is whole), then wraps `noop.db` in a ZIP written as `.noopbak` under the
/// unchanged entry name `noop-backup.sqlite`, alongside a small `settings.json` entry (#1000) carrying
/// the whitelisted profile/display settings (see `BackupSettings`) so a restore also brings back
/// weight/height/units, not just the rows. Because it is now Room-format, an Apple export is
/// restorable on Android (proven cross-platform in shared `AppleRoomBackupCrossPlatformTest`), and the
/// container stays byte-compatible with the Android exporter.
///
/// Import detects the format by magic bytes (ZIP `PK\x03\x04` or plain SQLite), extracts + validates,
/// then routes by the backup's own bookkeeping table onto the live `noop.db`:
///  - a Room backup (`room_master_table`) is NATIVE and swapped straight in;
///  - a legacy GRDB backup (`grdb_migrations`, any pre-2c-1 Mac/iOS export) is migrated through the
///    `GrdbMigrator` ETL into a fresh Room file first, so legacy `.noopbak` / `.sqlite` backups remain
///    importable forever;
///  - a foreign schema that holds data is rejected. (Android's "this is a Mac backup, rejected"
///    message is therefore now reachable only for those pre-2c-1 legacy GRDB exports.)
///
/// Sandbox-safe: relies on the `com.apple.security.files.user-selected.read-write` entitlement and
/// security-scoped access on the panel-returned URLs. Every path is best-effort — failures surface
/// as a `.failure` result and never crash.
enum DataBackup {

    // MARK: - Result

    enum BackupResult {
        /// Export wrote the backup to `url`.
        case exported(URL)
        /// Import succeeded; a relaunch is required for it to take effect. `sidecar` is where the
        /// previous database was preserved, in case the user wants to roll back.
        case imported(sidecar: URL)
        /// The user dismissed the save/open panel — nothing happened, show nothing loud.
        case cancelled
        /// Something went wrong; `message` is user-facing.
        case failure(String)
    }

    // MARK: - Export

    /// Checkpoint the store and write the live database as a compressed `.noopbak` (single-entry
    /// ZIP) to a user-chosen file.
    ///
    /// - Parameter checkpoint: invoked first to flush the WAL into the main file. Pass
    ///   `repo.checkpointForBackup`. Must succeed — a failed checkpoint means committed pages still
    ///   live in the WAL and would be silently absent from the ZIP; we fail loudly rather than ship
    ///   a partial backup.
    @MainActor
    static func runExport(checkpoint: @escaping () async -> Bool) async -> BackupResult {
        let dbPath: String
        do { dbPath = try Self.liveExportDatabasePath() }
        catch { return .failure(String(localized: "Couldn't locate the NOOP database. \(error.localizedDescription)")) }

        let dbURL = URL(fileURLWithPath: dbPath)
        guard FileManager.default.fileExists(atPath: dbPath) else {
            return .failure(String(localized: "There's no NOOP data to export yet. Import or record some first."))
        }

        // Flush the WAL so the single .sqlite carries everything. Required for ZIP (no sidecar
        // fallback in a single-file archive).
        guard await checkpoint() else {
            return .failure(String(localized: "Couldn't safely export right now. Recent changes are still in the database's write-ahead log. Close any in-flight sync, then try again."))
        }

        #if os(macOS)
        let panel = NSSavePanel()
        panel.title = String(localized: "Export NOOP backup")
        panel.prompt = String(localized: "Export")
        panel.canCreateDirectories = true
        panel.nameFieldStringValue = defaultBackupName()
        panel.allowedContentTypes = backupContentTypes()
        panel.isExtensionHidden = false

        guard panel.runModal() == .OK, let dest = panel.url else { return .cancelled }

        let scoped = dest.startAccessingSecurityScopedResource()
        defer { if scoped { dest.stopAccessingSecurityScopedResource() } }

        let fm = FileManager.default
        do {
            // NSSavePanel already handled the "replace existing?" confirmation; clear the target.
            if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
            // Reading the whole SQLite and DEFLATE-compressing it is multi-second on a big library
            // (and #1014 added a quick_check read of the whole file first); run it off the main
            // actor so the UI never beach-balls. Only file paths cross the hop.
            try await Task.detached(priority: .utility) {
                try writeVerifiedBackupZip(dbURL: dbURL, to: dest, settingsJSON: currentSettingsJSON())
            }.value
            return .exported(dest)
        } catch {
            return .failure(String(localized: "Export failed: \(error.localizedDescription)"))
        }
        #else
        let fm = FileManager.default

        // Stage the compressed backup in temp, then hand it to the share sheet.
        let staged = fm.temporaryDirectory.appendingPathComponent(defaultBackupName())
        do {
            if fm.fileExists(atPath: staged.path) { try fm.removeItem(at: staged) }
            // Off the main actor: same reason as the macOS branch (heavy read + DEFLATE). Only paths hop.
            try await Task.detached(priority: .utility) {
                try writeVerifiedBackupZip(dbURL: dbURL, to: staged, settingsJSON: currentSettingsJSON())
            }.value
        } catch {
            return .failure(String(localized: "Export failed: \(error.localizedDescription)"))
        }
        guard let dest = await DocumentPicker.export(staged) else { return .cancelled }
        return .exported(dest)
        #endif
    }

    /// #1014 defence-in-depth (export side): the export's failure when the LIVE database itself is
    /// damaged. Thrown by `writeVerifiedBackupZip`; `LocalizedError` so the existing
    /// "Export failed: \(error.localizedDescription)" surfaces the specific, honest message.
    private struct ExportIntegrityFailure: LocalizedError {
        let complaint: String
        var errorDescription: String? {
            String(localized: "the NOOP database failed its integrity check (SQLite reports: \(complaint)). A backup of it would not restore. Export the WHOOP-format CSV (Settings → Export data) to save what's still readable.")
        }
    }

    /// The production export path: verify, then archive. GRDB checkpoints the WAL first (the
    /// callers' `checkpoint()` guard), so at this point the single file IS the whole store — run a
    /// read-only `PRAGMA quick_check` over it BEFORE zipping (#1014). Archiving an already-corrupt
    /// database writes a `.noopbak` that only fails the import-side integrity gate months later,
    /// when the original data may be long gone; failing loudly NOW is the honest move. The read-only
    /// probe sits safely beside the app's open GRDB pool (WAL allows concurrent readers).
    /// `writeBackupForTesting` deliberately bypasses this so tests can build damaged containers.
    private static func writeVerifiedBackupZip(dbURL: URL, to dest: URL, settingsJSON: Data?) throws {
        if let complaint = DatabaseIntegrity.quickCheckFailure(atPath: dbURL.path) {
            throw ExportIntegrityFailure(complaint: complaint)
        }
        try writeBackupZip(dbURL: dbURL, to: dest, settingsJSON: settingsJSON)
    }

    /// Write the live SQLite at `dbURL` into a fresh deflate ZIP at `dest`: the DB under the canonical
    /// entry name `noop-backup.sqlite`, plus (#1000) an optional second entry `settings.json` carrying
    /// the whitelisted profile/display settings, so a restore brings back weight/height/units and not
    /// just the rows. Entry names, entry ORDER (DB first — older importers stop at the first `.sqlite`
    /// entry) and deflate compression match the Android exporter byte-for-byte at the container level,
    /// so a `.noopbak` produced on either platform imports on the other. `settingsJSON == nil` writes
    /// the legacy single-entry ZIP. Mirrors the `Archive` idiom in `WhoopCsvExporter`.
    private static func writeBackupZip(dbURL: URL, to dest: URL, settingsJSON: Data?) throws {
        let archive = try Archive(url: dest, accessMode: .create)
        try archive.addEntry(with: backupEntryName, fileURL: dbURL, compressionMethod: .deflate)
        guard let settingsJSON else { return }
        // Stage the JSON through a temp file so the settings entry uses the exact same file-URL
        // addEntry idiom as the DB entry (one container code path, no provider-API variant to drift).
        let fm = FileManager.default
        let tmpJSON = fm.temporaryDirectory
            .appendingPathComponent("noop-settings-\(UUID().uuidString).json")
        try settingsJSON.write(to: tmpJSON)
        defer { try? fm.removeItem(at: tmpJSON) }
        try archive.addEntry(with: BackupSettings.entryName, fileURL: tmpJSON, compressionMethod: .deflate)
    }

    /// This device's whitelisted profile/display settings (see `BackupSettings.whitelist`) as the
    /// `settings.json` payload, or nil when nothing whitelisted was ever set (a fresh install then
    /// exports a legacy DB-only ZIP, which is the right degrade). UserDefaults is thread-safe, so
    /// the detached export tasks may call this off the main actor.
    private static func currentSettingsJSON() -> Data? {
        BackupSettings.encode(BackupSettings.snapshot(from: .standard))
    }

    /// (Backup & Sync) Write a `.noopbak` to a SPECIFIC `dest` URL with NO save panel: the folder /
    /// auto-backup path. Checkpoints the WAL (so the single `.sqlite` is whole) then writes the same
    /// deflate ZIP via the same `writeBackupZip` the interactive export uses, so folder / auto backups
    /// are byte-identical to a manual export. The CALLER owns any security-scoped access to `dest`
    /// (start/stop around this call). Never presents UI, so it is safe off the main actor.
    static func writeBackup(checkpoint: @escaping () async -> Bool, to dest: URL) async -> BackupResult {
        let dbPath: String
        do { dbPath = try Self.liveExportDatabasePath() }
        catch { return .failure(String(localized: "Couldn't locate the NOOP database. \(error.localizedDescription)")) }

        let dbURL = URL(fileURLWithPath: dbPath)
        guard FileManager.default.fileExists(atPath: dbPath) else {
            return .failure(String(localized: "There's no NOOP data to export yet."))
        }
        // Flush the WAL into the single file (same requirement as the interactive export: a single-file
        // ZIP has no sidecar fallback, so committed pages still in the WAL would otherwise be absent).
        guard await checkpoint() else {
            return .failure(String(localized: "Couldn't safely back up right now. Recent changes are still in the write-ahead log."))
        }
        do {
            let fm = FileManager.default
            if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
            try writeVerifiedBackupZip(dbURL: dbURL, to: dest, settingsJSON: currentSettingsJSON())
            return .exported(dest)
        } catch {
            return .failure(String(localized: "Backup failed: \(error.localizedDescription)"))
        }
    }

    /// Test seam: write a `.noopbak` for an EXPLICIT source database (no checkpoint, no `StorePaths`),
    /// so a unit test can round-trip a throwaway SQLite through the exact ZIP container the app writes.
    /// `settings` (canonical `BackupSettings` keys) adds the `settings.json` entry; nil writes the
    /// legacy single-entry ZIP — tests cover both shapes. Not used by app code; production goes
    /// through `writeBackup(checkpoint:to:)`.
    static func writeBackupForTesting(databaseAt dbURL: URL, to dest: URL,
                                      settings: [String: Any]? = nil) throws {
        let fm = FileManager.default
        if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
        try writeBackupZip(dbURL: dbURL, to: dest,
                           settingsJSON: settings.flatMap { BackupSettings.encode($0) })
    }

    // MARK: - Import

    /// Pick a `.noopbak` (ZIP) or legacy `.sqlite` backup, validate it, snapshot the current DB
    /// to a side file, then copy the backup over the live database path (removing the `-wal`/`-shm`
    /// siblings). The store stays open, so the swapped-in file only takes effect after a relaunch —
    /// the caller informs the user.
    @MainActor
    static func runImport() async -> BackupResult {
        // Import lands on the live Room store `noop.db` (Phase 2c-1 Task 7): after the swap + relaunch,
        // `WhoopStore.detectMigrateOpen` opens the imported Room file directly.
        let dbPath: String
        do { dbPath = try StorePaths.roomDatabasePath() }
        catch { return .failure(String(localized: "Couldn't locate the NOOP database. \(error.localizedDescription)")) }

        #if os(macOS)
        let panel = NSOpenPanel()
        panel.title = String(localized: "Import NOOP backup")
        panel.prompt = String(localized: "Import")
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.allowedContentTypes = backupContentTypes()

        guard panel.runModal() == .OK, let pickedSource = panel.url else { return .cancelled }

        let scoped = pickedSource.startAccessingSecurityScopedResource()
        defer { if scoped { pickedSource.stopAccessingSecurityScopedResource() } }
        #else
        // iOS: pick the backup through the system document picker (asCopy gives us a readable local
        // copy in our temp dir, so no security-scoped bookkeeping is needed).
        guard let pickedSource = await DocumentPicker.importFile(backupContentTypes()) else { return .cancelled }
        #endif

        // Hand the chosen file to the same hardened restore core the folder (Backup & Sync) path uses,
        // so the unzip / magic-byte / GRDB-origin / sidecar-snapshot / rollback logic lives in one place.
        // The restore does heavy synchronous file work (unzip, copy the whole DB, scan sqlite_master,
        // snapshot + rollback), which can run tens of seconds on a big library. Push it off the main
        // actor so the picker's UI thread stays live; the security-scoped access opened above (macOS)
        // stays valid because the surrounding function is still awaiting here. Only Sendable value
        // types (URL, String) cross the hop; the result hops back to main for handleBackup.
        return await Task.detached(priority: .utility) {
            restore(from: pickedSource, toDatabaseAt: dbPath)
        }.value
    }

    /// Restore a chosen backup file directly, with NO picker. The Backup & Sync folder flow calls this
    /// with a snapshot it already resolved from the user's backup folder (the caller owns any
    /// security-scoped access around the call). Runs against the live database path.
    ///
    /// Reuses the exact same hardened path as the picker import: ZIP extraction, SQLite magic-byte
    /// validation, GRDB-origin rejection (a foreign-but-valid SQLite is refused), a timestamped
    /// sidecar snapshot of the current store, and rollback-on-failure so a failed restore leaves the
    /// live database untouched.
    static func restore(from pickedSource: URL) -> BackupResult {
        let dbPath: String
        do { dbPath = try StorePaths.roomDatabasePath() }
        catch { return .failure(String(localized: "Couldn't locate the NOOP database. \(error.localizedDescription)")) }
        return restore(from: pickedSource, toDatabaseAt: dbPath)
    }

    /// The hardened restore core, with the destination database path injected so it is unit-testable
    /// against a throwaway DB (real file I/O, never the user's live store). Behaviour is identical to
    /// the previous `runImport` body; only the picker and path-resolution moved out to the callers.
    /// `settingsDefaults` is where a `settings.json` entry (#1000) is re-applied — injected for the
    /// same reason as `dbPath` (tests use a suite-scoped UserDefaults, never the runner's real domain).
    static func restore(from pickedSource: URL, toDatabaseAt dbPath: String,
                        settingsDefaults: UserDefaults = .standard) -> BackupResult {
        // If the picked file is a .noopbak ZIP, extract the SQLite entry to a temp dir first.
        // Legacy plain-SQLite files fall straight through. The extracted dir is cleaned up below.
        let fm = FileManager.default
        let source: URL
        let extractedDir: URL?

        if isZipFile(at: pickedSource) {
            let tmpExtract = fm.temporaryDirectory
                .appendingPathComponent("noop-import-\(UUID().uuidString)", isDirectory: true)
            do {
                if fm.fileExists(atPath: tmpExtract.path) { try fm.removeItem(at: tmpExtract) }
                try fm.createDirectory(at: tmpExtract, withIntermediateDirectories: true)
                try extractBackupZip(at: pickedSource, into: tmpExtract)
            } catch {
                try? fm.removeItem(at: tmpExtract)
                return .failure(String(localized: "Couldn't open the backup archive: \(error.localizedDescription)"))
            }
            guard let sqliteEntry = (try? fm.contentsOfDirectory(
                at: tmpExtract, includingPropertiesForKeys: nil))?
                .first(where: { $0.pathExtension == "sqlite" }) else {
                try? fm.removeItem(at: tmpExtract)
                return .failure(String(localized: "The backup archive doesn't contain a database file."))
            }
            source = sqliteEntry
            extractedDir = tmpExtract
        } else {
            source = pickedSource
            extractedDir = nil
        }
        defer { if let d = extractedDir { try? fm.removeItem(at: d) } }

        // Validate: must be a real SQLite database (magic header "SQLite format 3\0").
        guard isSQLiteFile(at: source) else {
            return .failure(String(localized: "That file isn't a NOOP backup. It doesn't look like a SQLite database."))
        }

        // #1014 defence-in-depth: the magic check reads only the FIRST pages of the file — the 16-byte
        // magic and sqlite_master both survive a backup truncated mid-upload or torn by a flaky
        // drive/cloud sync, and such a file then "restores" into a store that silently shows no data.
        // Run SQLite's own `PRAGMA quick_check` over the STAGED file, read-only, BEFORE anything
        // touches the live database (and before the possibly-expensive legacy ETL below), and refuse
        // honestly. One carve-out: a legacy plain-SQLite file still travelling with its -wal/-shm
        // siblings (an uncheckpointed manual copy) skips THIS gate — a read-only probe can't recover
        // someone else's WAL (shm rebuild needs write access) and would refuse spuriously. Those rare
        // files are still verified by the post-swap check below, on the landed main file.
        let legacySidecarsPresent = extractedDir == nil
            && (fm.fileExists(atPath: source.path + "-wal") || fm.fileExists(atPath: source.path + "-shm"))
        if !legacySidecarsPresent,
           let complaint = DatabaseIntegrity.quickCheckFailure(atPath: source.path) {
            return .failure(String(localized: "This backup file is damaged and can't be restored (SQLite reports: \(complaint)). Your current data was left untouched. Try an earlier backup file."))
        }

        // Origin routing (Phase 2c-1 Task 7 — the backup flip). The live store is Room `noop.db` now:
        //  • a Room backup (`room_master_table`) is NATIVE — swap the staged file straight in;
        //  • a legacy GRDB backup (`grdb_migrations`, any pre-2c-1 Mac/iOS export) is migrated through
        //    the SAME `GrdbMigrator` ETL as the one-time in-place cutover, into a fresh Room file that
        //    is then swapped in — so legacy `.noopbak` / `.sqlite` backups stay importable forever;
        //  • an unknown schema that still holds data is some other app's database: refuse it, restoring
        //    it would strand the store. An empty/pre-migration file falls through and opens fresh.
        // (`GrdbMigrator` lives in the Shared framework, which only WhoopStore links; the ETL step is
        // delegated to `WhoopStore.migrateLegacyGrdbArchive` for that reason.)
        let backupTables = sqliteTableNames(at: source)
        let origin = backupOrigin(of: backupTables)
        let swapSource: URL
        var etlWorkDir: URL?
        defer { if let etlWorkDir { try? fm.removeItem(at: etlWorkDir) } }
        switch origin {
        case .room:
            swapSource = source
        case .grdb:
            do {
                let migratedRoom = try WhoopStore.migrateLegacyGrdbArchive(atPath: source.path)
                swapSource = URL(fileURLWithPath: migratedRoom)
                etlWorkDir = swapSource.deletingLastPathComponent()
            } catch {
                return .failure(String(localized: "This looks like an older NOOP backup, but it couldn't be upgraded to the current format (\(error.localizedDescription)). Your existing data was left untouched. Try an earlier backup, or export the WHOOP-format CSV (Settings → Export data) and import that here."))
            }
        case .unknown:
            if DataBackup.holdsData(backupTables) {
                return .failure(String(localized: "This isn't a NOOP backup from this app. It's missing the database bookkeeping a NOOP backup carries (it looks like another app's database), and restoring it would strand your store. To move your history across platforms, export the WHOOP-format CSV on the other device (Settings → Export data) and import that here, or import your original WHOOP / Apple Health export."))
            }
            swapSource = source
        }

        let dbURL = URL(fileURLWithPath: dbPath)

        do {
            // Snapshot the current DB (+ sidecars) to a timestamped side file so the user can roll back.
            var sidecar = dbURL.deletingLastPathComponent()
                .appendingPathComponent("noop-replaced-\(timestamp()).sqlite")
            if fm.fileExists(atPath: dbURL.path) {
                if fm.fileExists(atPath: sidecar.path) { try fm.removeItem(at: sidecar) }
                try fm.copyItem(at: dbURL, to: sidecar)
            } else {
                // Nothing to preserve (fresh install); report a placeholder so the message reads sensibly.
                sidecar = dbURL
            }

            // Remove the live DB and its WAL/SHM siblings, then drop the backup in. `swapSource` is the
            // staged file for a Room backup, or the freshly-migrated Room file for a legacy GRDB backup.
            removeIfPresent(dbURL)
            removeIfPresent(URL(fileURLWithPath: dbPath + "-wal"))
            removeIfPresent(URL(fileURLWithPath: dbPath + "-shm"))

            do {
                try fm.copyItem(at: swapSource, to: dbURL)
            } catch {
                // The live DB was just removed and the replacement didn't land. Roll back to the
                // snapshot so a failed import leaves the user's data exactly as it was, instead of a
                // fresh-empty DB on relaunch (mirrors the Android rollback). Clear any partial-copy
                // leftover first — copyItem fails if the destination exists, which would otherwise
                // block the restore.
                if sidecar != dbURL, fm.fileExists(atPath: sidecar.path) {
                    removeIfPresent(dbURL)
                    try? fm.copyItem(at: sidecar, to: dbURL)
                }
                return .failure(String(localized: "Import failed. Your existing data was kept. \(error.localizedDescription)"))
            }

            // #1014 defence-in-depth, post-swap: re-verify the file that actually LANDED at the
            // live path with a second read-only quick_check. The staged file was verified above,
            // but the copy itself can tear — disk-full mid-copy, a dying filesystem, the device
            // sleeping — and the next launch would meet a corrupt store. Runs BEFORE any legacy
            // sidecars are laid down: a bare main file is always read-only verifiable, and WAL
            // frames carry their own checksums (SQLite validates them on the first real open), so
            // the sidecars don't need this gate. On failure, roll back to the snapshot
            // AUTOMATICALLY and say so; the snapshot file is kept either way (same policy as a
            // successful import: the user can always reach the pre-import bytes).
            if let complaint = DatabaseIntegrity.quickCheckFailure(atPath: dbURL.path) {
                removeIfPresent(dbURL)
                if sidecar != dbURL, fm.fileExists(atPath: sidecar.path) {
                    try? fm.copyItem(at: sidecar, to: dbURL)
                    return .failure(String(localized: "Import failed its post-restore integrity check (SQLite reports: \(complaint)). Your previous data was rolled back automatically and is unchanged."))
                }
                // Fresh install: there was no previous store to preserve, so removing the damaged
                // file (done above) restores the exact pre-import state — an empty slate.
                return .failure(String(localized: "Import failed its post-restore integrity check (SQLite reports: \(complaint)). The damaged file was removed; there was no previous data to roll back."))
            }

            // The imported/migrated `noop.db` has landed and passed its integrity check. Write the
            // completion sentinel beside it (Phase 2c-1 Task 7) so the next launch's
            // `WhoopStore.detectMigrateOpen` opens THIS file as-is, instead of mistaking a
            // sentinel-less Room file for a crashed partial ETL and deleting it (which would silently
            // discard the restore). Idempotent: a live Room store already has one; this refreshes it.
            WhoopStore.markRoomDatabaseImported(roomPath: dbPath)

            // Restore sidecars only for a legacy plain-SQLite backup swapped in DIRECTLY (a Room-format
            // `.sqlite` whose WAL wasn't checkpointed at export). Not for a ZIP import (always
            // checkpointed) nor for a GRDB backup routed through the ETL (its output is a fresh Room
            // file, and the staged GRDB sidecars would corrupt it). Deliberately AFTER the post-swap
            // integrity check — best-effort (`try?` inside), can't throw, rollback semantics unchanged.
            if extractedDir == nil && swapSource.path == source.path {
                restoreSidecar(from: source, toMainPath: dbPath, suffix: "-wal")
                restoreSidecar(from: source, toMainPath: dbPath, suffix: "-shm")
            }

            // #1000: re-apply the backup's whitelisted profile/display settings (weight, height, age,
            // sex, HR-max override, unit prefs) — but only NOW, after the DB swap landed. A failed or
            // rolled-back restore returns above and never touches settings. Legacy single-entry ZIPs
            // and plain-SQLite backups have no `settings.json` (extractedDir nil / entry absent) and
            // restore exactly as before — no settings, no error. A malformed settings entry degrades
            // to "fewer keys applied" inside BackupSettings.decode; it can never fail the restore.
            if let extractedDir {
                let settingsURL = extractedDir.appendingPathComponent(BackupSettings.entryName)
                if let data = try? Data(contentsOf: settingsURL) {
                    BackupSettings.apply(BackupSettings.decode(data), to: settingsDefaults)
                }
            }
            // #57 debug: record when a restore swapped the DB, so the export can correlate a restore with a
            // later write stall (a restore not followed by a relaunch is the #57 failure).
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "backup.lastRestoreAt")
            return .imported(sidecar: sidecar)
        } catch {
            return .failure(String(localized: "Import failed: \(error.localizedDescription)"))
        }
    }

    // MARK: - Helpers

    /// The live database to EXPORT (Phase 2c-1 Task 7). The store is the shared Room `noop.db` now, so
    /// export that. Falls back to the legacy GRDB `whoop.sqlite` ONLY when no Room file exists yet, the
    /// rare `.legacyGrdb` state after an interrupted ETL where the user's live data is still the GRDB
    /// file (exporting it there produces a legacy backup, which import upgrades through the ETL). When
    /// Room is live BOTH files exist and `noop.db` (the source of truth) wins. Kept in lockstep with
    /// `checkpointForBackup`, which checkpoints whichever backend is live before this resolves.
    private static func liveExportDatabasePath() throws -> String {
        let roomPath = try StorePaths.roomDatabasePath()
        if FileManager.default.fileExists(atPath: roomPath) { return roomPath }
        return try StorePaths.defaultDatabasePath()
    }

    /// Canonical entry name for the SQLite inside a `.noopbak` ZIP. Matches the Android exporter so
    /// a backup produced on either platform restores on the other.
    private static let backupEntryName = "noop-backup.sqlite"

    /// "NOOP-backup-2026-06-07.noopbak"
    private static func defaultBackupName() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return "NOOP-backup-\(f.string(from: Date())).noopbak"
    }

    private static func timestamp() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd-HHmmss"
        return f.string(from: Date())
    }

    /// Content types accepted by the export/import panels. Includes the new `.noopbak` (ZIP),
    /// generic ZIP, and legacy `.sqlite` / `.database` types so older backups keep working.
    private static func backupContentTypes() -> [UTType] {
        var types: [UTType] = []
        if let noopbak = UTType(filenameExtension: "noopbak") { types.append(noopbak) }
        types.append(.zip)
        if let sqlite = UTType(filenameExtension: "sqlite") { types.append(sqlite) }
        types.append(.database)
        types.append(.data)
        return types
    }

    /// Which migrator's bookkeeping a NOOP backup carries, deciding how `restore` handles it after the
    /// Phase 2c-1 Task 7 flip: `.room` (`room_master_table`) is this app's NATIVE format now, swapped
    /// straight onto `noop.db`; `.grdb` (`grdb_migrations`, any pre-2c-1 Mac/iOS export) is a legacy
    /// backup migrated through the ETL on import; `.unknown` (neither marker) is an empty file or some
    /// other app's database, judged by whether it holds data.
    enum BackupOrigin: Equatable { case grdb, room, unknown }

    /// Pure classification over a backup's `sqlite_master` table names: Room (this app now, and the
    /// Android app) writes `room_master_table`; GRDB (the pre-2c-1 Mac/iOS app) writes
    /// `grdb_migrations`. `.unknown` (neither, an empty or pre-migration file) falls through to the
    /// normal import path. Mirrors the shared `BackupRestore.backupOriginOf` byte-for-byte (Room is the
    /// accepted native on both), including the tie-break: Room wins the degenerate both-present case.
    static func backupOrigin(of tableNames: Set<String>) -> BackupOrigin {
        if tableNames.contains("room_master_table") { return .room }
        if tableNames.contains("grdb_migrations") { return .grdb }
        // Older Room layouts didn't carry `room_master_table`; treat the Room/AndroidX duo of
        // `android_metadata` + an internal `sqlite_sequence` as a Room backup too.
        if tableNames.contains("android_metadata") && tableNames.contains("sqlite_sequence") {
            return .room
        }
        return .unknown
    }

    /// Does this backup actually hold app data (vs an empty/fresh file)? True when it carries any
    /// user-content table beyond the SQLite/Android/GRDB housekeeping ones. Mirrors the shared
    /// `BackupRestore.holdsData` (and `WhoopStore.holdsData`) so the import gate, the quarantine gate,
    /// and Android all agree on what "holds data" means.
    static func holdsData(_ tableNames: Set<String>) -> Bool {
        let housekeeping: Set<String> = ["android_metadata", "sqlite_sequence", "room_master_table", "grdb_migrations"]
        return tableNames.contains { !housekeeping.contains($0) && !$0.hasPrefix("sqlite_") }
    }

    /// Every table name in a SQLite file, opened READ-ONLY through the system SQLite so the probed
    /// file is never mutated. Returns an empty set on any failure — the caller treats that as
    /// `.unknown` and falls through to the existing behaviour.
    private static func sqliteTableNames(at url: URL) -> Set<String> {
        var db: OpaquePointer?
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else {
            sqlite3_close(db)
            return []
        }
        defer { sqlite3_close(db) }
        var stmt: OpaquePointer?
        let sql = "SELECT name FROM sqlite_master WHERE type = 'table'"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        var names: Set<String> = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let c = sqlite3_column_text(stmt, 0) {
                names.insert(String(cString: c))
            }
        }
        return names
    }

    /// Read the first 4 bytes and check for the ZIP PK magic (`PK\x03\x04`).
    private static func isZipFile(at url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: 4), head.count >= 4 else { return false }
        return head[0] == 0x50 && head[1] == 0x4B && head[2] == 0x03 && head[3] == 0x04
    }

    /// Extract the SQLite entry from a `.noopbak` ZIP at `zipURL` into `destDir`. Each file entry is
    /// written under its own last-path-component, so the SQLite lands as `<destDir>/<name>.sqlite`
    /// for the caller to locate. Uses the `Archive` reader (the repo's ZIPFoundation idiom).
    private static func extractBackupZip(at zipURL: URL, into destDir: URL) throws {
        let archive = try Archive(url: zipURL, accessMode: .read)
        for entry in archive where entry.type == .file {
            let name = (entry.path as NSString).lastPathComponent
            let out = destDir.appendingPathComponent(name)
            _ = try archive.extract(entry, to: out)
        }
    }

    /// Read the first 16 bytes and check for the SQLite magic header.
    private static func isSQLiteFile(at url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: 16), head.count >= 16 else { return false }
        // "SQLite format 3" + NUL terminator.
        let magic: [UInt8] = Array("SQLite format 3".utf8) + [0x00]
        return Array(head) == magic
    }

    private static func removeIfPresent(_ url: URL) {
        let fm = FileManager.default
        if fm.fileExists(atPath: url.path) { try? fm.removeItem(at: url) }
    }

    /// Copy a legacy backup's `<source><suffix>` sidecar next to the live DB if it exists, so an
    /// old plain-SQLite backup whose WAL wasn't checkpointed at export restores its committed pages
    /// (SQLite folds them in on open). Not called for ZIP imports (those are always checkpointed).
    private static func restoreSidecar(from source: URL, toMainPath dbPath: String, suffix: String) {
        let fm = FileManager.default
        let src = URL(fileURLWithPath: source.path + suffix)
        guard fm.fileExists(atPath: src.path) else { return }
        let dst = URL(fileURLWithPath: dbPath + suffix)
        if fm.fileExists(atPath: dst.path) { try? fm.removeItem(at: dst) }
        try? fm.copyItem(at: src, to: dst)
    }
}
