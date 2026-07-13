package com.noop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/**
 * Phase 2c-1 Task 5 (metrics/sleep/journal/workout delegation): the DAO query shapes ADDED so the
 * iOS `WhoopStore` metric/sleep/journal/workout branches can delegate to shared Room byte-for-byte
 * with the GRDB originals:
 *   - [WhoopDao.upsertSleepSessionPreservingEdit]: the CASE-WHEN sleep-edit-preserving upsert;
 *   - [WhoopDao.applySleepEdit]: the durable bed/wake-time hand-correction UPDATE;
 *   - [WhoopDao.sessionMotionsForStarts] / [WhoopDao.sessionSleepStatesInRange]: batched per-epoch
 *     analytics readers;
 *   - [WhoopDao.metricDayRange]: the `(earliest, latest)` day-range reader, null-unless-both-present;
 *   - [WhoopDao.upsertWorkoutPreservingRoute]: the routePolyline-preserving workout upsert;
 *   - [WhoopDao.upsertLabMarkersPreservingId]: the id-preserving Lab Book marker upsert.
 *
 * These need a real Room instance, so (exactly like [WhoopDaoStreamQueryTest]) the body is gated on
 * [canRunFullRestore]: the plain-JVM androidUnitTest target has no Context for Room's Android builder
 * (no Robolectric in this module, by design), so the iosSimulatorArm64 / macosArm64 targets carry the
 * end-to-end proof. On JVM the tests return early (still counted, always green).
 */
class WhoopDaoMetricsSleepQueryTest {

    private fun freshDb(name: String): WhoopDatabase =
        openWhoopDatabaseAt((tempWorkDir().toPath() / name).toString())

