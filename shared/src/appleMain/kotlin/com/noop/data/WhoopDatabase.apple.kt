@file:OptIn(ExperimentalTime::class)

package com.noop.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Apple open path for [WhoopDatabase] (Phase 2a KMP split; generalized from iosMain to appleMain in
 * Phase 2b Task 1, unchanged, since the bundled driver builder is Darwin-generic). iOS and macOS both
 * build the database from a file path with the bundled SQLite driver (its own SQLite, independent of
 * the host libsqlite version) and run queries on [Dispatchers.IO], mirroring the GRDB store the Swift
 * app uses. A fresh database is created straight at the current schema version, but an EXISTING file
 * at an older schema (a restored backup via [BackupRestore], or a Room store left by an earlier app
 * version) must upgrade in place, so the builder registers [WhoopDatabase.ALL_MIGRATIONS] — the same
 * single source of truth `WhoopDatabase.android.kt` registers. Without it Room throws
 * "migration from N to M was required but not found" the moment an older file is opened
 * (BackupRestoreTest.restoredDatabaseOpensInRoomAndHoldsData caught this with a v17 fixture).
 *
 * Task 6 (device registry): GRDB's own MIGRATION_7_8-equivalent (v15) seeds one `pairedDevice` row
 * ("my-whoop", active) on every migrate call, including a fresh install; Room's fresh-install path
 * here built straight at the current schema with no such seed, so a fresh install had zero paired
 * devices once the registry started reading from Room. Fixed the same way as Android's #1037 fix
 * (`WhoopDatabase.android.kt`'s `onCreate` callback, same idempotent INSERT OR IGNORE, same literal
 * values): a `RoomDatabase.Callback.onCreate` fires exactly once, only for a database created fresh at
 * the current version (never for one opened from an existing file), using the KMP native-target
 * `SQLiteConnection`-based overload (Android's callback takes a `SupportSQLiteDatabase` instead; this
 * one is common to every Room target and is what the bundled-driver Apple builder needs).
 */
fun whoopDatabase(path: String): WhoopDatabase =
    Room.databaseBuilder<WhoopDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        // Same single source of truth as the Android builder: an existing file at an older
        // schema (restored backup, older-app store) upgrades in place; a fresh create at the
        // current version runs none of these.
        .addMigrations(*WhoopDatabase.ALL_MIGRATIONS)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(connection: SQLiteConnection) {
                val now = Clock.System.now().toEpochMilliseconds() / 1000
                connection.execSQL(
                    "INSERT OR IGNORE INTO `pairedDevice` " +
                        "(`id`, `brand`, `model`, `nickname`, `sourceKind`, `capabilities`, " +
                        "`status`, `addedAt`, `lastSeenAt`) VALUES " +
                        "('my-whoop', 'WHOOP', 'WHOOP', NULL, 'liveBLE', " +
                        "'hr,hrv,spo2,skinTemp,sleep,strainLoad', 'active', $now, $now)",
                )
            }
        })
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
