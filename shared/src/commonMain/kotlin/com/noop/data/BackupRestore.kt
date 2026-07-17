package com.noop.data

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/**
 * Multiplatform core of `.noopbak` restore: stage, validate, swap. Mirrors the Android
 * `DataBackup.importFrom` flow step for step (and its user-facing failure messages verbatim), so
 * delegating Android keeps byte-identical behavior while iOS gains the same restore for free.
 *
 * The engine operates on FILE PATHS only. It never opens, closes, or otherwise assumes anything
 * about a live Room instance: the CALLER owns closing any open database on [restore]'s target path
 * BEFORE calling it (Android: `WhoopDatabase.close()`; iOS: close the `whoopDatabase(path)`
 * instance you hold; `WhoopDatabase.close()` is a no-op there and `openWhoopDatabase()` throws,
 * by Task 5 design). After a successful restore the caller must re-open the database fresh
 * (Android: restart the app; iOS: build a new `whoopDatabase(path)`).
 *
 * Restore sequence (the recon-documented Android order):
 *  1. stage the archive's SQLite (+ optional `settings.json`, #1000) next to [restore]'s target
 *  2. SQLite magic check on the staged file
 *  3. origin check over `sqlite_master` (reject `grdb_migrations`, a Mac/iOS GRDB store, and
 *     foreign databases that hold data but no recognised bookkeeping)
 *  4. `PRAGMA quick_check(1)` on the staged file (#1014: read-only, before the live DB is touched)
 *  5. snapshot the current live file to `<name>.import-bak`
 *  6. swap the staged file in, delete stale `-wal` / `-shm` sidecars
 *  7. re-run quick_check on the LANDED file; roll back to the snapshot automatically on failure
 *  8. apply the staged settings payload via [restore]'s hook (never fails the restore), clean up
 */
object BackupRestore {

    /** Entry name of the SQLite inside the `.noopbak` ZIP (cross-platform container contract). */
    const val ZIP_ENTRY_NAME = "noop-backup.sqlite"

    /** Entry name of the optional whitelisted-settings JSON (#1000). */
    const val SETTINGS_ENTRY_NAME = BackupSettingsCodecKmp.ENTRY_NAME

    /** Staged-file names, created in the work directory ([restore] uses the live DB's parent). */
    const val STAGED_DB_NAME = "import-extract.sqlite"
    const val STAGED_SETTINGS_NAME = "import-settings.json"

