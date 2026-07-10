package com.noop.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Android open path for [WhoopDatabase] (Phase 2a KMP split). The @Database class, the migrations and
 * the schema-pinning SQL constants live in commonMain; this file keeps the Android factory UNCHANGED:
 * the same [WhoopDatabase.DB_NAME] file, the same process-wide singleton with double-checked locking,
 * the same [CorruptionPreservingOpenHelperFactory] wiring (Android stays on the support/framework
 * driver so open-path behaviour is byte-identical to pre-split), the same additive-only migration set
 * with NO destructive fallback, and the same fresh-install seed callback.
 *
 * `WhoopDatabase.get(context)` / `WhoopDatabase.close()` (companion members in commonMain) delegate to
 * [openWhoopDatabase] / [closeWhoopDatabase] here, so every existing call site is unchanged.
 */
actual typealias PlatformContext = Context

@Volatile
private var instance: WhoopDatabase? = null

/**
 * Process-wide singleton. Safe to call from any thread. Locks on the [WhoopDatabase] companion object,
 * exactly the monitor the pre-split `synchronized(this)` used.
 */
internal actual fun openWhoopDatabase(context: PlatformContext): WhoopDatabase =
    instance ?: synchronized(WhoopDatabase) {
        instance ?: buildWhoopDatabase(context.applicationContext).also { instance = it }
    }

internal actual fun closeWhoopDatabase() {
    synchronized(WhoopDatabase) {
        instance?.close()
        instance = null
    }
}

private fun buildWhoopDatabase(appContext: Context): WhoopDatabase =
    Room.databaseBuilder(appContext, WhoopDatabase::class.java, WhoopDatabase.DB_NAME)
        // #1014: replace ONLY the corruption handling of the default open-helper. The
        // platform default silently DELETES a corrupt database file (non-resendable strap
        // history gone without a trace); this factory logs + preserves the file instead.
        // Every migration/lifecycle callback is delegated to Room unchanged.
        .openHelperFactory(CorruptionPreservingOpenHelperFactory())
        // Real additive migration, NO destructive fallback (see the class doc): with
        // exportSchema=false a silent rebuild would lose already-acked, non-resendable strap
        // history on any schema mismatch. Room throws loudly instead; CI guards the SQL.
        .addMigrations(*WhoopDatabase.ALL_MIGRATIONS)
        // #1037: a FRESH install builds the schema straight at the current version and runs NO
        // migrations, so the MIGRATION_7_8 "my-whoop" registry seed never fires and the WHOOP,
        // though paired and streaming fine, never appears in the Devices list. Seed the canonical
        // row on create too (same idempotent INSERT OR IGNORE as the migration) so a first-ever
        // install still lists its WHOOP. iOS/GRDB re-runs migrations on a fresh DB, so it never hit this.
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis() / 1000
                db.execSQL(
                    "INSERT OR IGNORE INTO `pairedDevice` " +
                        "(`id`, `brand`, `model`, `nickname`, `sourceKind`, `capabilities`, " +
                        "`status`, `addedAt`, `lastSeenAt`) VALUES " +
                        "('my-whoop', 'WHOOP', 'WHOOP', NULL, 'liveBLE', " +
                        "'hr,hrv,spo2,skinTemp,sleep,strainLoad', 'active', $now, $now)",
                )
            }
        })
        .build()
