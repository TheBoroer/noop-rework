package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the v18 -> v19 and v19 -> v20 migrations ported from the legacy app (task #89). Both SQL
 * lists were extracted from the shipped legacy APK's dexdump (`EFFICIENCY_HEAL_MIGRATION_SQL` /
 * `PPG_WAVEFORM_MIGRATION_SQL` in the legacy `WhoopDatabase.<clinit>`) and MUST stay byte-identical
 * to it: a legacy database upgraded by the rework, and a legacy v20 backup restored into the
 * rework, must both land in exactly the schema/data state the old app would have produced.
 *
 * Same plain-JVM string/shape-assertion precedent as [OutboxMigrationTest] (see its header for why
 * no real connection is opened on this target).
 */
class Schema1920MigrationTest {

    // -- v18 -> v19: efficiency percent->fraction heal (data-only, no schema change) --

    @Test
    fun efficiencyHeal_isExactlyTheLegacyStatements() {
        assertEquals(
            listOf(
                "UPDATE `sleepSession` SET `efficiency` = `efficiency` / 100.0 WHERE `efficiency` > 1.5",
                "UPDATE `dailyMetric` SET `efficiency` = `efficiency` / 100.0 WHERE `efficiency` > 1.5",
            ),
            WhoopDatabase.EFFICIENCY_HEAL_MIGRATION_SQL,
        )
    }

    @Test
    fun efficiencyHeal_touchesNoSchema() {
        for (s in WhoopDatabase.EFFICIENCY_HEAL_MIGRATION_SQL) {
            val up = s.trimStart().uppercase()
            assertTrue("data-only heal, got: $s", up.startsWith("UPDATE"))
            for (banned in listOf("CREATE ", "DROP ", "ALTER ", "DELETE ", "INSERT ")) {
                assertTrue("must not contain '$banned': $s", !up.contains(banned))
            }
        }
    }

    @Test
    fun migration_versionPair_is18to19() {
        assertEquals(18, WhoopDatabase.MIGRATION_18_19.startVersion)
        assertEquals(19, WhoopDatabase.MIGRATION_18_19.endVersion)
    }

    // -- v19 -> v20: ppgWaveformSample table (additive) --

    @Test
    fun ppgWaveform_isExactlyTheLegacyCreate() {
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS `ppgWaveformSample` (`deviceId` TEXT NOT NULL, " +
                    "`ts` INTEGER NOT NULL, `samples` BLOB NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
            ),
            WhoopDatabase.PPG_WAVEFORM_MIGRATION_SQL,
        )
    }

    @Test
    fun ppgWaveform_createMatchesRoomSchemaShape() {
        val create = WhoopDatabase.PPG_WAVEFORM_CREATE_SQL
        assertTrue("composite PK", create.contains("PRIMARY KEY(`deviceId`, `ts`)"))
        assertTrue("`deviceId` NOT NULL TEXT", create.contains("`deviceId` TEXT NOT NULL"))
        assertTrue("`ts` NOT NULL INTEGER", create.contains("`ts` INTEGER NOT NULL"))
        assertTrue("`samples` NOT NULL BLOB", create.contains("`samples` BLOB NOT NULL"))
        assertTrue("no SQL DEFAULT", !create.uppercase().contains("DEFAULT"))
    }

    @Test
    fun migration_versionPair_is19to20() {
        assertEquals(19, WhoopDatabase.MIGRATION_19_20.startVersion)
        assertEquals(20, WhoopDatabase.MIGRATION_19_20.endVersion)
    }

    @Test
    fun schemaVersion_is20_andAllMigrationsEndThere() {
        assertEquals(20, WhoopDatabase.SCHEMA_VERSION)
        assertEquals(20, WhoopDatabase.ALL_MIGRATIONS.last().endVersion)
        // Contiguous chain: each migration starts where the previous one ended.
        WhoopDatabase.ALL_MIGRATIONS.toList().zipWithNext { a, b ->
            assertEquals("gap between ${a.endVersion} and ${b.startVersion}", a.endVersion, b.startVersion)
        }
    }
}
