package com.noop.data

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Task 6 (device registry) seed-handoff fix, pinned directly against [whoopDatabase] (the real Apple
 * open path the Swift `WhoopStore` uses, not a bare `Room.databaseBuilder`). GRDB's own MIGRATION_7_8-
 * equivalent (v15) seeds one `pairedDevice` row ("my-whoop", active) on a fresh install; Room's
 * fresh-install path had no such seed until the `RoomDatabase.Callback.onCreate(connection:)` added in
 * `WhoopDatabase.apple.kt`. Apple-only, and deliberately so, same rationale as [WhoopDatabaseSmokeTest]:
 * this needs a real Room instance with the bundled SQLite driver, which only the Apple native test
 * targets provide in this module.
 */
class WhoopDatabaseSeedHandoffTest {

    private fun freshPath(): String =
        NSTemporaryDirectory() + "noop_seed_handoff_" + Random.nextLong().toString() + ".db"

    /** A brand-new file, opened for the first time, has the seeded "my-whoop" row, active, with the
     *  exact GRDB-parity capability set, and nothing else. */
    @Test
    fun freshInstallSeedsMyWhoopActive() = runTest {
        val db = whoopDatabase(freshPath())
        try {
            val dao = db.whoopDao()
            val devices = dao.pairedDevices()
            assertEquals(1, devices.size)
            val seeded = devices.single()
            assertEquals("my-whoop", seeded.id)
            assertEquals("WHOOP", seeded.brand)
            assertEquals("WHOOP", seeded.model)
            assertNull(seeded.nickname)
            assertNull(seeded.peripheralId)
            assertEquals("liveBLE", seeded.sourceKind)
            assertEquals("hr,hrv,spo2,skinTemp,sleep,strainLoad", seeded.capabilities)
            assertEquals("active", seeded.status)
            assertEquals("my-whoop", dao.activeDeviceId())
        } finally {
            db.close()
        }
    }

    /** Re-opening the same file (an existing database, not a fresh create) never re-fires `onCreate`,
     *  so the seed stays exactly one row even across repeated opens -- INSERT OR IGNORE makes the seed
     *  itself idempotent too, matching the GRDB migration's own re-run safety. */
    @Test
    fun reopeningExistingFileNeverDuplicatesTheSeed() = runTest {
        val path = freshPath()
        val first = whoopDatabase(path)
        first.whoopDao().pairedDevices() // touch it so the file is actually created on disk
        first.close()

        val second = whoopDatabase(path)
        try {
            val devices = second.whoopDao().pairedDevices()
            assertEquals(1, devices.size)
            assertEquals("my-whoop", devices.single().id)
        } finally {
            second.close()
        }
    }
}
