package com.noop.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture-driven proof of the GRDB-to-Room ETL (Phase 2c-1 Task 2). The legacy side is the
 * SYNTHETIC `grdb-mini.sqlite` built by `Tools/make-grdb-fixture.sh` (exact final GRDB schema
 * from Database.swift, deterministic formulaic rows, 3 devices / 2 days). Runs on the Apple
 * native targets only: [GrdbMigrator] lives in appleMain (it is the iOS/macOS cutover path)
 * and needs a real Room database on the bundled driver, which these targets open directly.
 */
class GrdbMigratorTest {

    // Must match Tools/make-grdb-fixture.sh exactly (the script prints these counts).
    private val expectedCounts = mapOf(
        "device" to 3L,
        "hrSample" to 44_401L,
        "rrInterval" to 7_212L,
        "event" to 24L,
        "battery" to 240L,
        "spo2Sample" to 720L,
        "skinTempSample" to 720L,
        "respSample" to 720L,
        "gravitySample" to 360L,
        "sleepSession" to 4L,
        "dailyMetric" to 6L,
        "journal" to 8L,
        "workout" to 5L,
        "appleDaily" to 4L,
        "metricSeries" to 30L,
        "stepSample" to 721L,
        "ppgHrSample" to 605L,
        "pairedDevice" to 3L,
        "dayOwnership" to 2L,
        "labMarker" to 6L,
        "sleepStateSample" to 720L,
        "liveSession" to 3L,
    )

    /** 2026-07-01T00:00:00Z, the fixture's fixed epoch base. */
    private val base = 1_782_864_000L
    private val day2 = base + 86_400L

    private fun tempDir(): String {
        val dir = NSTemporaryDirectory() + "noop-grdb-etl-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(dir.toPath())
        return dir
    }

    /** Copies the committed fixture into [dir] so a migrator bug can never dirty the repo file. */
    private fun copyFixtureTo(dir: String): String {
        val src = (fixturesDir() + "/grdb-mini.sqlite").toPath()
        val dst = "$dir/legacy.sqlite"
        FileSystem.SYSTEM.copy(src, dst.toPath())
        return dst
    }

