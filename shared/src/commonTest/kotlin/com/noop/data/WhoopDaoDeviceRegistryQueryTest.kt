package com.noop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

/**
 * Phase 2c-1 Task 6 (device registry redesign): the two new atomic composite methods added to
 * [WhoopDao] so the Swift `DeviceRegistryStore` Room branch can call one suspend function through the
 * same `roomDb.whoopDao()` surface every other delegated store uses, instead of bridging the more
 * complex [DeviceRegistry] `Transactor` façade:
 *   - [WhoopDao.setActiveAtomic]: demote-then-promote (invariant I1) in one transaction;
 *   - [WhoopDao.deleteDeviceDataAtomic]: fan out to every deviceId-keyed table in one transaction.
 *
 * Both need a real Room instance to prove `@Transaction` actually wraps the calls (the JVM
 * `FakeRegistryDao` used by [DeviceRegistryTest] has no real transaction semantics), so this follows
 * [WhoopDaoStreamQueryTest]'s pattern: gated on [canRunFullRestore], real assertions run on
 * `iosSimulatorArm64Test` / `macosArm64Test`; the plain-JVM `androidUnitTest` target returns early
 * (still counted, always green).
 *
 * [deleteDeviceDataAtomic] fans out to all 17 deviceId-keyed tables; this test exercises a
 * representative spread (a raw stream table, an event table, a computed/cached table, a series table,
 * and dayOwnership) rather than every table, matching the established "Upsert rule" (Task 4/5): the
 * Swift-side `DeviceRegistryStoreTests` suite (converted to run on Room) is the byte-level arbiter for
 * the full 17-table fan-out, since that's what the Swift `DeviceRegistryStore.deviceScopedTables` list
 * itself is checked against.
 */
class WhoopDaoDeviceRegistryQueryTest {

    private fun freshDb(name: String): WhoopDatabase =
        openWhoopDatabaseAt((tempWorkDir().toPath() / name).toString())

    /** Fresh install seeds "my-whoop" active (Task 6 seed-handoff fix); promoting "polar-1" demotes
     *  it to paired and stamps polar-1's lastSeenAt, both inside setActiveAtomic's one transaction. */
    @Test
    fun setActiveAtomicDemotesThenPromotesInOneTransaction() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("dao-registry-setactive.db")
        try {
            val dao = db.whoopDao()
            assertEquals("my-whoop", dao.activeDeviceId())

            dao.upsertPairedDevice(
                PairedDeviceRow(
                    id = "polar-1", brand = "Polar", model = "H10", nickname = null,
                    sourceKind = "liveBLE", capabilities = "hr,hrv", status = "paired",
                    addedAt = 1, lastSeenAt = 1,
                )
            )

            dao.setActiveAtomic(id = "polar-1", now = 500)

            assertEquals("polar-1", dao.activeDeviceId())
            val byId = dao.pairedDevices().associateBy { it.id }
            assertEquals("active", byId.getValue("polar-1").status)
            assertEquals(500L, byId.getValue("polar-1").lastSeenAt)
            assertEquals("paired", byId.getValue("my-whoop").status) // demoted, not deleted
        } finally {
            db.close()
        }
    }

    /** Seeds one row per sampled table for two devices; deleteDeviceDataAtomic("dev-a") clears every
     *  dev-a row across the sampled tables while dev-b's rows (and the registry row) survive intact. */
    @Test
    fun deleteDeviceDataAtomicClearsSampledTablesDeviceScoped() = runTest {
        if (!canRunFullRestore) return@runTest
        val db = freshDb("dao-registry-deletedata.db")
        try {
            val dao = db.whoopDao()
            for (dev in listOf("dev-a", "dev-b")) {
                dao.insertHr(listOf(HrSample(dev, 100, 60)))
                dao.insertEvents(listOf(EventRow(dev, 100, "note", "{}")))
                dao.upsertDailyMetrics(listOf(DailyMetric(deviceId = dev, day = "2026-06-15")))
                dao.upsertMetricSeries(listOf(MetricSeriesRow(dev, "2026-06-15", "steps", 1234.0)))
                dao.setDayOwner(DayOwnershipRow(day = "2026-06-15-$dev", deviceId = dev, locked = false))
            }

            dao.deleteDeviceDataAtomic("dev-a")

            assertNull(dao.latestHr("dev-a")) // dev-a's hr row is gone
            assertEquals(60, dao.latestHr("dev-b")?.bpm) // dev-b's hr row survives untouched
            assertEquals(0, dao.events("dev-a", 0, 1000, 10).size)
            assertEquals(1, dao.events("dev-b", 0, 1000, 10).size)
            assertEquals(0, dao.dailyMetricsRange("dev-a", "2026-06-15", "2026-06-15").size)
            assertEquals(1, dao.dailyMetricsRange("dev-b", "2026-06-15", "2026-06-15").size)
            assertEquals(0, dao.metricSeriesCount("dev-a"))
            assertEquals(1, dao.metricSeriesCount("dev-b"))
            assertNull(dao.dayOwner("2026-06-15-dev-a"))
            assertEquals("dev-b", dao.dayOwner("2026-06-15-dev-b")?.deviceId)
        } finally {
            db.close()
        }
    }
}
