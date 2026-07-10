package com.noop.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke test for the Phase 2a KMP [WhoopDatabase]. iOS-only, and deliberately so: the round-trip needs
 * a real Room instance, and on the JVM (`androidUnitTest`) Room's Android builders require a
 * `Context` (i.e. Robolectric/instrumentation), which this module intentionally does not use (see the
 * SQL-only migration tests). The iOS simulator target (`iosSimulatorArm64Test`, part of the gate) can
 * open a real Room database with the bundled SQLite driver directly, so it carries the open+round-trip
 * proof: a fresh store is empty, one inserted HR sample persists through Room's generated schema and
 * DAO adapters, and it reads back with the exact bpm.
 */
class WhoopDatabaseSmokeTest {

    private fun testDatabase(): WhoopDatabase {
        // NSTemporaryDirectory + a random name → a fresh, empty file per run so `countHr() == 0` holds.
        val path = NSTemporaryDirectory() + "noop_smoke_" + Random.nextLong().toString() + ".db"
        return Room.databaseBuilder<WhoopDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    @Test
    fun opensAndRoundTripsAnHrSample() = runTest {
        val db = testDatabase()
        try {
            val dao = db.whoopDao()
            assertEquals(0, dao.countHr()) // fresh store, no rows yet
            dao.insertHr(listOf(HrSample(deviceId = "smoke", ts = 1_700_000_000L, bpm = 55)))
            assertEquals(1, dao.countHr()) // the insert persisted through Room's generated adapter
            assertEquals(55, dao.latestHr("smoke")?.bpm) // and reads back the exact row
        } finally {
            db.close()
        }
    }
}