    /** First 16 bytes of every SQLite 3 file: "SQLite format 3\0". */
    private val SQLITE_MAGIC: ByteArray =
        byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
        )

    /** First 4 bytes of every ZIP file: "PK". */
    private val ZIP_MAGIC: ByteArray = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** Outcome of [stage]: the SQLite was staged, or why it wasn't. Mirrors DataBackup.StageResult. */
    enum class StageResult { OK, CANNOT_OPEN, NO_DB_IN_ZIP, NOT_A_BACKUP }

    /** Outcome of [restore]. On success the caller must re-open the database fresh. */
    sealed interface RestoreResult {
        /** The new database is in place; re-open it (Android: relaunch NOOP). */
        data object NeedsReopen : RestoreResult

        /** Restore failed and the database at the live path is untouched (or rolled back). */
        data class Failed(val message: String) : RestoreResult
    }

    /**
     * Extract `noop-backup.sqlite` (+ optional `settings.json`) from [archive] into [workDir] as
     * [STAGED_DB_NAME] / [STAGED_SETTINGS_NAME]. Handles both the `.noopbak` ZIP and a legacy plain
     * SQLite file. A stale staged-settings file is deleted first (a leftover from an earlier import
     * must not masquerade as THIS backup's settings). May throw [IOException] on a torn archive,
     * exactly like the Android stream twin; [restore] maps that to the read-failure message.
     */
    fun stage(fs: FileSystem, archive: Path, workDir: Path): StageResult {
        // 1. Peek at the first 16 bytes to distinguish ZIP from plain SQLite (mirrors the Android
        //    header peek). An unopenable archive is CANNOT_OPEN; a too-short file can't be either
        //    format.
        val header = try {
            fs.source(archive).buffer().use { source ->
                val bytes = ByteArray(16)
                var offset = 0
                while (offset < bytes.size) {
                    val n = source.read(bytes, offset, bytes.size - offset)
                    if (n < 0) break
                    offset += n
                }
                bytes.copyOf(offset)
            }
        } catch (e: okio.FileNotFoundException) {
            return StageResult.CANNOT_OPEN
        }

        fs.createDirectories(workDir)
        val stagedDb = workDir / STAGED_DB_NAME
        val stagedSettings = workDir / STAGED_SETTINGS_NAME
        // Stale-delete first: a leftover settings file from an earlier import must not masquerade
        // as THIS backup's settings (its absence is legitimate: every pre-#1000 backup).
        fs.delete(stagedSettings, mustExist = false)

        return when {
            header.startsWith(ZIP_MAGIC) -> {
                val zip = fs.openZip(archive)
                val entries = zip.listRecursively("/".toPath())
                    .filter { zip.metadataOrNull(it)?.isRegularFile == true }
                    .toList()
                // Both platforms' exporters write the DB as the FIRST (and only) `.sqlite` entry;
                // match by suffix like the Android stream twin does.
                val dbEntry = entries.firstOrNull { it.name.endsWith(".sqlite") }
                    ?: return StageResult.NO_DB_IN_ZIP
                copyOut(fs, zip, dbEntry, stagedDb)
                entries.firstOrNull { it.name == SETTINGS_ENTRY_NAME }?.let { entry ->
                    copyOut(fs, zip, entry, stagedSettings)
                }
                StageResult.OK
            }
            header.startsWith(SQLITE_MAGIC) -> {
                // Legacy plain `.sqlite` / `.noopdb`: copy straight through.
                fs.copy(archive, stagedDb)
                StageResult.OK
            }
            else -> StageResult.NOT_A_BACKUP
        }
    }

    /** Stream one zip [entry] out of [zip] into [dest] on the real file system. */
    private fun copyOut(fs: FileSystem, zip: FileSystem, entry: Path, dest: Path) {
        zip.source(entry).buffer().use { source ->
            fs.sink(dest).buffer().use { sink -> sink.writeAll(source) }
        }
    }

    /**
     * Full restore of [archive] over [liveDbPath]: stage -> sqlite magic check -> origin check ->
     * quick_check -> snapshot -> swap -> re-check -> rollback on failure. Stages into
     * [liveDbPath]'s parent directory. [applySettings] receives the raw `settings.json` payload
     * AFTER a successful swap only, wrapped so it can never fail the restore.
     *
     * The caller MUST have closed any open database instance on [liveDbPath] first; see the
     * class KDoc for the per-platform contract.
     */
    fun restore(
        fs: FileSystem,
        archive: Path,
        liveDbPath: Path,
        applySettings: (String) -> Unit = {},
    ): RestoreResult {
        val workDir = liveDbPath.parent
            ?: return RestoreResult.Failed("Import failed, your data is unchanged: no parent directory for $liveDbPath")
        val stagedDb = workDir / STAGED_DB_NAME
        val stagedSettings = workDir / STAGED_SETTINGS_NAME

        fun cleanStaged() {
            fs.delete(stagedDb, mustExist = false)
            // The read-write quick_check fallback can materialise WAL sidecars beside the staged
            // copy during open-time recovery; without this they outlive the import (seen on-device
            // as orphaned `import-extract.sqlite-shm`/`-wal`).
            fs.delete("$stagedDb-wal".toPath(), mustExist = false)
            fs.delete("$stagedDb-shm".toPath(), mustExist = false)
            fs.delete(stagedSettings, mustExist = false)
        }

        // 2. Stage the archive's SQLite (+ optional settings) next to the live file. Failure
        //    messages are the Android importFrom strings VERBATIM (behavior parity).
        try {
            when (stage(fs, archive, workDir)) {
                StageResult.OK -> Unit
                StageResult.CANNOT_OPEN -> return RestoreResult.Failed("Could not open the chosen file.")
                StageResult.NO_DB_IN_ZIP -> {
                    fs.delete(stagedSettings, mustExist = false)
                    return RestoreResult.Failed("The backup archive doesn't contain a database file.")
                }
                StageResult.NOT_A_BACKUP -> return RestoreResult.Failed(
                    "That file is not a NOOP backup - it doesn't look like a .noopbak archive or a SQLite database."
                )
            }
        } catch (e: IOException) {
            cleanStaged()
            return RestoreResult.Failed("Could not read the chosen file: ${e.message}")
        }

        // 3. Validate the staged file is a real SQLite database (magic-byte check).
        if (!hasSqliteMagic(fs, stagedDb)) {
            cleanStaged()
            return RestoreResult.Failed("The backup archive doesn't contain a valid NOOP database.")
        }

        // 3b. Origin check (parity with the Apple side's GRDB-origin rejection): read the backup's
        //     table names READ-ONLY and reject anything that isn't a this-app backup but still
        //     holds real data. Empty/pre-migration files fall through to the open-time migrator.
        val backupTables = sqliteTableNames(stagedDb.toString())
        when (backupOriginOf(backupTables)) {
            BackupOrigin.MAC -> {
                cleanStaged()
                return RestoreResult.Failed(
                    "This isn't a NOOP backup from this app. It looks like a backup from the Mac or " +
                        "iOS NOOP app (it carries that platform's migration bookkeeping). Restoring it here " +
                        "would strand your store. To move your history across platforms, export the " +
                        "WHOOP-format CSV on the other device (Settings → Export data) and import that here.",
                )
            }
            BackupOrigin.UNKNOWN ->
                if (holdsData(backupTables)) {
                    cleanStaged()
                    return RestoreResult.Failed(
                        "This isn't a NOOP backup from this app. It's missing the database bookkeeping a " +
                            "NOOP backup carries (it looks like another app's database). Restoring it would " +
                            "strand your store.",
                    )
                }
            BackupOrigin.ANDROID -> Unit // our own backup, proceed.
        }

        // 3c. #1014 defence-in-depth: the gates above read only the FIRST pages of the file. Run
        //     SQLite's own `PRAGMA quick_check` over the STAGED file, read-only, BEFORE the live
        //     DB is touched, and refuse the swap honestly.
        sqliteQuickCheck(stagedDb.toString())?.let { complaint ->
            cleanStaged()
            return RestoreResult.Failed(
                "This backup file is damaged and can't be restored (SQLite reports: $complaint). " +
                    "Your current data is untouched. Try an earlier backup file."
            )
        }

        // 3d. Schema-version gate: a backup written by a NEWER schema (SQLite `user_version`
        //     above ours) passes every gate above — it IS a healthy this-app database — but Room
        //     has no downgrade path, so swapping it in would crash-loop the app on every launch
        //     after the import (Room throws from onDowngrade at open time, AFTER the restore
        //     already reported success). Refuse the swap while the live DB is still untouched.
        val backupVersion = sqliteUserVersion(fs, stagedDb)
        if (backupVersion != null && backupVersion > WhoopDatabase.SCHEMA_VERSION) {
            cleanStaged()
            return RestoreResult.Failed(
                "This backup was created by a newer version of the app (database schema " +
                    "$backupVersion; this version understands up to ${WhoopDatabase.SCHEMA_VERSION}). " +
                    "Update the app, then import it again. Your current data is untouched."
            )
        }

        val walFile = "$liveDbPath-wal".toPath()
        val shmFile = "$liveDbPath-shm".toPath()
        val rollbackFile = "$liveDbPath.import-bak".toPath()

        // (The caller closed any open database on liveDbPath BEFORE this call; see the class KDoc.)

        // 5. Snapshot the current db so a failed copy can be rolled back.
        try {
            fs.delete(rollbackFile, mustExist = false)
            if (fs.exists(liveDbPath)) fs.copy(liveDbPath, rollbackFile)
        } catch (e: IOException) {
            cleanStaged()
            return RestoreResult.Failed("Could not back up the current data: ${e.message}")
        }

        // 6. Overwrite the db file with the staged backup, then drop the stale sidecars.
        try {
            fs.copy(stagedDb, liveDbPath)
            fs.delete(walFile, mustExist = false)
            fs.delete(shmFile, mustExist = false)
        } catch (e: IOException) {
            runCatching { if (fs.exists(rollbackFile)) fs.copy(rollbackFile, liveDbPath) }
            fs.delete(rollbackFile, mustExist = false)
            cleanStaged()
            return RestoreResult.Failed("Import failed, your data is unchanged: ${e.message}")
        }

        // 6b. #1014 post-swap: re-verify the file that actually LANDED at the live path (the copy
        //     itself can tear). On failure, roll back to the `.import-bak` snapshot automatically.
        sqliteQuickCheck(liveDbPath.toString())?.let { complaint ->
            cleanStaged()
            fs.delete(walFile, mustExist = false)
            fs.delete(shmFile, mustExist = false)
            val message: String
            if (fs.exists(rollbackFile)) {
                if (runCatching { fs.copy(rollbackFile, liveDbPath) }.isSuccess) {
                    fs.delete(rollbackFile, mustExist = false)
                    message = "The backup failed its integrity check after the copy (SQLite reports: " +
                        "$complaint). Your previous data was rolled back automatically and is unchanged."
                } else {
                    // The roll-back copy itself failed: KEEP the snapshot on disk (it is now the
                    // only good copy of the user's data) and say exactly where it is.
                    message = "The backup failed its integrity check after the copy (SQLite reports: " +
                        "$complaint), and rolling back also failed. Your previous data is preserved at " +
                        "${rollbackFile.name} next to the app's database."
                }
            } else {
                // Fresh install: nothing existed before the import, so removing the damaged file
                // returns to the exact pre-import (empty) state.
                fs.delete(liveDbPath, mustExist = false)
                message = "The backup failed its integrity check after the copy (SQLite reports: " +
                    "$complaint). There was no previous data to roll back."
            }
            return RestoreResult.Failed(message)
        }

        // 7. #1000: hand the backup's whitelisted settings payload to the caller, but only NOW,
        //    after the DB swap landed. Every failure path above returns without touching settings.
        //    A misbehaving hook degrades silently and can never fail the restore.
        if (fs.exists(stagedSettings)) {
            runCatching { applySettings(fs.read(stagedSettings) { readUtf8() }) }
            fs.delete(stagedSettings, mustExist = false)
        }

        fs.delete(rollbackFile, mustExist = false)
        cleanStaged()
        return RestoreResult.NeedsReopen
    }

    /**
     * The SQLite `user_version` of the file at [path] (Room stores its schema version there),
     * read straight from the file header (big-endian u32 at byte offset 60) without opening
     * SQLite. Header-only is authoritative here: the staged file is a standalone checkpointed
     * copy with no `-wal` sidecar that could hold a newer header page. Null when the header
     * can't be read (short file, I/O error) — the caller treats that as "no verdict", exactly
     * like the other probes, because quick_check already vouched for the file's integrity.
     */
    internal fun sqliteUserVersion(fs: FileSystem, path: Path): Int? = runCatching {
        fs.source(path).buffer().use { source ->
            source.skip(60)
            source.readInt()
        }
    }.getOrNull()

    /** True when the file at [path] begins with the SQLite 3 magic. */
    private fun hasSqliteMagic(fs: FileSystem, path: Path): Boolean = runCatching {
        val bytes = fs.source(path).buffer().use { source ->
            val buf = ByteArray(SQLITE_MAGIC.size)
            var offset = 0
            while (offset < buf.size) {
                val n = source.read(buf, offset, buf.size - offset)
                if (n < 0) break
                offset += n
            }
            buf.copyOf(offset)
        }
        bytes.size >= SQLITE_MAGIC.size && bytes.contentEquals(SQLITE_MAGIC)
    }.getOrDefault(false)

    /** True when [this] begins with every byte in [prefix]. */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    // ── Pure helpers, ported verbatim from the Android DataBackup ───────────────────────────

    /** Which platform produced a NOOP backup, judged by its migrator's bookkeeping table. */
    enum class BackupOrigin { MAC, ANDROID, UNKNOWN }

    /**
     * Pure classification over a backup's `sqlite_master` table names: Room (this app) writes
     * `room_master_table`; GRDB (the Mac/iOS app) writes `grdb_migrations`. `.UNKNOWN` (neither,
     * an empty or pre-migration file) falls through to the normal open path. Mirrors the Apple
     * `DataBackup.backupOrigin(of:)` byte-for-byte; the Android `DataBackup.backupOriginOf`
     * delegates here.
     */
    fun backupOriginOf(tableNames: Set<String>): BackupOrigin {
        if (tableNames.contains("room_master_table")) return BackupOrigin.ANDROID
        if (tableNames.contains("grdb_migrations")) return BackupOrigin.MAC
        // Older Room layouts didn't carry `room_master_table`; treat the Room/AndroidX pairing of
        // `android_metadata` + `sqlite_sequence` as one of ours too (mirrors the Apple side).
        if (tableNames.contains("android_metadata") && tableNames.contains("sqlite_sequence")) {
            return BackupOrigin.ANDROID
        }
        return BackupOrigin.UNKNOWN
    }

    /**
     * Does this backup actually hold app data (vs an empty/fresh file)? True when it carries any
     * user-content table beyond the SQLite/Android/GRDB housekeeping ones.
     */
    fun holdsData(tableNames: Set<String>): Boolean {
        val housekeeping = setOf("android_metadata", "sqlite_sequence", "room_master_table", "grdb_migrations")
        return tableNames.any { it !in housekeeping && !it.startsWith("sqlite_") }
    }

    /**
     * Pure classification of the rows `PRAGMA quick_check` returned: null = healthy (the single
     * canonical "ok" row), otherwise the first complaint row VERBATIM, never a fabricated
     * summary. An EMPTY result set is a failure too: quick_check always answers, so silence means
     * the query was swallowed and the file must not be trusted. Twin of the Apple
     * `DatabaseIntegrity.verdict(fromRows:)`; the Android `DataBackup.quickCheckVerdict` delegates
     * here, so the golden vectors stay pinned on all platforms.
     */
    fun quickCheckVerdict(rows: List<String>): String? {
        if (rows.size == 1 && rows[0].equals("ok", ignoreCase = true)) return null
        return rows.firstOrNull { !it.equals("ok", ignoreCase = true) }
            ?: "quick_check returned no verdict"
    }
}

/**
 * Run `PRAGMA quick_check(1)` on the database file at [path]. Returns null when healthy, otherwise
 * a short human-readable complaint (verbatim first failure row, or an open/query failure note).
 * Opens read-only where the platform allows, never mutates the probed file, and must NEVER delete
 * it (the Android actual carries the #1014 preserve-on-corruption handler for exactly that).
 */
expect fun sqliteQuickCheck(path: String): String?

/** Every table name in the database at [path], read-only; empty on failure. */
internal expect fun sqliteTableNames(path: String): Set<String>
