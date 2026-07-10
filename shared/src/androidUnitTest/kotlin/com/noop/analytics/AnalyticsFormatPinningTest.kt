package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Task 6 (Phase 2a) PINNING tests for the two analytics format/date paths that move to commonMain
 * with WhoopRepository and had no direct coverage. Written BEFORE the port (trivially green against
 * String.format / java.util.TimeZone), kept green unchanged after (toFixed / kotlinx-datetime must
 * reproduce the same bytes). Stays in androidUnitTest deliberately: the oracles are the JVM
 * formatter and java.time themselves.
 */
class AnalyticsFormatPinningTest {

    @Test
    fun skinTempFunnelSummary_meanFormatsLikeJvmPrintf() {
        // Adversarial %.2f cases: exact binary ties, non-representable decimals, integral values.
        for (mean in listOf(36.125, 33.005, 3.345, 36.0, 35.999, 28.004999, 41.995)) {
            val d = AnalyticsEngine.SkinTempFunnelDiagnostic(
                totalSamples = 10, droppedNotWorn = 1, droppedOutOfWindow = 2,
                droppedOutOfRange = 3, kept = 4, minSamples = 300, mean = mean,
            )
            val expected = String.format(Locale.US, "%.2f°C", mean)
            assertTrue(
                "summary '${d.summary}' should contain '(mean=$expected)'",
                d.summary.contains("(mean=$expected)"),
            )
        }
        val absent = AnalyticsEngine.SkinTempFunnelDiagnostic(0, 0, 0, 0, 0, 300, null)
        assertTrue(absent.summary.contains("(mean=absent)"))
    }

    @Test
    fun hydrationDayKey_matchesSystemZoneOracle() {
        // dayKey buckets a unix-second on the device's LOCAL calendar day (offset AT that instant,
        // so DST/zone history matters). Oracle: java.time in the same system default zone.
        for (ts in listOf(0L, 946_684_800L, 1_700_000_000L, 1_710_000_000L, 1_751_500_000L)) {
            val expected = java.time.Instant.ofEpochSecond(ts)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            assertEquals("dayKey($ts)", expected, HydrationStore.dayKey(ts))
        }
    }
}
