package com.noop.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE

/**
 * iOS actuals for the [BackupRestore] SQLite probes, on the bundled SQLite driver (the same
 * driver the shared Room database uses on iOS, Task 5). Read-only open first: the probe must
 * never mutate the file it judges; the read-write fallback mirrors the Android actual (a
 * checkpointed backup is ours to touch, and open-time recovery is not a content change). The
 * bundled driver never deletes a corrupt file (unlike Android's framework default handler), so
 * no preserve-on-corruption armour is needed here.
 */

private fun openConnection(path: String): Result<SQLiteConnection> =
    runCatching { BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY) }
        .recoverCatching { BundledSQLiteDriver().open(path, SQLITE_OPEN_READWRITE) }

actual fun sqliteQuickCheck(path: String): String? {
    val conn = openConnection(path).getOrElse { return "could not open the database: ${it.message}" }
    return try {
        val rows = ArrayList<String>()
        val statement = conn.prepare("PRAGMA quick_check(1)")
        try {
            while (statement.step()) rows.add(statement.getText(0))
        } finally {
            statement.close()
        }
        BackupRestore.quickCheckVerdict(rows)
    } catch (e: Exception) {
        // The query failed outright (SQLITE_NOTADB on garbage behind a valid magic header, a
        // malformed page 1, …). That IS the verdict: the file is not a usable database.
        "quick_check failed: ${e.message}"
    } finally {
        runCatching { conn.close() }
    }
}

internal actual fun sqliteTableNames(path: String): Set<String> {
    val conn = openConnection(path).getOrNull() ?: return emptySet()
    return try {
        val names = LinkedHashSet<String>()
        val statement = conn.prepare("SELECT name FROM sqlite_master WHERE type = 'table'")
        try {
            while (statement.step()) names.add(statement.getText(0))
        } finally {
            statement.close()
        }
        names
    } catch (e: Exception) {
        emptySet()
    } finally {
        runCatching { conn.close() }
    }
}
