package com.noop.data

import androidx.room.Transactor
import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import kotlinx.coroutines.runBlocking
import kotlin.math.floor

/**
 * One-shot ETL from the legacy Swift GRDB `whoop.sqlite` into the shared Room database
 * (Phase 2c-1 Task 2). Copies the 22 mapped user tables; `rawBatch` and `cursors` stay
 * GRDB-only (skipped), `dismissedWorkout` / `dismissedSleep` have no GRDB twin and stay empty.
 *
 * Contract (binding, see the phase plan):
 *  - batched: 10k rows per write transaction,
 *  - resumable-by-restart: every mapped Room table is truncated before copying,
 *  - the legacy file is opened READ-ONLY and never written,
 *  - [onProgress] fires once per committed batch,
 *  - a REAL value found where an INTEGER column is expected fails loudly unless it is an
 *    exact integer (never silently truncated); the ONE deliberate conversion is
 *    ppgHrSample.bpm (GRDB REAL -> Room INTEGER, rounded half-up),
 *  - stepSample/ppgHrSample gain `synced = 0` (Room NOT NULL, absent in GRDB),
 *  - workout.routePolyline (Room-only column) is simply not named in the INSERT and lands NULL.
 *
 * Implementation is raw SQL on BundledSQLiteDriver connections: streamed `SELECT` over the
 * legacy file, plain batched `INSERT` on the Room database's own writer connection
 * ([useWriterConnection]). No per-row entity mapping, this is bulk ETL; the DAO adapters
 * would be needless overhead, and the semantics are pinned by GrdbMigratorTest through the DAO.
 */
object GrdbMigrator {
    data class Progress(val table: String, val copied: Long, val totalEstimate: Long)

    sealed interface Result {
        /** Every mapped table copied; [rowCounts] holds the copied row count per table. */
        data class Done(val rowCounts: Map<String, Long>) : Result

        /** The copy stopped at [table]; already-committed batches remain but a re-run truncates them. */
        data class Failed(val table: String, val message: String) : Result
    }

    internal const val BATCH_SIZE = 10_000

    /** How a legacy column's value crosses into the Room column of the same position. */
    private enum class Kind {
        /** TEXT, copied verbatim (NULL passes through). */
        TEXT,

        /**
         * INTEGER, copied verbatim. If the legacy cell actually holds a REAL (SQLite's flexible
         * typing allows it), the value must be an EXACT integer, anything fractional fails the
         * table loudly instead of being truncated.
         */
        INT,

        /** REAL, copied verbatim (an INTEGER-stored cell converts exactly). */
        REAL,

        /** ppgHrSample.bpm only: GRDB REAL -> Room INTEGER, rounded HALF-UP (72.5 -> 73). */
        PPG_BPM_HALF_UP,
    }

    private data class Col(val name: String, val kind: Kind)

    /**
     * A mapped table: same table name on both sides, [cols] are the shared columns (same names
     * in GRDB and Room, per Database.swift + Entities.kt), [syntheticSynced] appends Room's
     * NOT-NULL `synced` column (absent in GRDB) bound to the constant 0.
     */
    private class TableSpec(val name: String, val cols: List<Col>, val syntheticSynced: Boolean = false) {
        val selectSql = "SELECT ${cols.joinToString(", ") { "`${it.name}`" }} FROM `$name`"
        val insertSql = run {
            val names = cols.map { it.name } + (if (syntheticSynced) listOf("synced") else emptyList())
            val marks = names.joinToString(", ") { "?" }
            "INSERT INTO `$name` (${names.joinToString(", ") { "`$it`" }}) VALUES ($marks)"
        }
    }

    private fun t(name: String) = Col(name, Kind.TEXT)
    private fun i(name: String) = Col(name, Kind.INT)
    private fun r(name: String) = Col(name, Kind.REAL)

