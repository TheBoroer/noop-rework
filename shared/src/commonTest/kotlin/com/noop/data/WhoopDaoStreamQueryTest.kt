package com.noop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/**
 * Phase 2c-1 Task 4 (streams delegation): the DAO query shapes ADDED so the iOS `WhoopStore` stream
 * reads can delegate to shared Room byte-for-byte with the GRDB originals:
 *   - [WhoopDao.hrFingerprint]: windowed, device-scoped `(count, maxTs)`, measured `hrSample` ONLY;
 *   - [WhoopDao.hrBucketsWithConf]: [WhoopDao.hrBuckets] plus the per-bucket `MIN(conf)` projection;
 *   - [WhoopDao.countSleepState] / [WhoopDao.countPpgHr]: whole-table row counts for the store's
 *     per-table test helpers.
 *
 * These need a real Room instance, so (exactly like [BackupRestoreTest]) the body is gated on
 * [canRunFullRestore]: the plain-JVM androidUnitTest target has no Context for Room's Android builder
 * (no Robolectric in this module, by design), so the iosSimulatorArm64 / macosArm64 targets carry the
 * end-to-end proof. On JVM the tests return early (still counted, always green).
 */
class WhoopDaoStreamQueryTest {

    private fun freshDb(name: String): WhoopDatabase =
        openWhoopDatabaseAt((tempWorkDir().toPath() / name).toString())

    /** Measured hr at ts 100/200/300 on dev1 (+ a decoy on "other"): the fingerprint is device- and
     *  window-scoped, and an empty window COALESCEs to (0, 0). PPG rows never enter it. */
    @Test
    fun hrFingerprintIsWindowedDeviceScopedMeasuredOnly() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("dao-fingerprint.db")
        try {
            val dao = db.whoopDao()
            dao.insertHr(
                listOf(
                    HrSample("dev1", 100, 60),
                    HrSample("dev1", 200, 61),
                    HrSample("dev1", 300, 62),
                    HrSample("other", 200, 99),
                )
            )
            // A PPG row at a later ts must NOT move the measured-only fingerprint.
            dao.insertPpgHr(listOf(PpgHrSample("dev1", 5_000, 70, 0.9)))

            val full = dao.hrFingerprint("dev1", 0, 1_000)
            assertEquals(3, full.count)
            assertEquals(300, full.maxTs)

            val windowed = dao.hrFingerprint("dev1", 150, 250)
            assertEquals(1, windowed.count)
            assertEquals(200, windowed.maxTs)

            val other = dao.hrFingerprint("other", 0, 1_000)
            assertEquals(1, other.count)
            assertEquals(200, other.maxTs)

            val empty = dao.hrFingerprint("dev1", 6_000, 7_000)
            assertEquals(0, empty.count)
            assertEquals(0, empty.maxTs)
        } finally {
            db.close()
        }
    }

    /** A purely-measured bucket reads conf 1.0; a bucket whose ONLY source is a PPG fallback row (no
     *  measured hr that second) reads that row's stored conf; a measured second shadows the PPG one. */
    @Test
    fun hrBucketsWithConfSurfacesMinConf() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("dao-bucketconf.db")
        try {
            val dao = db.whoopDao()
            val dev = "my-whoop"
            // Bucket A (base..base+59): measured 90 at base; PPG 60 at base (shadowed), PPG 70 at base+1 (fills).
            val base = 1_780_000_000L
            dao.insertHr(listOf(HrSample(dev, base, 90)))
            dao.insertPpgHr(
                listOf(
                    PpgHrSample(dev, base, 60, 0.5),        // shadowed by measured 90 → excluded
                    PpgHrSample(dev, base + 1, 70, 0.8),    // fills the gap → contributes bpm 70, conf 0.8
                )
            )
            // Bucket B (base+120..): PPG-only, two rows, min conf 0.3.
            dao.insertPpgHr(
                listOf(
                    PpgHrSample(dev, base + 120, 55, 0.6),
                    PpgHrSample(dev, base + 121, 57, 0.3),
                )
            )

            val buckets = dao.hrBucketsWithConf(dev, base, base + 200, 60)
            assertEquals(2, buckets.size)
            // Bucket A: mean of {90 measured, 70 ppg} = 80, MIN(conf) = MIN(1.0, 0.8) = 0.8.
            // The bucket key is floor(ts / 60) * 60, not the raw base ts.
            assertEquals((base / 60) * 60, buckets[0].bucket)
            assertEquals(80.0, buckets[0].avgBpm, 0.001)
            assertEquals(0.8, buckets[0].minConf, 0.0001)
            // Bucket B: mean {55,57} = 56, MIN(conf) = 0.3 (PPG-only, weak).
            assertEquals(56.0, buckets[1].avgBpm, 0.001)
            assertEquals(0.3, buckets[1].minConf, 0.0001)
        } finally {
            db.close()
        }
    }

    /** Whole-table counts for the persist-only sleepState / ppgHr streams. */
    @Test
    fun countSleepStateAndPpgHr() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("dao-counts.db")
        try {
            val dao = db.whoopDao()
            assertEquals(0, dao.countSleepState())
            assertEquals(0, dao.countPpgHr())
            dao.insertSleepState(
                listOf(
                    SleepStateSampleEntity("d", 1, 0),
                    SleepStateSampleEntity("d", 2, 2),
                )
            )
            dao.insertPpgHr(listOf(PpgHrSample("d", 1, 55, 0.9)))
            assertEquals(2, dao.countSleepState())
            assertEquals(1, dao.countPpgHr())
        } finally {
            db.close()
        }
    }
}
