package com.noop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/**
 * The Phase 2a payoff tests: a SYNTHETIC Android-produced schema-v17 `.noopbak` backup (a fresh
 * `:app:installDemoDebug` + DemoSeeder run, never real user data, trimmed to the most recent 21
 * days by `Tools/make-backup-fixture.sh`, committed under `shared/src/commonTest/fixtures/`)
 * restores through the shared [BackupRestore] engine into shared Room, and reads back rows.
 *
 * The stage/failure tests run on BOTH targets (plain-JVM androidUnitTest + iosSimulatorArm64).
 * The full restore (quick_check + Room open) is gated on [canRunFullRestore]: the JVM target has
 * neither a working android.database.sqlite nor a Context for Room's Android builder (no
 * Robolectric in this module, by design; see WhoopDatabaseSmokeTest), so the iOS simulator target
 * carries the end-to-end cross-platform proof.
 */
class BackupRestoreTest {

    private val fixture get() = (fixturesDir() + "/noopbak-demo-v17-mini.zip").toPath()

    @Test
    fun stagesRealBackupFixture() {
        val fs = platformFileSystem()
        val work = tempWorkDir().toPath()
        val result = BackupRestore.stage(fs, fixture, work)
        assertEquals(BackupRestore.StageResult.OK, result)

        // The staged DB is a real SQLite file (magic header), and the fixture's settings.json
        // entry (#1000) was staged alongside it.
        val stagedDb = work / "import-extract.sqlite"
        assertTrue(fs.exists(stagedDb))
        val magic = fs.source(stagedDb).buffer().use { it.readByteArray(16) }
        assertEquals("SQLite format 3", magic.decodeToString(0, 15))
        assertEquals(0, magic[15].toInt())
        val settings = work / "import-settings.json"
        assertTrue(fs.exists(settings), "fixture carries a settings.json entry")
        assertTrue(fs.read(settings) { readUtf8() }.contains("profile."))
    }

    @Test
    fun stageRejectsGarbageFile() {
        val fs = platformFileSystem()
        val work = tempWorkDir().toPath()
        val junk = work / "junk.bin"
        fs.write(junk) { writeUtf8("definitely not a backup of anything") }
        assertEquals(BackupRestore.StageResult.NOT_A_BACKUP, BackupRestore.stage(fs, junk, work))
    }

    @Test
    fun stageReportsUnopenableArchive() {
        val fs = platformFileSystem()
        val work = tempWorkDir().toPath()
        assertEquals(
            BackupRestore.StageResult.CANNOT_OPEN,
            BackupRestore.stage(fs, work / "does-not-exist.noopbak", work),
        )
    }

    @Test
    fun restoreOfMissingArchiveFailsWithoutTouchingLivePath() {
        val fs = platformFileSystem()
        val work = tempWorkDir().toPath()
        val livePath = work / "restored.sqlite"
        val result = BackupRestore.restore(fs, work / "does-not-exist.noopbak", livePath)
        assertEquals(BackupRestore.RestoreResult.Failed("Could not open the chosen file."), result)
        assertFalse(fs.exists(livePath))
    }

    @Test
    fun restoreOfGarbageFailsBeforeAnyValidationGate() {
        val fs = platformFileSystem()
        val work = tempWorkDir().toPath()
        val junk = work / "junk.noopbak"
        fs.write(junk) { writeUtf8("PKxx not actually a zip and not sqlite either") }
        val livePath = work / "restored.sqlite"
        val result = BackupRestore.restore(fs, junk, livePath)
        assertEquals(
            BackupRestore.RestoreResult.Failed(
                "That file is not a NOOP backup - it doesn't look like a .noopbak archive or a SQLite database."
            ),
            result,
        )
        assertFalse(fs.exists(livePath))
    }

    @Test
    fun sqliteUserVersionReadsHeaderField() {
        val fs = platformFileSystem()
        val work = tempWorkDir().toPath()
        // A synthetic header: SQLite stores `user_version` as a big-endian u32 at byte offset 60.
        val file = work / "header-only.sqlite"
        fs.write(file) {
            write(ByteArray(60)) // offsets 0..59, content irrelevant to the reader
            writeInt(20)
        }
        assertEquals(20, BackupRestore.sqliteUserVersion(fs, file))
        // Short file (no complete header) -> no verdict, never a throw.
        val short = work / "short.sqlite"
        fs.write(short) { write(ByteArray(32)) }
        assertEquals(null, BackupRestore.sqliteUserVersion(fs, short))
    }