    /**
     * The 22 mapped tables in GRDB creation order (Database.swift v1..v22). Column lists are the
     * FINAL legacy shape (all 23 GRDB migrations applied); booleans (battery.charging,
     * sleepSession.userEdited, journal.answeredYes, dayOwnership.locked) travel as their stored
     * INTEGER 0/1. rawBatch and cursors are deliberately absent.
     */
    private val TABLES: List<TableSpec> = listOf(
        TableSpec("device", listOf(t("id"), t("mac"), t("name"), i("firstSeen"), i("lastSeen"))),
        TableSpec("hrSample", listOf(t("deviceId"), i("ts"), i("bpm"), i("synced"))),
        TableSpec("rrInterval", listOf(t("deviceId"), i("ts"), i("rrMs"), i("synced"))),
        TableSpec("event", listOf(t("deviceId"), i("ts"), t("kind"), t("payloadJSON"), i("synced"))),
        TableSpec("battery", listOf(t("deviceId"), i("ts"), r("soc"), i("mv"), i("charging"), i("synced"))),
        TableSpec("spo2Sample", listOf(t("deviceId"), i("ts"), i("red"), i("ir"), i("synced"))),
        TableSpec("skinTempSample", listOf(t("deviceId"), i("ts"), i("raw"), i("synced"))),
        TableSpec("respSample", listOf(t("deviceId"), i("ts"), i("raw"), i("synced"))),
        TableSpec("gravitySample", listOf(t("deviceId"), i("ts"), r("x"), r("y"), r("z"), i("synced"))),
        TableSpec(
            "sleepSession",
            listOf(
                t("deviceId"), i("startTs"), i("endTs"), r("efficiency"), i("restingHr"), r("avgHrv"),
                t("stagesJSON"), i("userEdited"), i("startTsAdjusted"), t("motionJSON"), t("sleepStateJSON"),
            ),
        ),
        TableSpec(
            "dailyMetric",
            listOf(
                t("deviceId"), t("day"), r("totalSleepMin"), r("efficiency"), r("deepMin"), r("remMin"),
                r("lightMin"), i("disturbances"), i("restingHr"), r("avgHrv"), r("recovery"), r("strain"),
                i("exerciseCount"), r("spo2Pct"), r("skinTempDevC"), r("respRateBpm"), i("steps"),
                r("activeKcalEst"), i("spo2Red"), i("spo2Ir"),
            ),
        ),
        TableSpec(
            "journal",
            listOf(t("deviceId"), t("day"), t("question"), i("answeredYes"), t("notes"), r("numericValue")),
        ),
        // Room's workout.routePolyline is not named in the INSERT: a legacy workout lands NULL there.
        TableSpec(
            "workout",
            listOf(
                t("deviceId"), i("startTs"), i("endTs"), t("sport"), t("source"), r("durationS"),
                r("energyKcal"), i("avgHr"), i("maxHr"), r("strain"), r("distanceM"), t("zonesJSON"), t("notes"),
            ),
        ),
        TableSpec(
            "appleDaily",
            listOf(
                t("deviceId"), t("day"), i("steps"), r("activeKcal"), r("basalKcal"), r("vo2max"),
                i("avgHr"), i("maxHr"), i("walkingHr"), r("weightKg"),
            ),
        ),
        TableSpec("metricSeries", listOf(t("deviceId"), t("day"), t("key"), r("value"))),
        // GRDB stepSample has no synced column; Room's is NOT NULL, so it is synthesized as 0.
        TableSpec(
            "stepSample",
            listOf(t("deviceId"), i("ts"), i("counter"), i("activityClass")),
            syntheticSynced = true,
        ),
        // GRDB ppgHrSample stores bpm as REAL (a float estimate); Room stores INTEGER. Rounded
        // half-up, the one deliberate lossy conversion of the whole ETL. synced synthesized as 0.
        TableSpec(
            "ppgHrSample",
            listOf(t("deviceId"), i("ts"), Col("bpm", Kind.PPG_BPM_HALF_UP), r("conf")),
            syntheticSynced = true,
        ),
        TableSpec(
            "pairedDevice",
            listOf(
                t("id"), t("brand"), t("model"), t("nickname"), t("sourceKind"), t("capabilities"),
                t("status"), i("addedAt"), i("lastSeenAt"), t("peripheralId"),
            ),
        ),
        TableSpec("dayOwnership", listOf(t("day"), t("deviceId"), i("locked"))),
        TableSpec(
            "labMarker",
            listOf(
                t("id"), t("deviceId"), t("markerKey"), t("category"), t("day"), i("takenAt"),
                r("value"), t("valueText"), t("unit"), t("source"), t("note"), t("referenceText"),
            ),
        ),
        TableSpec("sleepStateSample", listOf(t("deviceId"), i("ts"), i("state"))),
        TableSpec(
            "liveSession",
            listOf(
                t("deviceId"), i("startTs"), i("endTs"), r("chargeAtStart"), r("floorBpm"), r("ceilingBpm"),
                r("inBandSec"), r("belowSec"), r("aboveSec"), i("pushCount"), i("easeCount"), t("hrSource"),
            ),
        ),
    )

    /**
     * Copies all mapped user tables from the legacy GRDB database at [legacyPath] into the Room
     * database at [roomPath] (created at the current schema version if absent). Batched
     * ([BATCH_SIZE] rows/transaction), resumable by restart (truncates the mapped Room tables
     * first), never writes to [legacyPath]. [onProgress] fires per committed batch.
     */
    fun migrate(legacyPath: String, roomPath: String, onProgress: (Progress) -> Unit = {}): Result {
        val legacy = try {
            BundledSQLiteDriver().open(legacyPath, SQLITE_OPEN_READONLY)
        } catch (e: Exception) {
            return Result.Failed("(open legacy)", e.message ?: "could not open $legacyPath")
        }
        try {
            // whoopDatabase() builds (or opens) the Room store at the CURRENT schema version, so
            // the target shape and its identity hash are always Room's own, never hand-written.
            val db = try {
                whoopDatabase(roomPath)
            } catch (e: Exception) {
                return Result.Failed("(open room)", e.message ?: "could not open $roomPath")
            }
            try {
                return runBlocking {
                    db.useWriterConnection { transactor ->
                        // Resume-by-restart: wipe every mapped table so a rerun after any
                        // interruption starts from a clean slate (plain INSERTs stay conflict-free).
                        try {
                            transactor.immediateTransaction<Unit> {
                                TABLES.forEach { execSQL("DELETE FROM `${it.name}`") }
                            }
                        } catch (e: Exception) {
                            return@useWriterConnection Result.Failed("(truncate)", e.message ?: "truncate failed")
                        }
                        val counts = LinkedHashMap<String, Long>()
                        for (spec in TABLES) {
                            try {
                                counts[spec.name] = copyTable(legacy, transactor, spec, onProgress)
                            } catch (e: Throwable) {
                                return@useWriterConnection Result.Failed(
                                    spec.name,
                                    e.message ?: e::class.simpleName ?: "unknown error",
                                )
                            }
                        }
                        Result.Done(counts)
                    }
                }
            } finally {
                db.close()
            }
        } finally {
            legacy.close()
        }
    }