    /** A fresh session upsert behaves like a plain latest-wins insert (userEdited defaults false).
     *  Once userEdited = 1 (set out-of-band, mirroring [applySleepEdit]), a re-upsert with different
     *  endTs/stagesJSON/startTsAdjusted must NOT move those three fields, but efficiency/restingHr/
     *  avgHrv keep updating, and userEdited itself is never cleared by this path. motionJSON is
     *  untouched by either upsert since it is named in neither the INSERT list nor the SET clause. */
    @Test
    fun upsertSleepSessionPreservingEditKeepsEditedBoundsOnConflict() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("dao-sleepedit-upsert.db")
        try {
            val dao = db.whoopDao()
            val dev = "dev1"
            val start = 1_000L

            // Initial insert: fresh row, userEdited defaults to false.
            dao.upsertSleepSessionPreservingEdit(
                dev, start, endTs = 2_000L, efficiency = 0.8, restingHr = 50, avgHrv = 40.0,
                stagesJSON = "[1,2]", userEdited = false, startTsAdjusted = null,
            )
            var row = dao.sleepSessions(dev, start, start, 1).single()
            assertEquals(2_000L, row.endTs)
            assertEquals("[1,2]", row.stagesJSON)
            assertEquals(false, row.userEdited)

            // A normal recompute refresh (still un-edited) DOES move endTs/stagesJSON/startTsAdjusted.
            dao.upsertSleepSessionPreservingEdit(
                dev, start, endTs = 2_500L, efficiency = 0.9, restingHr = 52, avgHrv = 41.0,
                stagesJSON = "[1,2,3]", userEdited = false, startTsAdjusted = 900L,
            )
            row = dao.sleepSessions(dev, start, start, 1).single()
            assertEquals(2_500L, row.endTs)
            assertEquals("[1,2,3]", row.stagesJSON)
            assertEquals(900L, row.startTsAdjusted)

            // Hand-edit the session out-of-band (mirrors applySleepEdit setting userEdited = 1).
            dao.applySleepEdit(dev, start, newStartTs = 800L, newEndTs = 2_600L, stagesJSON = null)
            row = dao.sleepSessions(dev, start, start, 1).single()
            assertEquals(true, row.userEdited)
            assertEquals(800L, row.startTsAdjusted)
            assertEquals(2_600L, row.endTs)

            // A subsequent recompute/import upsert must NOT clobber the edited endTs/stagesJSON/
            // startTsAdjusted, must NOT clear userEdited, but efficiency/restingHr/avgHrv keep updating.
            dao.upsertSleepSessionPreservingEdit(
                dev, start, endTs = 9_999L, efficiency = 0.99, restingHr = 55, avgHrv = 42.0,
                stagesJSON = "[9,9,9]", userEdited = false, startTsAdjusted = 111L,
            )
            row = dao.sleepSessions(dev, start, start, 1).single()
            assertEquals(2_600L, row.endTs, "edited endTs must survive a re-upsert")
            assertEquals("[1,2,3]", row.stagesJSON, "edited stagesJSON must survive a re-upsert")
            assertEquals(800L, row.startTsAdjusted, "edited startTsAdjusted must survive a re-upsert")
            assertEquals(true, row.userEdited, "userEdited is never cleared by the preserving upsert")
            assertEquals(0.99, row.efficiency, "efficiency keeps updating even on an edited session")
            assertEquals(55, row.restingHr)
            assertEquals(42.0, row.avgHrv)
        } finally {
            db.close()
        }
    }

    /** [WhoopDao.applySleepEdit] is a no-op (0 rows changed) when the natural key doesn't exist, and
     *  a null [stagesJSON] COALESCEs to keep the existing stages rather than clearing them. */
    @Test
    fun applySleepEditNoOpWhenMissingAndCoalescesNullStages() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("dao-sleepedit-noop.db")
        try {
            val dao = db.whoopDao()
            val changed = dao.applySleepEdit("dev1", 1_000L, newStartTs = 500L, newEndTs = 1_500L, stagesJSON = "[x]")
            assertEquals(0, changed, "editing a session that doesn't exist changes nothing")

            dao.upsertSleepSessions(
                listOf(SleepSession("dev1", 1_000L, 2_000L, stagesJSON = "[orig]"))
            )
            val ok = dao.applySleepEdit("dev1", 1_000L, newStartTs = 900L, newEndTs = 2_100L, stagesJSON = null)
            assertEquals(1, ok)
            val row = dao.sleepSessions("dev1", 1_000L, 1_000L, 1).single()
            assertEquals("[orig]", row.stagesJSON, "null stagesJSON COALESCEs to the existing value")
            assertEquals(900L, row.startTsAdjusted)
            assertEquals(2_100L, row.endTs)
            assertEquals(true, row.userEdited)
        } finally {
            db.close()
        }
    }

    /** Batched motion/sleep-state readers: a start with no banked column is simply absent from the
     *  result (never a fabricated row), and the range reader is bounded by [from, to] inclusive. */
    @Test
    fun sessionMotionsAndSleepStatesAreBatchedAndOmitAbsent() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("dao-session-batch.db")
        try {
            val dao = db.whoopDao()
            val dev = "dev1"
            dao.upsertSleepSessions(
                listOf(
                    SleepSession(dev, 100L, 200L),
                    SleepSession(dev, 200L, 300L),
                    SleepSession(dev, 300L, 400L),
                )
            )
            dao.updateSessionMotion(dev, 100L, "[0.1,0.2]")
            dao.updateSessionMotion(dev, 300L, "[0.3]")
            dao.updateSessionSleepState(dev, 200L, "[1,2,3]")

            val motions = dao.sessionMotionsForStarts(dev, listOf(100L, 200L, 300L, 999L))
            assertEquals(2, motions.size, "the un-set 200 and the never-inserted 999 are both absent")
            assertEquals(setOf(100L, 300L), motions.map { it.startTs }.toSet())
            assertEquals("[0.1,0.2]", motions.single { it.startTs == 100L }.motionJSON)

            val states = dao.sessionSleepStatesInRange(dev, 100L, 250L)
            assertEquals(1, states.size, "300 is out of [100,250] and 100 has no sleepStateJSON")
            assertEquals(200L, states.single().startTs)
            assertEquals("[1,2,3]", states.single().sleepStateJSON)

            assertTrue(dao.sessionSleepStatesInRange(dev, 1_000L, 2_000L).isEmpty())
        } finally {
            db.close()
        }
    }

    /** [WhoopDao.metricDayRange] returns both null when the series is empty, and the true MIN/MAX
     *  day-key once rows exist for that (deviceId, key), scoped away from a different key/device. */
    @Test
    fun metricDayRangeNullWhenEmptyElseMinMax() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("dao-metricdayrange.db")
        try {
            val dao = db.whoopDao()
            val empty = dao.metricDayRange("dev1", "hrv")
            assertNull(empty.earliest)
            assertNull(empty.latest)

            dao.upsertMetricSeries(
                listOf(
                    MetricSeriesRow("dev1", "2026-01-03", "hrv", 40.0),
                    MetricSeriesRow("dev1", "2026-01-01", "hrv", 38.0),
                    MetricSeriesRow("dev1", "2026-01-02", "hrv", 39.0),
                    MetricSeriesRow("dev1", "2026-05-01", "strain", 10.0),
                    MetricSeriesRow("other", "2026-01-01", "hrv", 1.0),
                )
            )
            val range = dao.metricDayRange("dev1", "hrv")
            assertEquals("2026-01-01", range.earliest)
            assertEquals("2026-01-03", range.latest)

            val other = dao.metricDayRange("dev1", "strain")
            assertEquals("2026-05-01", other.earliest)
            assertEquals("2026-05-01", other.latest)
        } finally {
            db.close()
        }
    }

    /** [WhoopDao.upsertWorkoutPreservingRoute] never names routePolyline, so a re-import that carries
     *  no route can't clobber a GPS route already recorded for that workout, while every other column
     *  (endTs here) still updates normally on conflict. */
    @Test
    fun upsertWorkoutPreservingRouteKeepsExistingRoute() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("dao-workout-route.db")
        try {
            val dao = db.whoopDao()
            val dev = "dev1"
            dao.upsertWorkoutPreservingRoute(
                dev, startTs = 100L, endTs = 200L, sport = "run", source = "strap",
                durationS = 100.0, energyKcal = 50.0, avgHr = 120, maxHr = 150,
                strain = 5.0, distanceM = 1_000.0, zonesJSON = "[1]", notes = "n1",
            )
            // Set a route out-of-band, exactly as Android would via the plain @Upsert path.
            dao.upsertWorkouts(
                listOf(
                    WorkoutRow(
                        dev, 100L, 200L, "run", "strap", 100.0, 50.0, 120, 150, 5.0, 1_000.0,
                        "[1]", "n1", routePolyline = "abc123",
                    )
                )
            )
            var row = dao.workouts(dev, 100L, 100L, 1).single()
            assertEquals("abc123", row.routePolyline)

            // Re-import via the route-preserving upsert: endTs changes, routePolyline must survive.
            dao.upsertWorkoutPreservingRoute(
                dev, startTs = 100L, endTs = 250L, sport = "run", source = "strap",
                durationS = 105.0, energyKcal = 55.0, avgHr = 121, maxHr = 151,
                strain = 5.5, distanceM = 1_050.0, zonesJSON = "[2]", notes = "n2",
            )
            row = dao.workouts(dev, 100L, 100L, 1).single()
            assertEquals(250L, row.endTs, "non-route columns keep updating on conflict")
            assertEquals("abc123", row.routePolyline, "routePolyline must survive the route-preserving upsert")
        } finally {
            db.close()
        }
    }

    /** [WhoopDao.upsertLabMarkersPreservingId] keeps the ORIGINAL row's id across a re-import that
     *  lands on the same natural key (deviceId, markerKey, takenAt, source), diverging on purpose
     *  from [WhoopDao.insertLabMarkersRaw]'s REPLACE semantics (which would adopt the new id).
     *  Every other column still updates on conflict, and the metricSeries projection stays in sync. */
    @Test
    fun upsertLabMarkersPreservingIdKeepsOriginalIdAndReprojects() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("dao-labmarker-id.db")
        try {
            val dao = db.whoopDao()
            val dev = "dev1"
            val written1 = dao.upsertLabMarkersPreservingId(
                listOf(
                    LabMarkerRow(
                        id = "id-1", deviceId = dev, markerKey = "ldl", category = "lipids",
                        day = "2026-01-01", takenAt = 1_000L, value = 100.0, unit = "mg/dL", source = "manual",
                    )
                )
            )
            assertEquals(1, written1)
            var row = dao.labMarkersByKey(dev, "ldl").single()
            assertEquals("id-1", row.id)
            assertEquals(100.0, row.value)
            assertEquals(
                100.0,
                dao.metricSeries(WhoopDao.LAB_BOOK_SOURCE_ID, "ldl", "2026-01-01", "2026-01-01").single().value,
                "the metricSeries projection under the Lab Book source reflects the initial value",
            )

            // Re-import the SAME natural key with a DIFFERENT id and updated value/category/note.
            val written2 = dao.upsertLabMarkersPreservingId(
                listOf(
                    LabMarkerRow(
                        id = "id-2", deviceId = dev, markerKey = "ldl", category = "lipids-updated",
                        day = "2026-01-01", takenAt = 1_000L, value = 105.0, unit = "mg/dL", source = "manual",
                        note = "retest",
                    )
                )
            )
            assertEquals(1, written2)
            row = dao.labMarkersByKey(dev, "ldl").single()
            assertEquals("id-1", row.id, "the original id must survive a re-upsert on the same natural key")
            assertEquals(105.0, row.value, "non-id columns keep updating on conflict")
            assertEquals("lipids-updated", row.category)
            assertEquals("retest", row.note)
            assertEquals(
                105.0,
                dao.metricSeries(WhoopDao.LAB_BOOK_SOURCE_ID, "ldl", "2026-01-01", "2026-01-01").single().value,
                "the metricSeries projection reflects the updated value",
            )
        } finally {
            db.close()
        }
    }
}