    /**
     * Regression: a healthy backup written by a NEWER schema passed every gate (magic, origin,
     * quick_check), swapped in, and then crash-looped the app at every launch — Room throws from
     * onDowngrade at open time, AFTER the restore already reported success, and the rollback
     * snapshot is gone by then. The version gate (step 3d) must refuse the swap while the live
     * DB is still untouched.
     */
    @Test
    fun restoreRefusesNewerSchemaBackupBeforeTouchingLiveDb() = runTest {
        if (!canRunFullRestore) return@runTest // JVM target: see class KDoc; proven on iosSimulatorArm64
        val fs = platformFileSystem()
        val work = tempWorkDir().toPath()

        // Build the fixture: the real v17 backup's SQLite with its `user_version` header field
        // patched one above the app's schema. Still a byte-for-byte healthy this-app database,
        // so every gate before 3d accepts it.
        val newerVersion = WhoopDatabase.SCHEMA_VERSION + 1
        val patched = work / "newer-schema.sqlite"
        val zipFs = fs.openZip(fixture)
        val bytes = zipFs.source(BackupRestore.ZIP_ENTRY_NAME.toPath()).buffer().use { it.readByteArray() }
        bytes[60] = (newerVersion ushr 24).toByte()
        bytes[61] = (newerVersion ushr 16).toByte()
        bytes[62] = (newerVersion ushr 8).toByte()
        bytes[63] = newerVersion.toByte()
        fs.write(patched) { write(bytes) }
        assertEquals(newerVersion, BackupRestore.sqliteUserVersion(fs, patched))

        // A live DB with sentinel content that the refused restore must not touch.
        val livePath = work / "restored.sqlite"
        fs.write(livePath) { writeUtf8("sentinel live db") }

        var appliedSettings: String? = null
        val result = BackupRestore.restore(fs, patched, livePath) { json -> appliedSettings = json }
        assertEquals(
            BackupRestore.RestoreResult.Failed(
                "This backup was created by a newer version of the app (database schema " +
                    "$newerVersion; this version understands up to ${WhoopDatabase.SCHEMA_VERSION}). " +
                    "Update the app, then import it again. Your current data is untouched."
            ),
            result,
        )
        // Live file untouched, settings hook never fired, staging fully cleaned up.
        assertEquals("sentinel live db", fs.read(livePath) { readUtf8() })
        assertEquals(null, appliedSettings)
        assertFalse(fs.exists(work / "import-extract.sqlite"))
        assertFalse(fs.exists(work / "import-extract.sqlite-wal"))
        assertFalse(fs.exists(work / "import-extract.sqlite-shm"))
        assertFalse(fs.exists(work / "restored.sqlite.import-bak"))
    }

    /** THE milestone: Android schema-v17 backup -> shared engine -> shared Room -> rows read back. */
    @Test
    fun restoredDatabaseOpensInRoomAndHoldsData() = runTest {
        if (!canRunFullRestore) return@runTest // JVM target: see class KDoc; proven on iosSimulatorArm64
        val fs = platformFileSystem()
        val work = tempWorkDir().toPath()
        val livePath = work / "restored.sqlite"

        var appliedSettings: String? = null
        val result = BackupRestore.restore(fs, fixture, livePath) { json -> appliedSettings = json }
        assertEquals(BackupRestore.RestoreResult.NeedsReopen, result)

        // The settings hook fired with the backup's whitelisted payload, and the engine cleaned
        // up after itself: no staged files, no rollback snapshot left behind.
        assertNotNull(appliedSettings, "settings.json should reach the apply hook")
        assertTrue(appliedSettings!!.contains("profile."))
        assertFalse(fs.exists(work / "import-extract.sqlite"))
        assertFalse(fs.exists(work / "import-settings.json"))
        assertFalse(fs.exists(work / "restored.sqlite.import-bak"))

        // Shared Room (bundled SQLite driver) opens the restored file directly: the fixture is
        // schema v17 with the same Room identity hash as the shared WhoopDatabase, and synthetic
        // demo rows (a fresh DemoSeeder run, never real user data) read back through the real DAO.
        val db = openWhoopDatabaseAt(livePath.toString())
        try {
            val hr = db.whoopDao().countHr()
            val rr = db.whoopDao().countRr()
            assertTrue(hr > 0, "restored db should hold HR rows, got $hr")
            assertTrue(rr > 0, "restored db should hold RR rows, got $rr")
        } finally {
            db.close()
        }
    }

    @Test
    fun kmpSettingsCodecDecodesWhitelistedSubset() {
        val decoded = BackupSettingsCodecKmp.decode(
            """{"profile.age":35,"profile.weightKg":83.5,"units.system":"imperial",""" +
                """"profile.sex":7,"unknown.key":"x","profile.hrMax":true}"""
        )
        assertEquals(35, decoded["profile.age"]) // int stays int
        assertEquals(83.5, decoded["profile.weightKg"]) // double stays double
        assertEquals("imperial", decoded["units.system"]) // string stays string
        assertFalse(decoded.containsKey("profile.sex"), "wrong-typed value is dropped, never guessed")
        assertFalse(decoded.containsKey("unknown.key"), "unknown keys are dropped")
        assertFalse(decoded.containsKey("profile.hrMax"), "booleans never coerce to numbers")
    }

    @Test
    fun kmpSettingsCodecDegradesMalformedJsonToEmpty() {
        assertEquals(emptyMap(), BackupSettingsCodecKmp.decode("not json at all"))
        assertEquals(emptyMap(), BackupSettingsCodecKmp.decode("[1,2,3]"))
    }

    @Test
    fun originClassificationMirrorsAndroid() {
        assertEquals(
            BackupRestore.BackupOrigin.ANDROID,
            BackupRestore.backupOriginOf(setOf("room_master_table", "hrSample")),
        )
        assertEquals(
            BackupRestore.BackupOrigin.MAC,
            BackupRestore.backupOriginOf(setOf("grdb_migrations", "hrSample")),
        )
        assertEquals(
            BackupRestore.BackupOrigin.ANDROID,
            BackupRestore.backupOriginOf(setOf("android_metadata", "sqlite_sequence")),
        )
        assertEquals(BackupRestore.BackupOrigin.UNKNOWN, BackupRestore.backupOriginOf(setOf("things")))
        assertTrue(BackupRestore.holdsData(setOf("android_metadata", "hrSample")))
        assertFalse(BackupRestore.holdsData(setOf("android_metadata", "sqlite_sequence")))
    }

    @Test
    fun quickCheckVerdictMirrorsAndroidGoldenVectors() {
        assertEquals(null, BackupRestore.quickCheckVerdict(listOf("ok")))
        assertEquals(null, BackupRestore.quickCheckVerdict(listOf("OK")))
        assertEquals(
            "quick_check returned no verdict",
            BackupRestore.quickCheckVerdict(emptyList()),
        )
        assertEquals(
            "Page 9 is never used",
            BackupRestore.quickCheckVerdict(listOf("ok", "Page 9 is never used")),
        )
    }
}