    /**
     * Post-copy verification: per-table `SELECT COUNT(*)` comparison, legacy vs Room, for the
     * mapped tables only. Returns table -> (legacyCount, roomCount); both files opened read-only.
     */
    fun verify(legacyPath: String, roomPath: String): Map<String, Pair<Long, Long>> {
        val legacy = BundledSQLiteDriver().open(legacyPath, SQLITE_OPEN_READONLY)
        try {
            val room = BundledSQLiteDriver().open(roomPath, SQLITE_OPEN_READONLY)
            try {
                return TABLES.associate { spec ->
                    spec.name to (legacy.countRows(spec.name) to room.countRows(spec.name))
                }
            } finally {
                room.close()
            }
        } finally {
            legacy.close()
        }
    }

    // MARK: - Copy internals

    /** Streams one legacy table into Room in [BATCH_SIZE]-row transactions; returns rows copied. */
    private suspend fun copyTable(
        legacy: SQLiteConnection,
        transactor: Transactor,
        spec: TableSpec,
        onProgress: (Progress) -> Unit,
    ): Long {
        val total = legacy.countRows(spec.name)
        var copied = 0L
        val batch = ArrayList<Array<Any?>>(BATCH_SIZE)

        suspend fun flush() {
            if (batch.isEmpty()) return
            transactor.immediateTransaction {
                usePrepared(spec.insertSql) { insert ->
                    for (row in batch) {
                        insert.reset()
                        insert.clearBindings()
                        row.forEachIndexed { index, value ->
                            val slot = index + 1
                            when (value) {
                                null -> insert.bindNull(slot)
                                is Long -> insert.bindLong(slot, value)
                                is Double -> insert.bindDouble(slot, value)
                                is String -> insert.bindText(slot, value)
                                else -> error("unbindable value ${value::class} in ${spec.name}")
                            }
                        }
                        insert.step()
                    }
                }
            }
            copied += batch.size
            batch.clear()
            onProgress(Progress(spec.name, copied, total))
        }

        val select = legacy.prepare(spec.selectSql)
        try {
            while (select.step()) {
                batch.add(readRow(select, spec))
                if (batch.size == BATCH_SIZE) flush()
            }
        } finally {
            select.close()
        }
        flush()
        // Empty table: still report completion so callers can render per-table progress.
        if (copied == 0L) onProgress(Progress(spec.name, 0, total))
        return copied
    }

    /** Reads one legacy row into bind-ready values, enforcing the per-column [Kind] rules. */
    private fun readRow(select: SQLiteStatement, spec: TableSpec): Array<Any?> {
        val extra = if (spec.syntheticSynced) 1 else 0
        val row = arrayOfNulls<Any?>(spec.cols.size + extra)
        spec.cols.forEachIndexed { index, col ->
            row[index] = if (select.isNull(index)) {
                null
            } else {
                when (col.kind) {
                    Kind.TEXT -> select.getText(index)
                    Kind.REAL -> select.getDouble(index)
                    Kind.INT -> {
                        // Exact-integer guard: SQLite's flexible typing can hand back a REAL from
                        // an INTEGER-typed column. getLong() would silently truncate 65.5 to 65,
                        // so compare against the double form and fail the table loudly instead.
                        val long = select.getLong(index)
                        val double = select.getDouble(index)
                        if (long.toDouble() != double) {
                            error("non-integer value $double in INTEGER column ${spec.name}.${col.name}")
                        }
                        long
                    }
                    Kind.PPG_BPM_HALF_UP -> floor(select.getDouble(index) + 0.5).toLong()
                }
            }
        }
        if (spec.syntheticSynced) row[spec.cols.size] = 0L
        return row
    }

    private fun SQLiteConnection.countRows(table: String): Long {
        val statement = prepare("SELECT COUNT(*) FROM `$table`")
        try {
            check(statement.step()) { "COUNT(*) returned no row for $table" }
            return statement.getLong(0)
        } finally {
            statement.close()
        }
    }
}
