@file:OptIn(ExperimentalTime::class)

package com.noop.analytics

import com.noop.data.MetricSeriesRow
import com.noop.util.formatShortTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/*
 * SleepMark.kt — tap-to-mark "going to sleep" / "awake" (#461 Phase 1).
 *
 * Faithful Kotlin mirror of Strand/Data/SleepMark.swift. A user-tapped sleep boundary, captured for
 * the record only — it does NOT feed the sleep detector (that stays the strap's job). Phase 1 is pure
 * logging: every mark is persisted into the existing long-format `metricSeries` store under the key
 * "sleep_mark" AND appended as a human-readable line to the shareable strap log, so a mark shows up in
 * a debug export.
 *
 * The store's natural key is (deviceId, day, key) with a single REAL `value`, so the mark TYPE is
 * encoded in the value (0 = bedtime, 1 = wake) and the row is keyed on the mark's local calendar day.
 * The precise wall-clock instant lives in the strap-log line (and [tsMs] here).
 *
 * Pure + DB-free so it unit-tests without a UI: encode -> MetricSeriesRow, decode <- MetricSeriesRow,
 * and the formatted log line. The screen is the only place that does I/O. Keep the value encoding and
 * the "sleep_mark" key byte-identical to Swift — both clients read the same series.
 */

/** One sleep boundary the user tapped. */
enum class SleepMarkType(val seriesValue: Int) {
    BEDTIME(0),   // "Going to sleep"
    WAKE(1);      // "I'm awake"

    /** Short word used in the confirming toast / strap-log line. */
    val word: String get() = if (this == BEDTIME) "bedtime" else "wake"

    companion object {
        /** Decode a persisted series value back to a type. Tolerant of float drift (rounds) and
         *  clamps any unexpected value to the nearest valid case so a corrupt row never crashes.
         *  `roundToInt()` ties round towards positive infinity, same as the `Math.round` this
         *  replaces (JVM-only, not available in commonMain) - not a date-formatting API, but a second
         *  blocker this file's original PHASE2 tag didn't name, found while hoisting. */
        fun fromSeriesValue(value: Double): SleepMarkType =
            if (value.roundToInt() == WAKE.seriesValue) WAKE else BEDTIME
    }
}

/**
 * A captured mark: a type plus the wall-clock instant it was tapped (unix MILLISECONDS — the `tsMs`
 * the spec asks for). The calendar [dayKey] is derived locally for the store's natural key.
 */
data class SleepMark(
    val type: SleepMarkType,
    val tsMs: Long,
) {
    /** The mark's local calendar day (yyyy-MM-dd) — the `day` of the store's natural key. Local zone
     *  so the mark lands on the day the user actually tapped it. `kotlinx.datetime.LocalDate.toString()`
     *  is already the zero-padded ISO "yyyy-MM-dd" form (no locale-sensitive text in it), so this maps
     *  directly - unlike `ConnectionTrace.isoDate`'s date-TIME case, `LocalDate.toString()` has no
     *  "omit if zero" quirk to work around. */
    val dayKey: String get() =
        Instant.fromEpochMilliseconds(tsMs).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

    /** Project this mark into a `metricSeries` row: key "sleep_mark", value 0/1 = type, day = local
     *  calendar day. Upsert is idempotent by (deviceId, day, key); a later same-day mark replaces the
     *  earlier value — the strap log keeps the full sequence, which Phase-1 logging relies on. */
    fun metricPoint(deviceId: String): MetricSeriesRow =
        MetricSeriesRow(deviceId = deviceId, day = dayKey, key = SERIES_KEY, value = type.seriesValue.toDouble())

    /** The human-readable strap-log line, e.g. "Sleep mark · bedtime (going to sleep) @ 23:42".
     *  Appended to the shared strap log so the mark appears in a debug export. Carries no PII. */
    fun logLine(): String {
        val clock = formatShortTime(tsMs / 1000L, zoneId = null)
        val phrase = if (type == SleepMarkType.BEDTIME) "going to sleep" else "awake"
        return "Sleep mark · ${type.word} ($phrase) @ $clock"
    }

    /** The confirming toast, e.g. "Logged bedtime at 23:42." */
    fun confirmation(): String {
        val clock = formatShortTime(tsMs / 1000L, zoneId = null)
        val what = if (type == SleepMarkType.BEDTIME) "bedtime" else "wake-up"
        return "Logged $what at $clock."
    }

    companion object {
        /** The metric-series key all sleep marks share. Identical to Swift. */
        const val SERIES_KEY = "sleep_mark"

        /** Capture a mark at the current instant. `Clock.System.now().toEpochMilliseconds()` replaces
         *  `System.currentTimeMillis()` (`java.lang.System`, JVM-only, not available in commonMain) -
         *  the kotlinx-datetime 0.8.0 convention this repo's Phase 2a tasks follow (task-3-report.md);
         *  a second blocker this file's original PHASE2 tag didn't name, found while hoisting. */
        fun now(type: SleepMarkType): SleepMark = SleepMark(type, Clock.System.now().toEpochMilliseconds())

        /** Capture a mark at the current instant for a NON-UI caller (the double-tap automation), which
         *  has no bedtime/wake picker. A single physical double-tap can't tell us which boundary the user
         *  means, so default to BEDTIME — the boundary a double-tap-to-sleep gesture most naturally marks.
         *  Additive convenience over [now]; the UI's two-button card keeps choosing the type explicitly. */
        fun nowDefault(): SleepMark = now(SleepMarkType.BEDTIME)

        /** Reconstruct a mark from a persisted row — the round-trip read-back. The row carries no
         *  sub-day time, so the instant resolves to that day's LOCAL midnight; the type is exact.
         *  Returns null for a row that isn't a sleep-mark or whose day won't parse. */
        fun fromRow(row: MetricSeriesRow): SleepMark? {
            if (row.key != SERIES_KEY) return null
            val date = runCatching { LocalDate.parse(row.day) }.getOrNull() ?: return null
            val tsMs = date.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            return SleepMark(SleepMarkType.fromSeriesValue(row.value), tsMs)
        }
    }
}
