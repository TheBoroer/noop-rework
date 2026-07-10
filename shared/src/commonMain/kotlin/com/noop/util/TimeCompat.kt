package com.noop.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Thin kotlinx-datetime helpers for the java.time -> kotlinx-datetime mapping this and later
 * Phase 2a tasks follow (see task-3-report.md). kotlinx-datetime 0.8.0 deprecates its own
 * `Instant`/`Clock` in favor of the Kotlin stdlib's `kotlin.time.Instant`/`kotlin.time.Clock`
 * (still `@ExperimentalTime` on the Kotlin 2.1.21 toolchain this repo pins); kotlinx-datetime
 * itself supplies the `TimeZone`/`LocalDate`/`LocalTime`/`DateTimeUnit` types and the
 * `Instant.toLocalDateTime(zone)` / `LocalDateTime.toInstant(zone)` conversions between them.
 */

/** Local calendar date of an epoch-seconds moment in [zone]. */
@OptIn(ExperimentalTime::class)
fun epochSecondsToLocalDate(epochSeconds: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone).date
