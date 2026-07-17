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
 * DemoSeeder run into a debug build, never real user data, trimmed to the most recent 21
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

    /**
     * The legacy-peer reconcile (restore step 3e) replays [WhoopDatabase.OUTBOX_MIGRATION_SQL]
     * on EVERY v18+ backup that lacks the outbox tables — and, at v20, on files this fork wrote
     * too if the table check ever mis-fires. Every statement must therefore be idempotent
     * (`IF NOT EXISTS`), or a replay against a this-fork backup would fail the whole restore.
     * Runs on both targets; no SQLite needed.
     */
    @Test
    fun outboxReconcileSqlIsIdempotent() {
        assertTrue(WhoopDatabase.OUTBOX_MIGRATION_SQL.isNotEmpty())
        for (stmt in WhoopDatabase.OUTBOX_MIGRATION_SQL) {
            assertTrue(stmt.contains("IF NOT EXISTS"), "reconcile replays this on healthy backups; must be idempotent: $stmt")
        }
    }

    /**
     * Regression for the legacy-backup identity-hash mismatch: the original NOOP app's v20 schema
     * shares the version NUMBER with this fork but lacks `outboxBatch`/`outboxCursor` and carries
     * a different `room_master_table` identity hash. Before step 3e, such a backup passed every
     * restore gate, swapped in, and then Room threw on the hash mismatch at every launch — a
     * crash-loop AFTER the restore already reported success, with the rollback snapshot gone.
     *
     * The legacy app ALSO kept a `seq` column on `rrInterval` (PK `deviceId, ts, rrMs, seq`)
     * that this fork dropped, so even with the outbox tables recreated Room's deep validation
     * rejected the table shape — the second half of the same crash-loop.
     *
     * Simulated here from the fixture: restore + Room-open yields a real this-fork v20 file; the
     * surgery (drop outbox tables, reshape `rrInterval` to the legacy form with duplicate beats
     * that differ only in `seq`, fake a foreign hash) makes it byte-for-byte what the legacy app
     * exports. The reconciled restore must succeed, Room must open the result (deep validation
     * passing proves `rrInterval` was rebuilt into this fork's shape), and the collapsed
     * duplicate must keep `MIN(synced)` so an unsynced beat is re-uploaded, not lost.
     */
    @Test
    fun legacyPeerV20BackupReconcilesAndOpensInRoom() = runTest {
        if (!canRunFullRestore) return@runTest // JVM target: see class KDoc; proven on iosSimulatorArm64
        val fs = platformFileSystem()
        val work = tempWorkDir().toPath()

        // Build a real this-fork v20 database: restore the v17 fixture, then let Room migrate
        // and stamp its identity hash.
        val forkDb = work / "fork-v20.sqlite"
        assertEquals(BackupRestore.RestoreResult.NeedsReopen, BackupRestore.restore(fs, fixture, forkDb))
        val forkRoom = openWhoopDatabaseAt(forkDb.toString())
        try {
            assertTrue(forkRoom.whoopDao().countHr() > 0)
        } finally {
            forkRoom.close()
        }
        assertTrue("outboxBatch" in sqliteTableNames(forkDb.toString()), "fork v20 file carries outbox tables")

        // Surgery: turn it into what the legacy app exports — same user_version (v20), no outbox
        // tables, the legacy `rrInterval` shape (`seq` column, 4-column PK, plus two beats that
        // differ ONLY in `seq`: one synced, one not), a room_master_table whose hash is NOT this
        // fork's.
        assertEquals(
            null,
            sqliteExec(
                forkDb.toString(),
                listOf(
                    "DROP TABLE outboxBatch",
                    "DROP TABLE outboxCursor",
                    "ALTER TABLE `rrInterval` RENAME TO `rrInterval_fork`",
                    "CREATE TABLE `rrInterval` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                        "`rrMs` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `synced` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`deviceId`, `ts`, `rrMs`, `seq`))",
                    "INSERT INTO `rrInterval` SELECT `deviceId`, `ts`, `rrMs`, 0, `synced` FROM `rrInterval_fork`",
                    "DROP TABLE `rrInterval_fork`",
                    "INSERT INTO `rrInterval` VALUES ('legacy-dupe-device', 1000, 800, 0, 1)",
                    "INSERT INTO `rrInterval` VALUES ('legacy-dupe-device', 1000, 800, 1, 0)",
                    "UPDATE room_master_table SET identity_hash = 'legacy-hash-differs-from-fork'",
                ),
            ),
        )

        // Restore the simulated legacy backup. Step 3e must recreate the outbox tables and drop
        // room_master_table on the STAGED copy, so Room deep-validates and re-stamps at open.
        val livePath = work / "restored-from-legacy.sqlite"
        assertEquals(BackupRestore.RestoreResult.NeedsReopen, BackupRestore.restore(fs, forkDb, livePath))
        val restoredTables = sqliteTableNames(livePath.toString())
        assertTrue("outboxBatch" in restoredTables, "reconcile recreates outboxBatch")
        assertTrue("outboxCursor" in restoredTables, "reconcile recreates outboxCursor")
        assertFalse("room_master_table" in restoredTables, "stale legacy hash must not survive the swap")

        // The payoff: Room opens the reconciled file (no identity-hash throw, no rrInterval
        // shape throw, no crash-loop) and the data survived the round trip. Deep validation
        // passing IS the shape assertion for rrInterval.
        val db = openWhoopDatabaseAt(livePath.toString())
        try {
            assertTrue(db.whoopDao().countHr() > 0, "legacy-shaped restore should keep its rows")
            assertTrue(db.whoopDao().countRr() > 0, "rrInterval rows must survive the rebuild")
            val dupes = db.whoopDao().rrIntervals("legacy-dupe-device", 0, Long.MAX_VALUE, 10)
            assertEquals(1, dupes.size, "beats differing only in legacy `seq` collapse to one row")
            assertEquals(0, dupes.single().synced, "MIN(synced) wins: unsynced duplicate is re-uploaded, not lost")
        } finally {
            db.close()
        }
    }

    /**
     * The 3e reconcile replays [BackupRestore.RR_INTERVAL_RECONCILE_SQL] on EVERY legacy-peer
     * backup, including hypothetical legacy shapes that predate the `seq` column. The rebuild is
     * only safe there because no statement ever references `seq`; this pins that guarantee the
     * same way [outboxReconcileSqlIsIdempotent] pins `IF NOT EXISTS`. Runs on both targets; no
     * SQLite needed.
     */
    @Test
    fun rrIntervalReconcileSqlNeverReferencesSeq() {
        assertTrue(BackupRestore.RR_INTERVAL_RECONCILE_SQL.isNotEmpty())
        for (stmt in BackupRestore.RR_INTERVAL_RECONCILE_SQL) {
            assertFalse(stmt.contains("seq"), "rebuild must be shape-agnostic (no `seq` reference): $stmt")
        }
    }
}
