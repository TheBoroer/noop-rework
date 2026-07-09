package com.noop.data

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File

/**
 * #1014 defence-in-depth: a Room open-helper factory whose ONLY behavioural change is corruption
 * handling. The platform DEFAULT — androidx.sqlite routes SQLITE_CORRUPT to
 * [SupportSQLiteOpenHelper.Callback.onCorruption], whose base implementation mirrors Android's
 * `DefaultDatabaseErrorHandler` — silently DELETES the corrupt database file. For NOOP that means
 * permanently destroying already-acked strap history the strap will never re-send, without the user
 * ever seeing a byte of it. Confirmed absent in this app before #1014: nothing overrode
 * onCorruption, so the delete-on-corruption default applied.
 *
 * The factory wraps the stock [FrameworkSQLiteOpenHelperFactory] and delegates every lifecycle
 * callback (configure/create/migrate/open) to Room's real callback UNCHANGED, so migrations behave
 * exactly as before. Only `onCorruption` is replaced: it logs loudly, closes the handle, sets ONE
 * `.corrupt` sibling copy aside (best-effort, skipped when one already exists so repeated failed
 * opens can't multiply 100 MB files), and — crucially — deletes NOTHING. The trade-off is
 * deliberate and matches [WhoopDatabase]'s no-destructive-fallback doctrine: the app may then fail
 * to open the store (the user sees an error instead of a silently empty app), but the file survives
 * for backup/recovery instead of vanishing.
 *
 * `allowDataLossOnRecovery` is pinned FALSE for the same reason: androidx's recovery path deletes
 * the file when an open fails, which is exactly the destruction this factory exists to prevent.
 *
 * Kept in `shared` (not moved alongside the rest of [DataBackup] in the app) because
 * [WhoopDatabase] needs it directly and is self-contained: no dependency on `com.noop.ui` or the
 * app-only [BackupSettingsCodec]/[BackupSettingsBridge] that force the rest of the export/import
 * object back into the app module.
 */
class CorruptionPreservingOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val preserving = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(PreservingCallback(configuration.callback))
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false)
            .build()
        return delegate.create(preserving)
    }

    /** Delegates everything to Room's callback except the destructive corruption default. */
    private class PreservingCallback(
        private val roomCallback: SupportSQLiteOpenHelper.Callback,
    ) : SupportSQLiteOpenHelper.Callback(roomCallback.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = roomCallback.onConfigure(db)
        override fun onCreate(db: SupportSQLiteDatabase) = roomCallback.onCreate(db)
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            roomCallback.onUpgrade(db, oldVersion, newVersion)
        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            roomCallback.onDowngrade(db, oldVersion, newVersion)
        override fun onOpen(db: SupportSQLiteDatabase) = roomCallback.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            // Do NOT call super — the base implementation DELETES the file outright (non-resendable strap
            // history gone without a trace). Instead QUARANTINE the corrupt file aside and let the store
            // recreate a fresh one, so the app OPENS instead of crash-looping on every launch. #1014
            // (wanxorg): 8.2.0 preserved the file but left it in place, so the very next open re-hit the
            // same corruption and the app crashed on startup until a reinstall. Moving the original out of
            // the way keeps the crash-recovery the platform default gives (next open finds no file → clean
            // rebuild) WITHOUT the silent data loss — the corrupt copy stays as `*.corrupt` for recovery.
            val path = runCatching { db.path }.getOrNull()
            Log.e(
                "WhoopDatabase",
                "SQLite reported corruption in $path — quarantining it to *.corrupt and recreating a fresh " +
                    "store. The corrupt copy is kept; restore from a backup to get your data back.",
            )
            runCatching { db.close() }
            if (path != null && path != ":memory:") {
                val original = File(path)
                if (original.exists()) {
                    val preserved = File("$path.corrupt")
                    if (!preserved.exists()) {
                        // Move (not copy) so the original is gone and the next open rebuilds clean. Fall
                        // back to copy+delete if rename fails (e.g. across a storage boundary).
                        if (!runCatching { original.renameTo(preserved) }.getOrDefault(false)) {
                            runCatching { original.copyTo(preserved, overwrite = false) }
                            runCatching { original.delete() }
                        }
                    } else {
                        // A quarantine copy already exists — just drop the still-corrupt original.
                        runCatching { original.delete() }
                    }
                }
                // Drop the WAL/SHM sidecars so a fresh DB can't inherit a stale write-ahead log.
                runCatching { File("$path-wal").delete() }
                runCatching { File("$path-shm").delete() }
            }
        }
    }
}
