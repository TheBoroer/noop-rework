package com.noop.data

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase

/**
 * Android actuals for the [BackupRestore] SQLite probes, moved verbatim from the app's
 * `DataBackup` privates so the shared engine and the app share one implementation. Uses the
 * FRAMEWORK SQLite (android.database.sqlite), exactly as the app always has; behavior parity
 * with the pre-KMP `DataBackup.sqliteQuickCheckFailure` / `sqliteTableNames` is byte-for-byte.
 *
 * NOTE for tests: off-device (plain-JVM androidUnitTest, no Robolectric) the framework classes
 * are throwing stubs, so these actuals only work on a device/emulator; the shared restore tests
 * gate the quick_check-dependent path accordingly and prove it on the iOS simulator target.
 */

/**
 * A [DatabaseErrorHandler] that closes the handle and PRESERVES the file. The framework default
 * ([android.database.DefaultDatabaseErrorHandler]) DELETES the file it was probing on
 * SQLITE_CORRUPT/SQLITE_NOTADB, and every `openDatabase` overload without an explicit handler
 * inherits that. For the integrity probes here that would be catastrophic: the export probe opens
 * the LIVE database, so a corrupt store would be silently destroyed by the very check meant to
 * protect it (#1014). Also used by the origin probe for the same reason.
 */
private val PRESERVE_ON_CORRUPTION = DatabaseErrorHandler { dbObj ->
    runCatching { dbObj.close() }
}

/**
 * Run `PRAGMA quick_check(1)` on the database at [path]. Returns null when the file is healthy,
 * otherwise a short human-readable complaint for the caller's honest failure message.
 * `quick_check(1)` stops at the first error, so a damaged 100 MB library still answers quickly.
 *
 * Opens READ-ONLY first (never mutates the probed file; sits safely beside an open Room
 * connection; WAL allows concurrent readers). If the read-only open itself fails, falls back
 * to a read-write open: pre-3.22 SQLite (API 26/27, minSdk 26) cannot read-only-open a
 * WAL-header file without an initialized `-shm`, which is exactly what a checkpointed staged
 * backup looks like, and refusing those would break valid restores on Android 8.x. Every probed
 * file is ours to touch (the staged temp copy, the just-swapped live file, or the live store
 * the export is about to archive), and a read-write open only performs standard SQLite
 * recovery, never a content change. Both opens carry [PRESERVE_ON_CORRUPTION] so no probe can
 * ever delete what it probes.
 */
actual fun sqliteQuickCheck(path: String): String? {
    val db = runCatching {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY, PRESERVE_ON_CORRUPTION)
    }.recoverCatching {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE, PRESERVE_ON_CORRUPTION)
    }.getOrElse { return "could not open the database: ${it.message}" }
    return try {
        val rows = ArrayList<String>()
        db.rawQuery("PRAGMA quick_check(1)", null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let(rows::add)
        }
        BackupRestore.quickCheckVerdict(rows)
    } catch (e: Exception) {
        // The query failed outright (SQLITE_NOTADB on garbage behind a valid magic header,
        // a malformed page 1, …). That IS the verdict: the file is not a usable database.
        "quick_check failed: ${e.message}"
    } finally {
        runCatching { db.close() }
    }
}

/** Every table name in the database at [path], opened READ-ONLY so the probed file is never
 *  mutated. Empty on failure. Carries [PRESERVE_ON_CORRUPTION] (#1014): without an explicit
 *  handler the framework default would DELETE the probed file on SQLITE_NOTADB/CORRUPT. */
internal actual fun sqliteTableNames(path: String): Set<String> {
    val db = runCatching {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY, PRESERVE_ON_CORRUPTION)
    }.getOrNull() ?: return emptySet()
    return try {
        val names = LinkedHashSet<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let(names::add)
        }
        names
    } catch (e: Exception) {
        emptySet()
    } finally {
        runCatching { db.close() }
    }
}
