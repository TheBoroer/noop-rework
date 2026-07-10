package com.noop.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * iOS open path for [WhoopDatabase] (Phase 2a KMP split). iOS builds the database from a file path
 * with the bundled SQLite driver (its own SQLite, independent of the host libsqlite version) and runs
 * queries on [Dispatchers.IO], mirroring the GRDB store the Swift app uses. A fresh database is created
 * straight at the current schema version (no in-place upgrade path), so no migrations are registered.
 */
fun whoopDatabase(path: String): WhoopDatabase =
    Room.databaseBuilder<WhoopDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/**
 * Placeholder for the Android context factory. iOS never opens the database through
 * `WhoopDatabase.get(context)`; it uses [whoopDatabase] with an explicit file path instead.
 */
actual abstract class PlatformContext

internal actual fun openWhoopDatabase(context: PlatformContext): WhoopDatabase =
    error("WhoopDatabase.get(context) is Android-only; on iOS build with whoopDatabase(path).")

internal actual fun closeWhoopDatabase() {
    // No shared singleton on iOS: callers own the WhoopDatabase instance and close it directly.
}
