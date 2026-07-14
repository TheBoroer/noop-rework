package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the additive v17 -> v18 Room migration (the shared outbox tables [OutboxBatchRow],
 * [OutboxCursorRow] replacing the legacy GRDB outbox, Phase 2c-2 Task 1). No Robolectric here, so, like
 * [DailySpo2RawMigrationTest] / [LabMarkerMigrationTest], the migration SQL is exposed as internal
 * constants ([WhoopDatabase.OUTBOX_MIGRATION_SQL] and friends) and pinned here to Room's generated shape
 * with pure string/shape assertions, exactly the precedent those two files set (neither opens a real
 * connection either).
 *
 * Two behavioral round trips were tried and both confirmed infeasible on this plain-JVM androidUnitTest
 * target, so neither lives here:
 *  - `Room.databaseBuilder<WhoopDatabase>(name)` (the context-free KMP builder that works on Apple
 *    targets) needs a `context: Context` argument on the Android target specifically (compile error:
 *    "No value passed for parameter 'context'").
 *  - A bare `BundledSQLiteDriver().open(path)` connection (bypassing Room's builder entirely) fails at
 *    runtime with `UnsatisfiedLinkError: no sqliteJni in java.library.path`: the Android-target variant
 *    of `androidx.sqlite:sqlite-bundled` resolved here expects its native library to be unpacked by the
 *    Android runtime (`jniLibs`), which never happens for a plain JVM unit test run without Robolectric.
 *
 * Both are the same class of limitation [BackupTestEnv.android.kt]'s `canRunFullRestore = false` already
 * documents for the classic Android Room builder, just confirmed here to also cover the KMP builder and
 * the bundled driver's raw connection API. So the real insert/readback round trip (proving
 * [WhoopDatabase.OUTBOX_MIGRATION_SQL] matches Room's own generated schema) lives in
 * [OutboxDaoRoomSchemaTest] (`commonTest`), gated on [canRunFullRestore]: a real assertion on
 * iosSimulatorArm64 / macosArm64 (where the Apple [whoopDatabase] factory builds a real database with no
 * Context and a working native driver), an early return (still green) on this plain-JVM target.
 */
class OutboxMigrationTest {

    // -- Pure string/shape assertions (mirrors LabMarkerMigrationTest / DailySpo2RawMigrationTest) --

    @Test
    fun migration_isAdditive_onlyCreateStatements() {
        val sql = WhoopDatabase.OUTBOX_MIGRATION_SQL
        assertEquals("batch table + 2 indexes + cursor table", 4, sql.size)
        for (s in sql) {
            val up = s.trimStart().uppercase()
            assertTrue("only CREATE statements allowed, got: $s", up.startsWith("CREATE"))
            for (banned in listOf("DROP ", "ALTER ", "DELETE ", "UPDATE ", "INSERT ")) {
                assertTrue("additive migration must not contain '$banned': $s", !up.contains(banned))
            }
        }
    }

    @Test
    fun migration_createsOutboxBatchTable_withRoomSchemaShape() {
        val create = WhoopDatabase.OUTBOX_BATCH_CREATE_SQL

        assertTrue("single-column TEXT primary key", create.contains("PRIMARY KEY(`batchId`)"))

        // NOT NULL columns (Kotlin non-null, no SQL default).
        for (col in listOf("`batchId`", "`deviceId`")) {
            assertTrue("$col must be NOT NULL", create.contains("$col TEXT NOT NULL"))
        }
        for (col in listOf(
            "`capturedAt`", "`deviceClockRef`", "`wallClockRef`", "`startTs`", "`endTs`",
            "`frameCount`", "`byteSize`",
        )) {
            assertTrue("$col must be NOT NULL INTEGER", create.contains("$col INTEGER NOT NULL"))
        }
        assertTrue("`framesBlob` must be NOT NULL BLOB", create.contains("`framesBlob` BLOB NOT NULL"))

        // Nullable column (Kotlin `Long?`): declared with the type and NO `NOT NULL`.
        assertTrue(
            "`syncedAt` is nullable INTEGER",
            create.contains("`syncedAt` INTEGER") && !create.contains("`syncedAt` INTEGER NOT NULL"),
        )

        assertTrue("no SQL DEFAULT in the CREATE TABLE", !create.uppercase().contains("DEFAULT"))
    }

    @Test
    fun migration_createsOutboxCursorTable_withRoomSchemaShape() {
        val create = WhoopDatabase.OUTBOX_CURSOR_CREATE_SQL
        assertTrue("single-column TEXT primary key", create.contains("PRIMARY KEY(`name`)"))
        assertTrue("`name` must be NOT NULL", create.contains("`name` TEXT NOT NULL"))
        assertTrue("`value` must be NOT NULL INTEGER", create.contains("`value` INTEGER NOT NULL"))
        assertTrue("no SQL DEFAULT in the CREATE TABLE", !create.uppercase().contains("DEFAULT"))
    }

    @Test
    fun migration_createsExactIndexes() {
        assertEquals(
            listOf(
                "CREATE INDEX IF NOT EXISTS `idx_outboxBatch_syncedAt` ON `outboxBatch` (`syncedAt`)",
                "CREATE INDEX IF NOT EXISTS `idx_outboxBatch_capturedAt` ON `outboxBatch` (`capturedAt`)",
            ),
            WhoopDatabase.OUTBOX_BATCH_INDEX_SQL,
        )
    }

    @Test
    fun migration_versionPair_is17to18() {
        assertEquals(17, WhoopDatabase.MIGRATION_17_18.startVersion)
        assertEquals(18, WhoopDatabase.MIGRATION_17_18.endVersion)
    }

    @Test
    fun outboxCursorRow_defaultsAndCopy_workAsPlainDataClass() {
        val row = OutboxCursorRow(name = "drain", value = 0)
        assertEquals(0L, row.value)
        assertEquals(42L, row.copy(value = 42).value)
    }
}
