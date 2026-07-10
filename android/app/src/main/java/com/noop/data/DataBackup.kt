package com.noop.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Whole-store EXPORT / IMPORT for device migration.
 *
 * NOOP keeps everything on-device in a single Room/SQLite file ([WhoopDatabase.DB_NAME]).
 * Moving to a new phone therefore means moving exactly that one file. There is no cloud,
 * no account, nothing leaves the device except through these two explicit, user-driven
 * file operations (a SAF document the user picks).
 *
 * Export: checkpoint the WAL into the main db file, then write a ZIP (the `.noopbak`
 * format) containing the SQLite file plus a small `settings.json` entry (#1000) with the
 * whitelisted profile/display settings (see [BackupSettingsCodec]), so a restore also
 * brings back weight/height/units and not just the rows. ZIP deflate typically reduces a
 * 100 MB+ SQLite backup to 10–20 MB — SQLite's page-aligned text data compresses very
 * well. The ZIP is a standard container: users can rename `.noopbak` → `.zip` and
 * extract the SQLite manually with any archive tool on any OS.
 *
 * Import: detect whether the picked file is a `.noopbak` ZIP (PK magic) or a legacy
 * plain `.sqlite` / `.noopdb` (SQLite magic) and handle both, so old backups keep
 * working. Validates the extracted/direct SQLite header, the backup's origin, AND its
 * structural integrity (`PRAGMA quick_check`, #1014) before touching the live DB.
 * Closes the live Room singleton, snapshots the current db, overwrites it with the
 * chosen one, drops the stale `-wal` / `-shm` sidecars, then re-verifies the landed
 * file and rolls back to the snapshot automatically if the copy tore (#1014). The
 * caller then instructs the user to restart the app so Room re-opens the new file fresh.
 *
 * Phase 2a (Task 8): the stage/validate/swap core of that import sequence lives in the
 * multiplatform [BackupRestore] engine in `shared` (same steps, same user-facing messages,
 * proven against a real backup on the iOS simulator). This object keeps the Android skin:
 * SAF Uri/ContentResolver handling, the [WhoopDatabase.close] call BEFORE delegating (the
 * engine operates on file paths only and never closes a database), the settings bridge, and
 * prefs recording. The pure helpers ([quickCheckVerdict], [backupOriginOf], [holdsData])
 * delegate to their shared ports so the contract stays defined once.
 *
 * Lives in the app (not `shared`, unlike the rest of `com.noop.data`): it reads
 * [BackupSettingsCodec]/[BackupSettingsBridge] and writes `com.noop.ui.NoopPrefs` directly, both
 * app-only. [CorruptionPreservingOpenHelperFactory], the one piece [WhoopDatabase] itself needs,
 * stays behind in `shared` as its own file.
 */
object DataBackup {

    /** Entry name of the SQLite inside the `.noopbak` ZIP. */
    private const val ZIP_ENTRY_NAME = "noop-backup.sqlite"

    /** Entry name of the optional whitelisted-settings JSON (#1000). Matches the Apple exporter. */
    private const val SETTINGS_ENTRY_NAME = BackupSettingsCodec.ENTRY_NAME

    /** First 16 bytes of every SQLite 3 file: "SQLite format 3\0". */
    private val SQLITE_MAGIC: ByteArray =
        byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
        )

    /** First 4 bytes of every ZIP file: "PK\x03\x04". */
    private val ZIP_MAGIC: ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** Outcome of an [importFrom] call. On success the app must be restarted. */
    sealed interface ImportResult {
        /** The new database is in place; tell the user to relaunch NOOP. */
        data object NeedsRestart : ImportResult

        /** Import failed and the original database is untouched. */
        data class Failed(val message: String) : ImportResult
    }

    /**
     * Export the live database to [uri] as a compressed `.noopbak` (single-entry ZIP).
     *
     * Runs `PRAGMA wal_checkpoint(TRUNCATE)` first so the db file is fully consistent.
     * The ZIP uses deflate compression; typical reduction is 80–90% vs the raw SQLite.
     * Throws on failure so the caller can surface the message in a toast/snackbar.
     */
    @Throws(IOException::class)
    fun exportTo(context: Context, uri: Uri) {
        val appContext = context.applicationContext

        // Fold the WAL back into the main file so the snapshot is complete.
        val db = WhoopDatabase.get(appContext)
        db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            cursor.moveToFirst()
        }

        val dbFile = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
        if (!dbFile.exists()) {
            throw IOException("No database to export yet.")
        }

        // #1014 defence-in-depth (export side): after the checkpoint the single file IS the whole
        // store — verify it BEFORE archiving. A backup of an already-corrupt database only fails
        // the import-side integrity gate months later, when the original data may be long gone;
        // failing loudly NOW is the honest move. Read-only probe, sits safely beside the open Room
        // connection (WAL allows concurrent readers). Twin of the Apple writeVerifiedBackupZip.
        // (sqliteQuickCheck is the shared androidMain actual: the framework-SQLite probe that
        // used to live here as a private, #1014 preserve-on-corruption armour included.)
        sqliteQuickCheck(dbFile.path)?.let { complaint ->
            throw IOException(
                "Couldn't export: the NOOP database failed its integrity check (SQLite reports: " +
                    "$complaint). A backup of it would not restore. Export the WHOOP-format CSV " +
                    "instead to save what's still readable."
            )
        }

        // #1000: the whitelisted profile/display settings ride along as a second entry so a restore
        // brings back weight/height/units, not just the rows. Null (nothing user-set) degrades to the
        // legacy single-entry ZIP. The DB entry stays FIRST — older importers stop at the first
        // `.sqlite` entry, so entry order is part of the cross-platform container contract.
        val settingsJson = BackupSettingsBridge.snapshotJson(appContext)

        val resolver = appContext.contentResolver
        val output = resolver.openOutputStream(uri)
            ?: throw IOException("Could not open the chosen file for writing.")
        output.use { out ->
            // #1014: copy the file while HOLDING Room's write transaction. In WAL mode the main
            // file is only rewritten by a checkpoint, and a checkpoint only runs on a commit — so
            // with the (single) write connection parked in an empty transaction for the duration
            // of the copy, no commit can land and the bytes we stream can't be torn mid-page by a
            // concurrent auto-checkpoint. Writers queue behind us and proceed after; readers are
            // unaffected. Anything committed after the checkpoint above lives in the new WAL and
            // is simply (consistently) absent from this snapshot, same as before.
            db.runInTransaction {
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry(ZIP_ENTRY_NAME))
                    dbFile.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    if (settingsJson != null) {
                        zip.putNextEntry(ZipEntry(SETTINGS_ENTRY_NAME))
                        zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    /**
     * Replace the live database with the backup at [uri].
     *
     * Accepts both the new `.noopbak` (ZIP) format and legacy plain `.sqlite`/`.noopdb`
     * files so older backups keep working after the format upgrade.
     *
     * On any error the current database is left exactly as it was. On success the caller
     * MUST instruct the user to fully restart the app.
     */
    fun importFrom(context: Context, uri: Uri): ImportResult {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver

        // 1. Materialize the SAF document to a local cache file: the shared [BackupRestore] engine
        //    works on file paths (okio), and a Uri stream can only be read once, not probed. The
        //    too-short pre-check keeps the legacy "not a NOOP backup" message for degenerate picks.
        val archiveFile = File(appContext.cacheDir, "import-archive.noopbak")
        try {
            val input = resolver.openInputStream(uri)
                ?: return ImportResult.Failed("Could not open the chosen file.")
            input.use { stream -> FileOutputStream(archiveFile).use { out -> stream.copyTo(out) } }
        } catch (e: IOException) {
            archiveFile.delete()
            return ImportResult.Failed("Could not read the chosen file: ${e.message}")
        }
        if (archiveFile.length() < 4) {
            archiveFile.delete()
            return ImportResult.Failed("That file is not a NOOP backup.")
        }

        // 2. Close the live Room singleton so the file handles are released. The shared engine
        //    never closes a database itself (on iOS close is a no-op by design); the caller owns
        //    this, and here that caller is us.
        val dbFile = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
        WhoopDatabase.close()

        // 3. Delegate the whole stage -> magic check -> origin check -> quick_check -> snapshot ->
        //    swap -> re-check -> rollback sequence to the shared engine (identical steps and
        //    user-facing messages to the pre-KMP implementation; see BackupRestore's KDoc). The
        //    #1000 settings hook fires only after the swap landed, wrapped by the engine so a
        //    malformed settings entry can never fail a restore whose DB half is fine.
        val result = try {
            BackupRestore.restore(
                FileSystem.SYSTEM,
                archiveFile.toOkioPath(),
                dbFile.toOkioPath(),
            ) { json -> BackupSettingsBridge.apply(appContext, json) }
        } finally {
            archiveFile.delete()
        }

        return when (result) {
            is BackupRestore.RestoreResult.Failed -> ImportResult.Failed(result.message)
            BackupRestore.RestoreResult.NeedsReopen -> {
                // #57 debug: record when a restore swapped the DB, so the export can correlate a
                // restore with a subsequent write stall (a restore that wasn't followed by a
                // restart is exactly the #57 failure).
                runCatching {
                    com.noop.ui.NoopPrefs.of(appContext).edit()
                        .putLong("backup.lastRestoreAt", System.currentTimeMillis() / 1000L).apply()
                }
                ImportResult.NeedsRestart
            }
        }
    }

    // ── Container staging (pure file/stream layer, unit-tested under real file I/O) ──────

    /** Outcome of [stageBackupSqlite]: the SQLite was staged, or why it wasn't. */
    enum class StageResult { OK, CANNOT_OPEN, NO_DB_IN_ZIP, NOT_A_BACKUP }

    /**
     * Stage the SQLite payload of a backup into [dest], from an already-opened [input] stream whose
     * first bytes are [header]. Handles both the `.noopbak` ZIP (extract the `.sqlite` entry) and a
     * legacy plain SQLite (copy through). Closes [input]. Context-free + stream-driven so the unit
     * tests drive it with real `java.util.zip` archives and real files, exercising the exact extraction
     * the live import uses (no behaviour fork between test and production).
     *
     * When [settingsDest] is given, a `settings.json` entry (#1000) is ALSO staged there if the ZIP
     * carries one (either platform's exporter may have written it, in either entry order). Its absence
     * is not an error — every pre-#1000 backup is a single-entry ZIP — and it never affects the
     * returned [StageResult]: the DB is the payload that decides success.
     *
     * NOTE this does NOT validate the staged file's SQLite header or origin; [importFrom] does that
     * next, on the staged file. Keeping staging and validation separate keeps each pure-testable.
     */
    fun stageBackupSqlite(
        input: java.io.InputStream?,
        header: ByteArray,
        dest: File,
        settingsDest: File? = null,
    ): StageResult {
        if (input == null) return StageResult.CANNOT_OPEN
        input.use { stream ->
            when {
                header.startsWith(ZIP_MAGIC) -> {
                    var foundDb = false
                    var foundSettings = false
                    ZipInputStream(stream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            when {
                                !entry.isDirectory && !foundDb && entry.name.endsWith(".sqlite") -> {
                                    FileOutputStream(dest).use { out -> zip.copyTo(out) }
                                    foundDb = true
                                }
                                !entry.isDirectory && !foundSettings && settingsDest != null &&
                                    entry.name.substringAfterLast('/') == SETTINGS_ENTRY_NAME -> {
                                    FileOutputStream(settingsDest).use { out -> zip.copyTo(out) }
                                    foundSettings = true
                                }
                            }
                            // Everything we could want is staged - stop reading the archive.
                            if (foundDb && (settingsDest == null || foundSettings)) break
                            entry = zip.nextEntry
                        }
                    }
                    return if (foundDb) StageResult.OK else StageResult.NO_DB_IN_ZIP
                }
                header.startsWith(SQLITE_MAGIC) -> {
                    FileOutputStream(dest).use { out -> stream.copyTo(out) }
                    return StageResult.OK
                }
                else -> return StageResult.NOT_A_BACKUP
            }
        }
    }

    /** Write [dbFile]'s bytes into a deflate ZIP at [dest] (the `.noopbak` container), DB entry first,
     *  plus the optional `settings.json` entry (#1000) when [settingsJson] is non-null. Context-free
     *  twin of the stream the live [exportTo] writes, so tests round-trip a real archive of either
     *  shape (legacy single-entry when [settingsJson] is null). */
    @Throws(IOException::class)
    fun writeBackupZip(dbFile: File, dest: File, settingsJson: String? = null) {
        FileOutputStream(dest).use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(ZIP_ENTRY_NAME))
                dbFile.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                if (settingsJson != null) {
                    zip.putNextEntry(ZipEntry(SETTINGS_ENTRY_NAME))
                    zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    /** True when [file] begins with the SQLite 3 magic. Pure; used by [importFrom] and the tests. */
    fun isValidSqliteHeader(file: File): Boolean {
        val buf = ByteArray(SQLITE_MAGIC.size)
        return runCatching {
            val read = file.inputStream().use { readFully(it, buf) }
            read >= SQLITE_MAGIC.size && buf.contentEquals(SQLITE_MAGIC)
        }.getOrDefault(false)
    }

    /** First [n] bytes of [file] (or fewer at EOF): the header peek the import does on the raw file. */
    fun peekHeader(file: File, n: Int = 16): ByteArray {
        val buf = ByteArray(n)
        val read = runCatching { file.inputStream().use { readFully(it, buf) } }.getOrDefault(0)
        return buf.copyOf(read)
    }

    /** Read up to [buffer].size bytes from [input], looping over short reads. Returns bytes read. */
    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            offset += n
        }
        return offset
    }

    /** True when [this] begins with every byte in [prefix]. */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    // ── Origin validation (parity with the Apple GRDB-origin rejection) ─────────

    /** Which platform produced a NOOP backup, judged by its migrator's bookkeeping table. */
    enum class BackupOrigin { MAC, ANDROID, UNKNOWN }

    /**
     * Pure classification over a backup's `sqlite_master` table names: Room (this app) writes
     * `room_master_table`; GRDB (the Mac/iOS app) writes `grdb_migrations`. `.UNKNOWN` (neither, an
     * empty or pre-migration file) falls through to the normal import path, where Room's open-time
     * migrator decides. Delegates to the shared [BackupRestore.backupOriginOf] (Task 8), where the
     * classification, mirroring the Apple `DataBackup.backupOrigin(of:)` byte-for-byte, now lives;
     * this wrapper keeps the app-facing enum and API stable.
     */
    fun backupOriginOf(tableNames: Set<String>): BackupOrigin =
        when (BackupRestore.backupOriginOf(tableNames)) {
            BackupRestore.BackupOrigin.MAC -> BackupOrigin.MAC
            BackupRestore.BackupOrigin.ANDROID -> BackupOrigin.ANDROID
            BackupRestore.BackupOrigin.UNKNOWN -> BackupOrigin.UNKNOWN
        }

    /**
     * Does this backup actually hold app data (vs an empty/fresh file)? True when it carries any
     * user-content table beyond the SQLite/Android housekeeping ones. An `.UNKNOWN` file with no
     * content is harmless to restore; one WITH content but no recognised bookkeeping is some other
     * app's database and is rejected. Delegates to the shared [BackupRestore.holdsData].
     */
    fun holdsData(tableNames: Set<String>): Boolean = BackupRestore.holdsData(tableNames)

    // ── Integrity gate (#1014 defence-in-depth; twin of the Apple DatabaseIntegrity) ─────

    /**
     * Pure classification of the rows `PRAGMA quick_check` returned: null = healthy (the single
     * canonical "ok" row), otherwise the first complaint row VERBATIM — never a fabricated summary.
     * An EMPTY result set is a failure too: quick_check always answers, so silence means the query
     * was swallowed and the file must not be trusted. Mirrors the Apple side's
     * `DatabaseIntegrity.verdict(fromRows:)` byte-for-byte — the same golden vectors are pinned in
     * [DataBackupIntegrityTest] here and `DatabaseIntegrityTests` there, so both platforms agree on
     * what "healthy" means. Delegates to the shared [BackupRestore.quickCheckVerdict] (Task 8);
     * still public here so the plain-JVM app test drives the same entry point it always has.
     *
     * (The framework-SQLite probes that used to sit beside this (`sqliteQuickCheckFailure`,
     * `sqliteTableNames`, and the #1014 PRESERVE_ON_CORRUPTION handler) moved verbatim to the
     * shared module's androidMain actual, `SqliteQuickCheck.android.kt`.)
     */
    fun quickCheckVerdict(rows: List<String>): String? = BackupRestore.quickCheckVerdict(rows)
}
