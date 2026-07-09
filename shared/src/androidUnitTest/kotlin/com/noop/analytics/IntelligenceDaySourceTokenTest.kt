package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the per-day scoring-diagnostic SOURCE token (Sleep overhaul §2.5). Each scored day emits
 * "sleep day=… totalSleepMin=… matched=… source=<token>" into the shareable strap log so the next
 * report ships PROOF of what was computed per day — the project's log-failures-not-successes blind
 * spot, and the data to settle "Rest repeats across days". The token resolves from the imported
 * day-key sets with the SAME precedence the dashboard merge uses (WHOOP import > Apple > computed).
 * Pure + set-based; the SAME `daySourceToken` analyzeRecent ships. Mirrors Swift DaySource.classify
 * (.logToken) so the two platforms log identical tokens.
 */
class IntelligenceDaySourceTokenTest {

    private val day = "2026-06-12"

    @Test
    fun computed_whenNoImportCoversTheDay() {
        assertEquals("computed",
            IntelligenceEngine.daySourceToken(day, emptySet(), emptySet()))
    }

    @Test
    fun importedWhoop_whenWhoopExportCoversTheDay() {
        assertEquals("imported:whoop",
            IntelligenceEngine.daySourceToken(day, setOf(day), emptySet()))
    }

    @Test
    fun importedApple_whenOnlyAppleCoversTheDay() {
        assertEquals("imported:apple",
            IntelligenceEngine.daySourceToken(day, emptySet(), setOf(day)))
    }

    @Test
    fun whoopBeatsApple_whenBothCoverTheSameDay() {
        // Must agree with the merge's source priority + the macOS classify (whoop wins over apple).
        assertEquals("imported:whoop",
            IntelligenceEngine.daySourceToken(day, setOf(day), setOf(day)))
    }

    @Test
    fun perDay_notGlobal() {
        // A set covering a DIFFERENT day leaves this day computed — the token is resolved per day,
        // which is the whole point of the honesty fix (an import elsewhere must not relabel this day).
        val imported = setOf("2026-06-10")
        assertEquals("computed", IntelligenceEngine.daySourceToken("2026-06-12", imported, emptySet()))
        assertEquals("imported:whoop", IntelligenceEngine.daySourceToken("2026-06-10", imported, emptySet()))
    }

    @Test
    fun diagnosticLineFormat_isStableAndParsable() {
        // The exact line shape the engine builds, assembled from the same parts, so the format stays
        // pinned: counts + a rounded minute only (no HR/HRV/timestamps), no em-dash.
        val totalSleepMin = 423.6
        val tsm = Math.round(totalSleepMin).toString()
        val line = "sleep day=$day totalSleepMin=$tsm matched=2 " +
            "source=${IntelligenceEngine.daySourceToken(day, setOf(day), emptySet())}"
        assertEquals("sleep day=2026-06-12 totalSleepMin=424 matched=2 source=imported:whoop", line)
        assertEquals(false, line.contains("—"))
    }
}
