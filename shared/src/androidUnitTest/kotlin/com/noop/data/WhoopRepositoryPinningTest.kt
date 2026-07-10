package com.noop.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Task 6 (Phase 2a) PINNING tests: written against the CURRENT androidMain WhoopRepository BEFORE
 * its java.time/Calendar/org.json code is ported to kotlinx-datetime/kotlinx-serialization, and
 * kept green (unchanged) after the port + hoist to commonMain. Each test pins a date-arithmetic or
 * JSON-decode behaviour that had no direct production-code coverage:
 *
 *  - [freshnessDayKey]: the UTC "today + deltaDays" key behind [WhoopRepository.freshness]'s window
 *    bounds (was java.util.Calendar + String.format(Locale.US)). Oracle: java.time on UTC, computed
 *    before AND after the call so a midnight rollover mid-test cannot flake.
 *  - [bufferDayAfter]: the +1-day read buffer behind the resolver (#614), incl. the leap-day step,
 *    the year rollover, and the unparseable-input pass-through ("9999-99-99" sentinel).
 *  - [sleepEfficiency]: the org.json segment-array read behind a manual nap's seeded efficiency
 *    (#508) - the skip rules (inverted span, missing keys), the wake-only null, and the
 *    unparseable-input null. (ManualNapTest exercises a test-local MIRROR of this function; this
 *    pins the production one.)
 *  - [healImplausibleTimestamps]: the local-zone `minDay` derivation (java.time Instant -> ZoneId ->
 *    LocalDate) and the [minTs, nowSec + FUTURE_MARGIN] bounds, captured off the DAO call arguments
 *    via a recording java.lang.reflect.Proxy (the pure predicate half is already covered by
 *    HistoryHealPredicateTest; the ARGUMENT derivation was not).
 *
 * The DAO is a reflect.Proxy so no Room database is needed (the suite stays Robolectric-free); this
 * file intentionally stays in androidUnitTest (JVM) since the oracles are java.time itself.
 */
class WhoopRepositoryPinningTest {

    /** Records every DAO invocation (name + args, minus the trailing suspend Continuation) and
     *  answers 0 - sufficient for the prune*/
    private class RecordingDao : InvocationHandler {
        val calls = ArrayList<Pair<String, List<Any?>>>()
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val a = (args ?: emptyArray()).filterNot { it is kotlin.coroutines.Continuation<*> }
            calls.add(method.name to a)
            return 0 // every method this test path touches is a suspend fun returning Int
        }
    }

    private fun repoWith(handler: InvocationHandler): WhoopRepository {
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java), handler,
        ) as WhoopDao
        return WhoopRepository(dao)
    }

    @Test
    fun freshnessDayKey_matchesUtcOracle() {
        val repo = repoWith(RecordingDao())
        for (delta in listOf(0, 1, -1, 30, -4000)) {
            val before = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(delta.toLong()).toString()
            val got = repo.freshnessDayKey(delta)
            val after = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(delta.toLong()).toString()
            assertTrue("freshnessDayKey($delta)=$got not in [$before, $after]", got == before || got == after)
        }
    }

    @Test
    fun bufferDayAfter_stepsOneCalendarDay() {
        val repo = repoWith(RecordingDao())
        assertEquals("2024-02-29", repo.bufferDayAfter("2024-02-28")) // leap day
        assertEquals("2024-03-01", repo.bufferDayAfter("2024-02-29"))
        assertEquals("2023-03-01", repo.bufferDayAfter("2023-02-28")) // non-leap
        assertEquals("2025-01-01", repo.bufferDayAfter("2024-12-31")) // year rollover
        assertEquals("2026-07-10", repo.bufferDayAfter("2026-07-09"))
    }

    @Test
    fun bufferDayAfter_unparseableInputPassesThroughVerbatim() {
        val repo = repoWith(RecordingDao())
        assertEquals("9999-99-99", repo.bufferDayAfter("9999-99-99")) // Today's wide-open sentinel
        assertEquals("", repo.bufferDayAfter(""))
        assertEquals("not-a-date", repo.bufferDayAfter("not-a-date"))
    }

    @Test
    fun sleepEfficiency_asleepFractionOfSegmentArray() {
        val repo = repoWith(RecordingDao())
        // 1200s light + 300s wake + 1500s deep: asleep 2700 of 3000 in-bed.
        val json = """[{"end":1200,"stage":"light","start":0},""" +
            """{"end":1500,"stage":"wake","start":1200},""" +
            """{"end":3000,"stage":"deep","start":1500}]"""
        assertEquals(0.9, repo.sleepEfficiency(json)!!, 1e-12)
        // "awake" spelling counts as awake too.
        val json2 = """[{"end":100,"stage":"awake","start":0},{"end":300,"stage":"rem","start":100}]"""
        assertEquals(2.0 / 3.0, repo.sleepEfficiency(json2)!!, 1e-12)
    }

    @Test
    fun sleepEfficiency_skipRulesAndNullCases() {
        val repo = repoWith(RecordingDao())
        // Inverted / degenerate spans are skipped; the remaining valid segment carries the ratio.
        val inverted = """[{"end":100,"stage":"light","start":200},{"end":600,"stage":"rem","start":300}]"""
        assertEquals(1.0, repo.sleepEfficiency(inverted)!!, 1e-12)
        // A segment missing start/end is skipped (org.json optLong default -1); missing stage ("")
        // is not wake, so it counts asleep.
        val missing = """[{"stage":"light"},{"end":50,"start":10}]"""
        assertEquals(1.0, repo.sleepEfficiency(missing)!!, 1e-12)
        // Wake-only, empty, non-array, unparseable, and null all yield null (fallback stays honest).
        assertNull(repo.sleepEfficiency("""[{"end":1800,"stage":"wake","start":0}]"""))
        assertNull(repo.sleepEfficiency("[]"))
        assertNull(repo.sleepEfficiency("""{"a":1}"""))
        assertNull(repo.sleepEfficiency("not json"))
        assertNull(repo.sleepEfficiency(null))
    }

    @Test
    fun healImplausibleTimestamps_derivesLocalMinDayAndBounds() = runTest {
        val handler = RecordingDao()
        val repo = repoWith(handler)
        val nowSec = 1_750_000_000L
        val minTs = com.noop.protocol.MIN_PLAUSIBLE_UNIX
        val deleted = repo.healImplausibleTimestamps(nowSec = nowSec, today = "2026-07-09")
        assertEquals(0, deleted)
        // The computed-daily prune gets (today verbatim, minDay = LOCAL calendar day of minTs).
        val daily = handler.calls.first { it.first == "pruneDailyMetricByDay" }
        assertEquals("2026-07-09", daily.second[0])
        val expectedMinDay = java.time.Instant.ofEpochSecond(minTs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        assertEquals(expectedMinDay, daily.second[1])
        // Every ts-keyed prune gets [minTs, nowSec + FUTURE_MARGIN].
        val maxTs = nowSec + com.noop.protocol.FUTURE_MARGIN
        for (name in listOf(
            "pruneHrByTs", "prunePpgHrByTs", "pruneRrByTs", "pruneSkinTempByTs", "pruneStepByTs",
            "pruneRespByTs", "pruneGravityByTs", "pruneSpo2ByTs", "pruneEventByTs", "pruneBatteryByTs",
            "pruneSleepSessionByTs",
        )) {
            val call = handler.calls.first { it.first == name }
            assertEquals("$name minTs", minTs, call.second[0])
            assertEquals("$name maxTs", maxTs, call.second[1])
        }
    }
}