    private fun rawCount(path: String, table: String): Long {
        val conn = BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY)
        try {
            val st = conn.prepare("SELECT COUNT(*) FROM `$table`")
            try {
                check(st.step())
                return st.getLong(0)
            } finally {
                st.close()
            }
        } finally {
            conn.close()
        }
    }

    private fun tableNames(path: String): Set<String> {
        val conn = BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY)
        try {
            val names = LinkedHashSet<String>()
            val st = conn.prepare("SELECT name FROM sqlite_master WHERE type = 'table'")
            try {
                while (st.step()) names.add(st.getText(0))
            } finally {
                st.close()
            }
            return names
        } finally {
            conn.close()
        }
    }

    @Test
    fun migrateCopiesEveryMappedTableWithTheThreeShapeAdjustments() = runTest {
        val dir = tempDir()
        val legacy = copyFixtureTo(dir)
        val room = "$dir/room.db"
        val legacyBytesBefore = FileSystem.SYSTEM.read(legacy.toPath()) { readByteString() }

        val progress = mutableListOf<GrdbMigrator.Progress>()
        val result = GrdbMigrator.migrate(legacy, room) { progress.add(it) }

        val done = assertIs<GrdbMigrator.Result.Done>(result, "migrate must succeed: $result")
        assertEquals(expectedCounts, done.rowCounts)

        // The legacy file is opened read-only and must be byte-identical afterwards.
        val legacyBytesAfter = FileSystem.SYSTEM.read(legacy.toPath()) { readByteString() }
        assertEquals(legacyBytesBefore, legacyBytesAfter, "migrate must never write to the legacy file")

        // Batching: hrSample (44401 rows) commits in five 10k transactions, progress per batch.
        val hrProgress = progress.filter { it.table == "hrSample" }
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 40_000L, 44_401L), hrProgress.map { it.copied })
        assertTrue(hrProgress.all { it.totalEstimate == 44_401L })
        // Every mapped table reports progress and finishes at its full count.
        assertEquals(expectedCounts.keys, progress.map { it.table }.toSet())
        expectedCounts.forEach { (table, count) ->
            assertEquals(count, progress.last { it.table == table }.copied, "final progress of $table")
        }

        // verify(): per-table counts equal on both sides, mapped tables only.
        val verdict = GrdbMigrator.verify(legacy, room)
        assertEquals(expectedCounts.keys, verdict.keys)
        verdict.forEach { (table, counts) ->
            assertEquals(counts.first, counts.second, "count mismatch in $table: $counts")
            assertEquals(expectedCounts.getValue(table), counts.first, "legacy count of $table")
        }

        // rawBatch + cursors stay GRDB-only: present in legacy, absent from the Room schema.
        assertTrue(tableNames(legacy).containsAll(listOf("rawBatch", "cursors", "grdb_migrations")))
        val roomTables = tableNames(room)
        assertTrue("rawBatch" !in roomTables && "cursors" !in roomTables && "grdb_migrations" !in roomTables)
        // The two Android-only tombstone tables exist but stay empty (no GRDB twin).
        assertEquals(0L, rawCount(room, "dismissedWorkout"))
        assertEquals(0L, rawCount(room, "dismissedSleep"))

        // Semantic spot-asserts through the real DAO (Room's generated adapters).
        val db = whoopDatabase(room)
        try {
            val dao = db.whoopDao()

            // 1. A planted hrSample row copies verbatim (bpm 143, synced 1).
            val hr = dao.rawHrSamples("whoop-beta", base + 1_000, base + 1_000, 10).single()
            assertEquals(143, hr.bpm)
            assertEquals(1, hr.synced)

            // 2. A sleepSession span survives with its JSON columns; the userEdited night keeps
            //    its adjusted onset.
            val night1 = dao.sleepSessions("my-whoop", base + 79_200, base + 79_200, 10).single()
            assertEquals(day2 + 21_600, night1.endTs)
            assertEquals(92.5, night1.efficiency)
            assertEquals("[0.12,0.05,0.3]", night1.motionJSON)
            assertEquals(false, night1.userEdited)
            val night2 = dao.sleepSessions("my-whoop", day2 + 79_200, day2 + 79_200, 10).single()
            assertEquals(true, night2.userEdited)
            assertEquals(day2 + 81_000, night2.startTsAdjusted)

            // 3. ppgHrSample: GRDB fractional REAL bpm rounds HALF-UP to the Room INTEGER,
            //    and the Room-only synced column lands 0.
            val ppg = dao.ppgHrSamples("my-whoop", base + 40_000, base + 40_004, 10)
            assertEquals(listOf(73, 68, 62, 63, 90), ppg.map { it.bpm })
            assertTrue(ppg.all { it.synced == 0 })
            assertEquals(0.9, ppg.first().conf)

            // 4. stepSample: the Room-only synced column lands 0; activityClass copies verbatim
            //    (planted row 2, and the fixture's NULL cases stay NULL).
            val step = dao.stepSamples("my-whoop", base + 20_000, base + 20_000, 10).single()
            assertEquals(4242, step.counter)
            assertEquals(2, step.activityClass)
            assertEquals(0, step.synced)
            val nullClass = dao.stepSamples("my-whoop", base + 28_800, base + 28_800, 10).single()
            assertNull(nullClass.activityClass, "fixture row i=0 has activityClass NULL")

            // 5. workout: GRDB has no routePolyline column; the Room row lands it NULL with
            //    every metric column copied verbatim.
            val run = dao.workouts("my-whoop", base + 61_200, base + 61_200, 10).single()
            assertEquals("running", run.sport)
            assertEquals(650.5, run.energyKcal)
            assertNull(run.routePolyline)
        } finally {
            db.close()
        }
    }

    @Test
    fun interruptedMigrationIsCleanAfterRerun() = runTest {
        val dir = tempDir()
        val legacy = copyFixtureTo(dir)
        val room = "$dir/room.db"

        // Simulated crash: the progress hook throws once, mid-hrSample (after two 10k batches).
        var thrown = false
        val first = GrdbMigrator.migrate(legacy, room) { p ->
            if (!thrown && p.table == "hrSample" && p.copied >= 20_000) {
                thrown = true
                throw IllegalStateException("simulated crash")
            }
        }
        val failed = assertIs<GrdbMigrator.Result.Failed>(first)
        assertEquals("hrSample", failed.table)
        assertTrue("simulated crash" in failed.message, failed.message)

        // The interrupted run left a partial hrSample behind (batches commit incrementally).
        val partial = rawCount(room, "hrSample")
        assertTrue(partial in 1 until 44_401L, "expected a partial hrSample copy, got $partial")

        // Re-running truncates first and completes cleanly (resume-by-restart idempotency).
        val second = GrdbMigrator.migrate(legacy, room)
        val done = assertIs<GrdbMigrator.Result.Done>(second, "re-run must succeed: $second")
        assertEquals(expectedCounts, done.rowCounts)
        GrdbMigrator.verify(legacy, room).forEach { (table, counts) ->
            assertEquals(counts.first, counts.second, "count mismatch in $table after re-run")
        }
    }

    @Test
    fun nonIntegerRealInAnIntegerColumnFailsLoudly() = runTest {
        val dir = tempDir()
        val legacy = copyFixtureTo(dir)
        val room = "$dir/room.db"

        // Corrupt OUR TEMP COPY (never the committed fixture): plant a non-integral REAL in an
        // INTEGER column. SQLite's flexible typing stores 65.5 as REAL despite the column type.
        val conn = BundledSQLiteDriver().open(legacy, SQLITE_OPEN_READWRITE)
        try {
            val st = conn.prepare("UPDATE hrSample SET bpm = 65.5 WHERE deviceId = 'whoop-beta' AND ts = ${base + 1_000}")
            try {
                st.step()
            } finally {
                st.close()
            }
        } finally {
            conn.close()
        }

        val result = GrdbMigrator.migrate(legacy, room)
        val failed = assertIs<GrdbMigrator.Result.Failed>(result, "a non-integer REAL must fail loudly, got $result")
        assertEquals("hrSample", failed.table)
        assertTrue("bpm" in failed.message && "65.5" in failed.message, failed.message)
    }
}
