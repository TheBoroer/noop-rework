package com.noop.data

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 2c-1 Task 7 — THE cross-platform payoff: an Apple-exported backup is Android-restorable.
 *
 * Before this phase the Apple app stored in GRDB and its `.noopbak` carried `grdb_migrations`, which
 * the shared [BackupRestore] engine (the exact code the Android app imports through) classifies as
 * `MAC` and REJECTS — the "This looks like a backup from the Mac or iOS NOOP app …" message. Now the
 * Apple app stores in Room and exports the Room `noop.db`, so its backup carries `room_master_table`
 * and is one of *ours*.
 *
 * This test builds an export-shaped Apple backup the honest way — it runs the real Apple cutover
 * ([GrdbMigrator]) over the SYNTHETIC `grdb-mini.sqlite` fixture to produce a Room `noop.db`, then
 * checkpoints it exactly as the export does ([WhoopDatabase.checkpointWal]) — and proves two things
 * about that single file:
 *  1. Android's origin check ([BackupRestore.backupOriginOf], the same one `DataBackup.backupOriginOf`
 *     delegates to) accepts it as native (`ANDROID`), NOT the rejected `MAC`.
 *  2. It restores end to end through the shared [BackupRestore] engine and the rows read back.
 *
 * Together: the "Mac backups are rejected" path on Android is now reachable ONLY for pre-2c-1 legacy
 * GRDB exports. Apple-native (appleTest) because [GrdbMigrator] and the bundled-driver Room open are
 * Apple targets; gated on [canRunFullRestore] like the sibling [BackupRestoreTest] end-to-end proof.
 */
class AppleRoomBackupCrossPlatformTest {

    private fun tempDir(): String {
        val dir = NSTemporaryDirectory() + "noop-xplat-backup-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(dir.toPath())
        return dir
    }

    private fun copyFixtureTo(dir: String): String {
        val src = (fixturesDir() + "/grdb-mini.sqlite").toPath()
        val dst = "$dir/legacy.sqlite"
        FileSystem.SYSTEM.copy(src, dst.toPath())
        return dst
    }

    @Test
    fun appleRoomExportPassesAndroidOriginCheckAndRestoresThroughSharedEngine() = runTest {
        if (!canRunFullRestore) return@runTest

        // 1. Build the Apple export the real way: ETL the legacy GRDB fixture into a Room noop.db.
        val dir = tempDir()
        val legacy = copyFixtureTo(dir)
        val roomExport = "$dir/noop.db"
        val migrated = GrdbMigrator.migrate(legacy, roomExport)
        assertIs<GrdbMigrator.Result.Done>(migrated, "the Apple cutover ETL must succeed: $migrated")

        // The export checkpoints the live Room WAL into the single file before archiving (Task 7), so
        // the exported noop.db is whole with no -wal/-shm sidecar carrying uncommitted commits. This
        // also exercises the new WhoopDatabase.checkpointWal the Swift export routes through.
        val exportDb = openWhoopDatabaseAt(roomExport)
        try {
            exportDb.checkpointWal()
        } finally {
            exportDb.close()
        }

        // 2. Android's origin check accepts the Apple export as one of ours (ANDROID/native), not the
        //    rejected MAC. This is the byte-for-byte classifier DataBackup.backupOriginOf delegates to.
        val exportTables = sqliteTableNames(roomExport)
        assertTrue(
            exportTables.contains("room_master_table"),
            "the Room export carries Room's bookkeeping table, got $exportTables",
        )
        assertEquals(
            BackupRestore.BackupOrigin.ANDROID,
            BackupRestore.backupOriginOf(exportTables),
            "an Apple Room export must classify as native (ANDROID), never the rejected MAC",
        )

        // 3. It restores end to end through the SHARED engine (the exact path Android imports through)
        //    into a fresh live path, and the migrated rows survive the round trip.
        val livePath = "$dir/restored.db"
        val restore = BackupRestore.restore(
            FileSystem.SYSTEM,
            roomExport.toPath(),
            livePath.toPath(),
        )
        assertEquals(
            BackupRestore.RestoreResult.NeedsReopen,
            restore,
            "the shared engine must accept the Apple Room export",
        )

        val db = openWhoopDatabaseAt(livePath)
        try {
            val hr = db.whoopDao().countHr()
            assertTrue(hr > 0, "restored Apple export should hold HR rows, got $hr")
        } finally {
            db.close()
        }
    }
}
