package com.noop.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end proof of the real backup import (#57 / rrInterval legacy-shape crash):
 * imports the REAL user backup (pushed by the runner to [DEVICE_BACKUP]) through the exact
 * production path — [DataBackup.importFrom] → BackupRestore stage/validate/reconcile/swap —
 * then re-opens [WhoopDatabase] fresh, which is precisely what the post-import app restart
 * does and precisely where the "Pre-packaged database has an invalid schema: rrInterval"
 * crash fired. No UI, no SAF picker: the Uri handed to importFrom is a plain file Uri over
 * an app-cache copy, everything downstream is the untouched production code.
 *
 * The backup lives in /data/local/tmp, which untrusted apps cannot read under SELinux, so the
 * test streams it into app cache via UiAutomation's shell (which can).
 */
@RunWith(AndroidJUnit4::class)
class BackupImportDeviceTest {

    @Test
    fun realBackupImportsAndReopensAfterRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext

        // 1. Shell-copy the pushed backup into app cache (SELinux: app can't read shell files).
        val local = File(appContext.cacheDir, "device-test-backup.noopbak.zip")
        instrumentation.uiAutomation.executeShellCommand("cat $DEVICE_BACKUP").use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                FileOutputStream(local).use { output -> input.copyTo(output) }
            }
        }
        assertTrue("backup not pushed to $DEVICE_BACKUP (or empty)", local.length() > 1_000_000)

        // 2. Real import, production path end to end.
        val result = DataBackup.importFrom(appContext, Uri.fromFile(local))
        assertEquals(DataBackup.ImportResult.NeedsRestart, result)

        // 3. "Restart": fresh Room open of the swapped-in DB — the historical crash point.
        //    importFrom closed the singleton before swapping, so get() builds from scratch and
        //    Room validates every entity's identity hash (rrInterval included) on first access.
        val db = WhoopDatabase.get(appContext)
        val sdb = db.openHelper.readableDatabase

        sdb.query("PRAGMA quick_check(1)").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("ok", c.getString(0))
        }
        val rrRows = sdb.query("SELECT count(*) FROM `rrInterval`").use { c ->
            c.moveToFirst(); c.getLong(0)
        }
        assertTrue("restored DB has no rrInterval rows", rrRows > 0)
        sdb.query(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN " +
                "('outboxBatch','outboxCursor')"
        ).use { c ->
            c.moveToFirst()
            assertEquals("fork outbox tables missing after reconcile", 2, c.getInt(0))
        }

        // 4. Re-import over the already-restored (fork-shaped) live DB: a user importing the
        //    same archive twice must also succeed, not trip the legacy reconcile.
        WhoopDatabase.close()
        val second = DataBackup.importFrom(appContext, Uri.fromFile(local))
        assertEquals(DataBackup.ImportResult.NeedsRestart, second)
        val db2 = WhoopDatabase.get(appContext)
        db2.openHelper.readableDatabase.query("SELECT count(*) FROM `rrInterval`").use { c ->
            c.moveToFirst()
            assertEquals(rrRows, c.getLong(0))
        }
        local.delete()
    }

    private companion object {
        const val DEVICE_BACKUP = "/data/local/tmp/noop-backup-device-test.noopbak.zip"
    }
}
