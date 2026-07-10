package com.noop.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Apple open path for [WhoopDatabase] (Phase 2a KMP split; generalized from iosMain to appleMain in
 * Phase 2b Task 1, unchanged, since the bundled driver builder is Darwin-generic). iOS and macOS both
 * build the database from a file path with the bundled SQLite driver (its own SQLite, independent of
 * the host libsqlite version) and run queries on [Dispatchers.IO], mirroring the GRDB store the Swift
 * app uses. A fresh database is created straight at the current schema version (no in-place upgrade
 * path), so no migrations are registered.
 */
fun whoopDatabase(path: String): WhoopDatabase =
    Room.databaseBuilder<WhoopDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/**
 * Placeholder for the Android context factory. Apple targets never open the database through
 * `WhoopDatabase.get(context)`; they use [whoopDatabase] with an explicit file path instead.
 */
actual abstract class PlatformContext

internal actual fun openWhoopDatabase(context: PlatformContext): WhoopDatabase =
    error("WhoopDatabase.get(context) is Android-only; on Apple targets build with whoopDatabase(path).")

internal actual fun closeWhoopDatabase() {
    // No shared singleton on Apple targets: callers own the WhoopDatabase instance and close it directly.
}
