# Phase 2a: Shared-Side Groundwork Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the shared module's data layer genuinely iOS-complete: multiplatform datetime/JSON/LRU/formatting replace the JVM-only APIs, WhoopRepository and WhoopDatabase move to commonMain with an iOS database builder, and a cross-platform backup-restore engine proves itself by restoring a real Android backup inside an iOS simulator test.

**Architecture:** Bottom-up unblocking. Each PHASE2 blocker class gets one multiplatform replacement (kotlinx-datetime for java.time, kotlinx-serialization for org.json, a commonMain LRU for LinkedHashMap accessOrder, a fixed-decimal formatter for Locale.US String.format), then the blocked files re-hoist in dependency order, ending with WhoopRepository, WhoopDatabase (Room KMP with iosMain builder), and the restore engine. No Xcode changes: everything verifies through the existing Gradle iOS-simulator test target. Phase 2b wires Xcode; Phase 2c swaps iOS BLE/UI storage.

**Tech Stack:** Kotlin 2.1.21, Room 2.7.1 KMP (BundledSQLiteDriver on iOS), kotlinx-datetime, kotlinx-serialization-json, kotlinx-atomicfu (locks), okio 3.x (zip read), existing KSP/AGP toolchain.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-09-kmp-cmp-unification-design.md`. Phase 1 close-out: `docs/superpowers/plans/phase1-baseline.md`.
- Package names stay `com.noop.*` verbatim (upstream cherry-pick diffability).
- Backup format byte-compatible with upstream v8.5.2 both directions. `noop-backup.sqlite` MUST stay the first zip entry. The settings.json whitelist keys and JSON shape must match `BackupSettingsCodec` exactly.
- Android app behavior identical after every task; `:app` tests stay baseline-equal (940 per flavor). Shared JVM baseline 1427, iOS sim baseline 355; these GROW, never shrink.
- Algorithm semantics exactly preserved: any datetime/JSON/formatting swap must be proven equivalent by tests, not asserted.
- Before ANY gradle command: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` (default JDK 25 breaks Gradle 8.7). Gradle runs from `android/`. App test tasks are `:app:testDemoDebugUnitTest :app:testFullDebugUnitTest`.
- Device policy: adb install only, never drive device UI. iOS SIMULATORS are exempt: tests may run freely.
- Per-task verification gate: `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:assembleDebug :app:testDemoDebugUnitTest :app:testFullDebugUnitTest`, all green, counts monotonically non-decreasing.
- New dependency versions: use the latest STABLE that resolves (pins below are known-good floors: kotlinx-datetime 0.6.1, kotlinx-serialization 1.7.3, atomicfu 0.26.1, okio 3.9.0); record the chosen version in the commit body. Never use an alpha/beta/RC.
- No em-dashes in any newly authored code, comments, docs, or commit messages.
- Hoist rules (proven in Phase 1): commonMain never references androidMain; a hoisted file that breaks iOS compile gets fixed via these replacements or demoted back with its PHASE2 tag restored; K2 crashes on java.util.LinkedHashMap subclasses, use composition; kotlin.test import swaps as in Phase 1.
- The PHASE2 tag stays accurate: when a file hoists, delete its tag; when a file stays, its tag's stated blocker must still be true. Files legitimately staying androidMain after this plan: NapPrefs (Context), Baselines + RecoveryScorerTrace + WatchRecovery (SharedPreferences boundary), WorkoutSport (health-connect), DemoSeeder + NapStore (Context), DeviceRegistry only if Room KMP transactions prove insufficient.

---

### Task 1: Multiplatform dependencies + MpLru

The LRU is the smallest self-contained blocker: three files (SleepStager, StagerCache, SleepStagerV2) wait on it, and it needs the atomicfu locks dependency, so both land together.

**Files:**
- Modify: `shared/build.gradle.kts` (add commonMain deps: kotlinx-datetime, kotlinx-serialization-json, atomicfu, okio; add serialization Gradle plugin)
- Modify: `android/build.gradle.kts` (register `org.jetbrains.kotlin.plugin.serialization` version 2.1.21 apply false)
- Create: `shared/src/commonMain/kotlin/com/noop/util/MpLru.kt`
- Test: `shared/src/commonTest/kotlin/com/noop/util/MpLruTest.kt`

**Interfaces:**
- Produces: `class MpLru<K : Any, V : Any>(private val capacity: Int)` with `fun get(key: K): V?`, `fun put(key: K, value: V)`, `val size: Int`, thread-safe, access-order eviction identical to `LinkedHashMap(cap, 0.75f, true)` + evict-eldest-on-overflow. Later tasks replace the three cache sites with it.

- [ ] **Step 1: Add dependencies**

In `shared/build.gradle.kts` plugins block add `kotlin("plugin.serialization")`. In `commonMain.dependencies` add:

```kotlin
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:atomicfu:0.26.1")
            implementation("com.squareup.okio:okio:3.9.0")
```

In root `android/build.gradle.kts` plugins block add:

```kotlin
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21" apply false
```

Bump any pin to the latest stable if the pinned version does not resolve; record final versions in the commit body.

- [ ] **Step 2: Write the failing MpLru test**

`shared/src/commonTest/kotlin/com/noop/util/MpLruTest.kt`:

```kotlin
package com.noop.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpLruTest {
    @Test
    fun evictsLeastRecentlyAccessedNotLeastRecentlyInserted() {
        val lru = MpLru<String, Int>(2)
        lru.put("a", 1)
        lru.put("b", 2)
        lru.get("a")            // a is now most recent
        lru.put("c", 3)         // evicts b, not a
        assertEquals(1, lru.get("a"))
        assertNull(lru.get("b"))
        assertEquals(3, lru.get("c"))
        assertEquals(2, lru.size)
    }

    @Test
    fun putExistingKeyRefreshesRecency() {
        val lru = MpLru<String, Int>(2)
        lru.put("a", 1)
        lru.put("b", 2)
        lru.put("a", 10)        // refresh a
        lru.put("c", 3)         // evicts b
        assertEquals(10, lru.get("a"))
        assertNull(lru.get("b"))
    }

    @Test
    fun capacityOneAlwaysKeepsNewest() {
        val lru = MpLru<String, Int>(1)
        lru.put("a", 1)
        lru.put("b", 2)
        assertNull(lru.get("a"))
        assertEquals(2, lru.get("b"))
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd android && ./gradlew :shared:compileDebugKotlinAndroid --console=plain 2>&1 | grep -E "^e:" | head -5`
Expected: unresolved reference `MpLru`.

- [ ] **Step 4: Implement MpLru**

`shared/src/commonMain/kotlin/com/noop/util/MpLru.kt`:

```kotlin
package com.noop.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Thread-safe LRU with access-order eviction, the multiplatform stand-in for
 * java.util.LinkedHashMap(capacity, 0.75f, accessOrder = true) plus
 * evict-eldest-on-overflow. Kotlin's LinkedHashMap keeps insertion order and
 * K2 cannot compile JDK-collection subclasses in a KMP module, so recency is
 * maintained by remove-and-reinsert, which moves the key to the map's tail.
 */
class MpLru<K : Any, V : Any>(private val capacity: Int) : SynchronizedObject() {
    init { require(capacity > 0) { "capacity must be positive" } }

    private val map = LinkedHashMap<K, V>()

    val size: Int get() = synchronized(this) { map.size }

    fun get(key: K): V? = synchronized(this) {
        val value = map.remove(key) ?: return null
        map[key] = value
        value
    }

    fun put(key: K, value: V) {
        synchronized(this) {
            map.remove(key)
            map[key] = value
            if (map.size > capacity) {
                map.remove(map.keys.first())
            }
        }
    }
}
```

- [ ] **Step 5: Run tests on both platforms**

Run: `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test --console=plain 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 3 new tests green on both targets.

- [ ] **Step 6: Run full verification gate and commit**

Run the Global Constraints gate.

```bash
git add -A
git commit -m "feat: multiplatform deps (datetime, serialization, atomicfu, okio) + MpLru cache"
```

---

### Task 2: Fixed-decimal formatter + re-hoist Locale-blocked files

Every real `java.util.Locale` use in the PHASE2 set is `String.format(Locale.US, "%.Nf", x)` (locale-independent decimal text). One commonMain helper replaces them all.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/noop/util/FixedFormat.kt`
- Test: `shared/src/commonTest/kotlin/com/noop/util/FixedFormatTest.kt`
- Move to commonMain (after replacement): `analytics/BatteryEstimator.kt`, `analytics/BatterySocLine.kt`, `analytics/DisplayTrace.kt`, `analytics/RecoveryDrivers.kt`, `analytics/StepsEstimateEngine.kt`, `analytics/StepsEstimateEngineTrace.kt`
- Re-hoist their commonTest-eligible tests per Phase 1 rules.

**Interfaces:**
- Consumes: nothing new.
- Produces: `fun Double.toFixed(decimals: Int): String` in `com.noop.util`, semantics identical to `String.format(Locale.US, "%.<decimals>f", this)` for finite values: HALF_UP rounding away from zero, always `decimals` fraction digits, ASCII dot, leading `-` for negatives, `-0.0` formats as the JVM does (verify with the parity test and match it).

- [ ] **Step 1: Write the JVM parity test first**

`shared/src/androidUnitTest/kotlin/com/noop/util/FixedFormatJvmParityTest.kt` (JVM-only on purpose: it compares against the real String.format):

```kotlin
package com.noop.util

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class FixedFormatJvmParityTest {
    @Test
    fun matchesStringFormatAcrossSweep() {
        val values = buildList {
            add(0.0); add(-0.0); add(0.05); add(-0.05); add(1.25); add(2.675)
            add(99.994); add(99.995); add(1234.5678); add(-1234.5678)
            var v = -10.0
            while (v <= 10.0) { add(v); v += 0.037 }
        }
        for (d in 0..3) {
            for (v in values) {
                assertEquals(
                    String.format(Locale.US, "%.${d}f", v),
                    v.toFixed(d),
                    "value=$v decimals=$d"
                )
            }
        }
    }
}
```

And a small commonTest with explicit expectations (runs on iOS too):

`shared/src/commonTest/kotlin/com/noop/util/FixedFormatTest.kt`:

```kotlin
package com.noop.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FixedFormatTest {
    @Test
    fun basics() {
        assertEquals("1.3", 1.25.toFixed(1))
        assertEquals("0.1", 0.05.toFixed(1))
        assertEquals("-0.1", (-0.05).toFixed(1))
        assertEquals("13", 12.6.toFixed(0))
        assertEquals("5.00", 5.0.toFixed(2))
        assertEquals("99.99", 99.994.toFixed(2))
    }
}
```

Note: `String.format("%.1f", 0.05)` on the JVM is `"0.1"` (HALF_UP on the decimal string, not IEEE half-even). If the JVM parity sweep disagrees with an explicit expectation above, THE JVM IS THE SPEC: fix the expectation and the implementation to match the parity test.

- [ ] **Step 2: Run to verify failure** (unresolved `toFixed`)

- [ ] **Step 3: Implement**

`shared/src/commonMain/kotlin/com/noop/util/FixedFormat.kt`:

```kotlin
package com.noop.util

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Locale-independent fixed-decimal formatting, replacement for
 * String.format(Locale.US, "%.Nf", x) on JVM-only paths. HALF_UP rounding
 * (away from zero on ties), always exactly [decimals] fraction digits.
 * Finite inputs only; the callers this replaces never pass NaN/Inf.
 */
fun Double.toFixed(decimals: Int): String {
    require(decimals in 0..9) { "decimals out of range: $decimals" }
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val negative = this < 0.0 || (this == 0.0 && 1.0 / this < 0.0)
    val scaled = abs(this) * scale
    val units = scaled.roundToLong()
    val whole = units / scale
    val frac = units % scale
    val sign = if (negative && units != 0L) "-" else if (negative) "-" else ""
    return if (decimals == 0) "$sign$whole"
    else "$sign$whole.${frac.toString().padStart(decimals, '0')}"
}
```

CAUTION: `roundToLong` is half-even at exact .5 in IEEE terms only when the scaled double lands exactly on .5, which for decimal inputs is rare but real (e.g. 2.5 at 0 decimals). JVM `%.1f` uses the shortest-decimal representation then HALF_UP. Run the parity sweep FIRST; if it fails on tie cases, switch the implementation to string-based rounding: format via `toString()`, locate the decimal digits, and apply HALF_UP on the decimal string (implement that variant and re-run until the sweep passes). The parity test is the contract; the code block above is the starting point, not the answer.

- [ ] **Step 4: Parity sweep green on JVM, basics green on both platforms**

Run: `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test --console=plain 2>&1 | tail -5`

- [ ] **Step 5: Replace call sites and hoist the six files**

In each of the six files listed above: replace `String.format(Locale.US, "%.<N>f", expr)` with `expr.toFixed(<N>)` (add `import com.noop.util.toFixed`), delete the Locale import, delete the PHASE2 tag line, `git mv` to the commonMain mirror path. StepsEstimateEngineTrace and any transitive-only file needs only the tag removal + move.

- [ ] **Step 6: Compile loop, hoist eligible tests, full gate, commit**

Per Phase 1 hoist rules. Expected iOS test count rises.

```bash
git add -A
git commit -m "feat: multiplatform toFixed formatter, re-hoist six Locale-blocked analytics files"
```

---

### Task 3: kotlinx-datetime adoption for the java.time-only files

**Files:**
- Create: `shared/src/commonMain/kotlin/com/noop/util/TimeCompat.kt` (thin helpers where the mapping is noisy)
- Modify + move to commonMain: `analytics/CyclePhaseEngine.kt`, `analytics/LabBookProjection.kt`, `analytics/VitalBands.kt`, `analytics/SleepEditGuard.kt`, `analytics/HydrationStore.kt`, `data/MoodStore.kt`, `protocol/AlarmPayload.kt`, plus transitive-only `analytics/V5HealthSignals.kt`
- Stays androidMain this task: `analytics/ConnectionReadout.kt`, `analytics/SleepMark.kt` (SimpleDateFormat/DateFormat locale-sensitive DISPLAY formatting; they move in Task 7 with an expect/actual)
- Test: hoist the moved files' tests; add `shared/src/commonTest/kotlin/com/noop/util/TimeCompatTest.kt`

**Interfaces:**
- Consumes: kotlinx-datetime from Task 1.
- Produces: the mapping convention every later task follows:
  - `java.time.Instant.ofEpochSecond(s)` -> `kotlinx.datetime.Instant.fromEpochSeconds(s)`
  - `java.time.LocalDate.parse(s)` -> `kotlinx.datetime.LocalDate.parse(s)` (both ISO-8601)
  - `LocalDate.plusDays(n)` -> `date.plus(n, DateTimeUnit.DAY)`
  - `ZoneId.systemDefault()` -> `TimeZone.currentSystemDefault()`
  - `instant.atZone(zone).toLocalDate()` -> `instant.toLocalDateTime(zone).date`
  - `java.time.LocalTime.of(h, m)` -> `kotlinx.datetime.LocalTime(h, m)`
  - `TimeZone.getDefault()` (java.util) -> `kotlinx.datetime.TimeZone.currentSystemDefault()`
  - In `TimeCompat.kt` define only what is used more than twice, e.g.:

```kotlin
package com.noop.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Local calendar date of an epoch-seconds moment in [zone]. */
fun epochSecondsToLocalDate(epochSeconds: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone).date
```

- [ ] **Step 1: Write TimeCompatTest with cross-platform edge cases**

```kotlin
package com.noop.util

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeCompatTest {
    @Test
    fun epochToLocalDateAroundMidnightUtc() {
        val utc = TimeZone.UTC
        assertEquals("2026-07-08", epochSecondsToLocalDate(1783123199, utc).toString())  // 23:59:59 UTC
        assertEquals("2026-07-09", epochSecondsToLocalDate(1783123200, utc).toString())  // 00:00:00 UTC
    }

    @Test
    fun negativeEpochBeforeUnixZero() {
        assertEquals("1969-12-31", epochSecondsToLocalDate(-1, TimeZone.UTC).toString())
    }
}
```

(Verify the epoch constants with `date -u -r 1783123200` before committing; fix the constants if the arithmetic is off, the intent is the day boundary in July 2026.)

- [ ] **Step 2: Failing run, implement TimeCompat, both-platform green**

- [ ] **Step 3: Convert and hoist the eight files, one at a time**

For each file: apply the mapping table, delete the PHASE2 tag, `git mv` to commonMain, compile both targets, run that file's tests. Behavior equivalence rule: any existing test asserting date math must pass UNCHANGED; if a file has no test covering its date logic, add one minimal commonTest case pinning current behavior BEFORE converting (write it against the androidMain version, watch it pass, convert, watch it still pass).

- [ ] **Step 4: Full gate, commit**

```bash
git add -A
git commit -m "feat: kotlinx-datetime adoption, re-hoist eight java.time-blocked files"
```

---

### Task 4: org.json to kotlinx-serialization for persisted payloads

Highest-care task: `StreamPersistence.encodePayload` output is PERSISTED in the database. The port must produce output that old readers parse identically, and must parse old stored strings.

**Files:**
- Modify + move to commonMain: `data/StreamPersistence.kt`, `analytics/SleepStageTotals.kt`, `analytics/SleepWindowReclip.kt`, then transitive `protocol/HistoricalStreams.kt` (also needs its `System.currentTimeMillis()` swapped to `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()`)
- Test: `shared/src/commonTest/kotlin/com/noop/data/StreamPersistenceGoldenTest.kt` plus hoisted existing tests

**Interfaces:**
- Consumes: kotlinx-serialization-json (Task 1).
- Produces: same public functions on the moved files (signatures unchanged). Convention for org.json ports: build `kotlinx.serialization.json.JsonObject` via `buildJsonObject { }`, read via `Json.parseToJsonElement(s).jsonObject`; `JSONObject.quote(s)` becomes `Json.encodeToString(JsonPrimitive(s))`.

- [ ] **Step 1: Golden tests BEFORE the port**

While the files are still androidMain, capture goldens: for each public encode function, feed 3 representative inputs (typical, empty, unicode + quotes in strings) and hard-code the CURRENT output strings into `StreamPersistenceGoldenTest` as expected values (write the test in androidUnitTest first to harvest outputs, then copy the literals into the commonTest file). Also capture one decode golden: a stored-format string parsed to expected values.

Key ordering caution: org.json's JSONObject does not guarantee key order; kotlinx preserves insertion order. If the current encoder emits keys whose order varies, the golden must compare PARSED structures, not raw strings, for those functions; raw-string equality only where the current implementation already emits deterministic order (single-key or array-only payloads). Decide per function while harvesting and document the choice in the test.

- [ ] **Step 2: Port each file, goldens stay green, hoist**

Same per-file loop as Task 3. Delete tags, `git mv`, both-platform compile + tests after each file.

- [ ] **Step 3: Full gate, commit**

```bash
git add -A
git commit -m "feat: kotlinx-serialization replaces org.json on persisted payload paths, four files re-hoisted"
```

---

### Task 5: WhoopDatabase to commonMain with iOS builder

**Files:**
- Modify + move to commonMain: `shared/src/androidMain/kotlin/com/noop/data/WhoopDatabase.kt` (class + migrations)
- Create: `shared/src/androidMain/kotlin/com/noop/data/WhoopDatabase.android.kt` (Android builder: Context, existing file path, CorruptionPreservingOpenHelperFactory wiring if retained by driver choice)
- Create: `shared/src/iosMain/kotlin/com/noop/data/WhoopDatabase.ios.kt` (iOS builder)
- Modify: `shared/build.gradle.kts` (add `androidx.sqlite:sqlite-bundled` to commonMain, add `ksp` configurations for iOS targets)
- Test: `shared/src/commonTest/kotlin/com/noop/data/WhoopDatabaseSmokeTest.kt`

**Interfaces:**
- Consumes: Room 2.7.1 KMP.
- Produces: `expect fun whoopDatabaseBuilder(): RoomDatabase.Builder<WhoopDatabase>` pattern:
  - commonMain: `@Database(...) @ConstructedBy(WhoopDatabaseConstructor::class) abstract class WhoopDatabase : RoomDatabase { ... }` plus `expect object WhoopDatabaseConstructor : RoomDatabaseConstructor<WhoopDatabase>`
  - androidMain keeps `WhoopDatabase.get(context)` factory with the SAME database file path and open-helper semantics as today (app code unchanged)
  - iosMain: `fun whoopDatabase(path: String): WhoopDatabase` using `Room.databaseBuilder<WhoopDatabase>(name = path)` + `BundledSQLiteDriver` + `Dispatchers.IO` query context
- Task 6 (repository) and Task 8 (restore engine) build on these.

- [ ] **Step 1: Add KSP for iOS targets and the bundled driver**

In `shared/build.gradle.kts`:

```kotlin
        commonMain.dependencies {
            // existing deps stay
            implementation("androidx.sqlite:sqlite-bundled:2.5.1")
        }
```

and at the bottom alongside `kspAndroid`:

```kotlin
    add("kspIosArm64", "androidx.room:room-compiler:2.7.1")
    add("kspIosSimulatorArm64", "androidx.room:room-compiler:2.7.1")
```

(sqlite-bundled version: match what androidx.room 2.7.1 depends on; check with `./gradlew :shared:dependencies --configuration debugRuntimeClasspath | grep sqlite` and pin that.)

- [ ] **Step 2: Move the @Database class to commonMain**

Schema constraint is absolute: `version = 17`, `exportSchema = false`, entity list, and every `@Database`/`@Entity` annotation move UNCHANGED. Add `@ConstructedBy(WhoopDatabaseConstructor::class)` and declare:

```kotlin
expect object WhoopDatabaseConstructor : RoomDatabaseConstructor<WhoopDatabase> {
    override fun initialize(): WhoopDatabase
}
```

with `actual object WhoopDatabaseConstructor` in androidMain and iosMain (Room generates the bodies; the actual is `actual object WhoopDatabaseConstructor : RoomDatabaseConstructor<WhoopDatabase>`).

Migrations: convert each `Migration(from, to) { database: SupportSQLiteDatabase -> ... }` to the KMP form `object : Migration(from, to) { override fun migrate(connection: SQLiteConnection) { connection.execSQL("...") } }`, SQL strings byte-identical. If any migration uses cursor reads beyond execSQL, port with `connection.prepare(...).use { stmt -> ... }`. Count the migrations first (`/usr/bin/grep -c "Migration(" WhoopDatabase.kt`) and list each in your report.

- [ ] **Step 3: Platform builders**

androidMain `WhoopDatabase.android.kt`: preserve today's `get(context)` singleton exactly (same db file name via `context.getDatabasePath`, same factory/driver behavior). If the existing open path used `CorruptionPreservingOpenHelperFactory`, keep it by staying on the support-SQLite path for Android (Room KMP allows per-platform driver choice; Android may keep the framework driver so behavior is untouched).

iosMain `WhoopDatabase.ios.kt`:

```kotlin
package com.noop.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

fun whoopDatabase(path: String): WhoopDatabase =
    Room.databaseBuilder<WhoopDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
```

- [ ] **Step 4: Smoke test in commonTest**

```kotlin
package com.noop.data

import kotlin.test.Test
import kotlin.test.assertTrue

expect fun testDatabase(): WhoopDatabase

class WhoopDatabaseSmokeTest {
    @Test
    fun opensAndQueries() {
        val db = testDatabase()
        // any trivially safe DAO read; adjust to a real DAO method name found in WhoopDao
        assertTrue(db.whoopDao().let { true })
        db.close()
    }
}
```

With `actual fun testDatabase()` in androidUnitTest (in-memory via Robolectric-free builder if available, else instrumentation-independent file db in build dir) and iosTest (temp path under `NSTemporaryDirectory()`). Replace the placeholder assertion with a genuine round-trip: insert one row via a real DAO insert and read it back; use actual entity/DAO names from `WhoopDao.kt` (the implementer reads that file and writes a real insert+query pair).

- [ ] **Step 5: Full gate (this is the task most likely to fight; iterate), commit**

```bash
git add -A
git commit -m "feat: WhoopDatabase to commonMain, Room KMP builders for android and ios"
```

---

### Task 6: WhoopRepository hoist + transitive re-hoists

**Files:**
- Modify + move to commonMain: `data/WhoopRepository.kt` (1624 lines; Context factory stays behind)
- Create: `shared/src/androidMain/kotlin/com/noop/data/WhoopRepositoryFactory.kt` (the one-line `from(context)` factory)
- Modify + move: `data/DeviceRegistry.kt` (swap `androidx.room.withTransaction` to Room KMP `useWriterConnection { it.immediateTransaction { ... } }` or `database.withTransaction`-equivalent from room-runtime KMP; verify the exact 2.7.1 API name in the room-runtime common artifact before writing)
- Then re-hoist transitively unblocked files: `analytics/IntelligenceEngine.kt`, `analytics/SleepStageHealer.kt`, `analytics/AnalyticsEngine.kt` (+ `RecoveryForecast.kt`, `SleepDebt.kt`), `analytics/ReadinessEngine.kt` (LRU from Task 1 + toFixed from Task 2), `data/StreamPersistence`-dependents already done in Task 4
- Tests hoist with their files.

**Interfaces:**
- Consumes: Tasks 1-5 (all four replacement conventions + commonMain WhoopDatabase).
- Produces: `WhoopRepository(db: WhoopDatabase)` constructible in commonMain; `WhoopRepository.from(context)` unchanged for app callers (now in the factory file). Every remaining TASK-8-era doc reference in commonMain becomes a real code reference where needed.

- [ ] **Step 1: Split the factory out, port the file's JVM APIs**

Apply the Task 2/3/4 conventions across WhoopRepository.kt: `java.time` per mapping table, `Calendar`/`TimeZone` via kotlinx-datetime, `Locale` via toFixed, `org.json` via serialization convention, `System.currentTimeMillis()` via `Clock.System.now().toEpochMilliseconds()`. Any date arithmetic without existing test coverage gets a pinning test BEFORE conversion (same rule as Task 3). This is the longest single-file port of the phase; work top-down, compile after each region.

- [ ] **Step 2: Hoist, then re-hoist the transitive queue**

Same loop as Phase 1 Task 8: move, compile both targets, demote only on a genuinely new blocker (report each), delete tags.

- [ ] **Step 3: Full gate, commit**

```bash
git add -A
git commit -m "feat: WhoopRepository and DeviceRegistry to commonMain, transitive analytics re-hoists"
```

---

### Task 7: Locale-display expect/actual + remaining display files

**Files:**
- Create: `shared/src/commonMain/kotlin/com/noop/util/LocalTimeFormat.kt` (`expect fun formatShortTime(epochSeconds: Long, zoneId: String?): String`)
- Create: `shared/src/androidMain/kotlin/com/noop/util/LocalTimeFormat.android.kt` (actual via existing DateFormat.getTimeInstance path, behavior identical to today's SleepMark output)
- Create: `shared/src/iosMain/kotlin/com/noop/util/LocalTimeFormat.ios.kt` (actual via NSDateFormatter, timeStyle Short)
- Modify + move to commonMain: `analytics/ConnectionReadout.kt`, `analytics/SleepMark.kt`
- Test: commonTest asserting format STRUCTURE (contains a digit and a separator) + androidUnitTest asserting exact today-parity for fixed locales.

**Interfaces:**
- Consumes: Task 3 conventions.
- Produces: `formatShortTime` used by any future display code needing locale-aware time strings.

- [ ] **Step 1: Pin current SleepMark/ConnectionReadout output with androidUnitTest cases (fixed TimeZone + Locale.US) before touching them**
- [ ] **Step 2: expect/actual implementation, port both files, hoist, tests green both platforms**
- [ ] **Step 3: Full gate, commit**

```bash
git add -A
git commit -m "feat: locale-aware short-time expect/actual, last display-blocked analytics files hoisted"
```

---

### Task 8: Cross-platform backup restore engine + iOS simulator restore test

The payoff task: an iOS simulator test restores a real Android-produced `.noopbak.zip` through shared code into shared Room and reads it.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/noop/data/BackupRestore.kt` (restore engine core)
- Create: `shared/src/commonMain/kotlin/com/noop/data/BackupSettingsCodecKmp.kt` (settings.json read/apply hooks; WRITE side stays app-side this phase)
- Create: `shared/src/androidMain/kotlin/com/noop/data/SqliteQuickCheck.android.kt` and `shared/src/iosMain/kotlin/com/noop/data/SqliteQuickCheck.ios.kt` (`expect fun sqliteQuickCheck(path: String): String?` returning null when ok, first failure line otherwise; Android: android.database.sqlite readonly open + PRAGMA; iOS: BundledSQLiteDriver connection + PRAGMA)
- Create: `Tools/make-backup-fixture.sh` (trims a full backup into a committed test fixture)
- Create: `shared/src/commonTest/fixtures/noopbak-v8.5.2-mini.zip` (generated by the script from `/Users/boro/Projects/opensource/NOOP/backups/noop-backup-2026-07-09.noopbak.zip`; target under 5 MB)
- Modify: `shared/build.gradle.kts` (pass fixture dir to both test targets via environment)
- Test: `shared/src/commonTest/kotlin/com/noop/data/BackupRestoreTest.kt`
- Modify: `android/app/src/main/java/com/noop/data/DataBackup.kt` (delegate its stage/validate internals to the shared engine, keeping its public API and behavior; export/writeBackupZip stays as-is)

**Interfaces:**
- Consumes: okio (Task 1), WhoopDatabase iOS builder (Task 5).
- Produces:

```kotlin
package com.noop.data

import okio.FileSystem
import okio.Path

/** Multiplatform core of .noopbak restore: stage, validate, swap. Mirrors DataBackup's android flow. */
object BackupRestore {
    enum class StageResult { OK, CANNOT_OPEN, NO_DB_IN_ZIP, NOT_A_BACKUP }
    sealed interface RestoreResult {
        data object NeedsReopen : RestoreResult
        data class Failed(val message: String) : RestoreResult
    }

    /** Extract noop-backup.sqlite (+ optional settings.json) from [archive] into [workDir]. */
    fun stage(fs: FileSystem, archive: Path, workDir: Path): StageResult

    /** Full restore: stage -> sqlite magic check -> quick_check -> snapshot -> swap -> re-check -> rollback on failure. */
    fun restore(fs: FileSystem, archive: Path, liveDbPath: Path, applySettings: (String) -> Unit = {}): RestoreResult
}
```

Zip reading via `FileSystem.openZip(archive)` (okio multiplatform zip filesystem). VERIFY FIRST in a scratch test that `okio.openZip` exists in the common source set of the pinned okio version; if it is JVM-only in that version, fall back to `expect fun unzipEntry(archive: Path, entryName: String, dest: Path): Boolean` with ZipInputStream on Android and POSIX+`NSData`/minizip-free manual central-directory reader NOT attempted: instead use Apple's `NSFileManager`+`NSData` with the `Compression` framework only if trivially available, otherwise STOP and report BLOCKED with findings. Do not hand-roll inflate.

- [ ] **Step 1: Fixture script**

`Tools/make-backup-fixture.sh`:

```bash
#!/usr/bin/env bash
# Trim a full .noopbak.zip into a small committed test fixture.
# Usage: Tools/make-backup-fixture.sh <full-backup.zip> <out.zip> [keep-days]
set -euo pipefail
SRC="$1"; OUT="$2"; KEEP_DAYS="${3:-21}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
unzip -o "$SRC" -d "$WORK" > /dev/null
DB="$WORK/noop-backup.sqlite"
[ -f "$DB" ] || { echo "no noop-backup.sqlite in $SRC"; exit 1; }
CUTOFF=$(( $(date +%s) - KEEP_DAYS*86400 ))
# Trim the biggest time-series tables. Table/column names verified against
# the schema before running; adjust the list if sqlite3 reports a missing table.
for T in $(sqlite3 "$DB" "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata';"); do
  HAS_TS=$(sqlite3 "$DB" "SELECT COUNT(*) FROM pragma_table_info('$T') WHERE name IN ('ts','timestamp','epochSeconds','startTs');")
  if [ "$HAS_TS" -gt 0 ]; then
    COL=$(sqlite3 "$DB" "SELECT name FROM pragma_table_info('$T') WHERE name IN ('ts','timestamp','epochSeconds','startTs') LIMIT 1;")
    sqlite3 "$DB" "DELETE FROM \"$T\" WHERE \"$COL\" < $CUTOFF AND \"$COL\" > 100000;" || true
  fi
done
sqlite3 "$DB" "VACUUM;"
( cd "$WORK" && rm -f out.zip && zip -X out.zip noop-backup.sqlite $( [ -f settings.json ] && echo settings.json ) > /dev/null )
mv "$WORK/out.zip" "$OUT"
echo "fixture: $(du -h "$OUT" | cut -f1) -> $OUT"
```

Run it against the archived real backup; verify the fixture is under 5 MB and the entry order keeps `noop-backup.sqlite` first (`unzip -l` shows it first; `zip` preserves the order given on the command line). If timestamps are in millis not seconds in some tables, the `> 100000` guard avoids nuking id-like columns; sanity-check row counts after trim (`sqlite3 "$DB" "SELECT COUNT(*) FROM <main sleep table>;"` should be > 0).

- [ ] **Step 2: Wire fixture path into both test tasks**

`shared/build.gradle.kts`:

```kotlin
tasks.withType<Test>().configureEach {
    environment("NOOP_FIXTURES", "$projectDir/src/commonTest/fixtures")
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_NOOP_FIXTURES", "$projectDir/src/commonTest/fixtures")
}
```

(Simulator child processes receive env via the `SIMCTL_CHILD_` prefix; the test reads `NOOP_FIXTURES` via `getenv` expect/actual or `platform.posix.getenv` in iosTest and `System.getenv` in androidUnitTest.)

- [ ] **Step 3: Failing restore test**

`shared/src/commonTest/kotlin/com/noop/data/BackupRestoreTest.kt`:

```kotlin
package com.noop.data

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

expect fun fixturesDir(): String
expect fun tempWorkDir(): String
expect fun platformFileSystem(): FileSystem

class BackupRestoreTest {
    @Test
    fun stagesRealBackupFixture() {
        val fs = platformFileSystem()
        val work = tempWorkDir().toPath()
        val result = BackupRestore.stage(fs, (fixturesDir() + "/noopbak-v8.5.2-mini.zip").toPath(), work)
        assertEquals(BackupRestore.StageResult.OK, result)
        assertTrue(fs.exists(work / "import-extract.sqlite"))
    }

    @Test
    fun restoredDatabaseOpensInRoomAndHoldsSleepData() = runTest {
        val fs = platformFileSystem()
        val work = tempWorkDir().toPath()
        val livePath = work / "restored.sqlite"
        val result = BackupRestore.restore(
            fs, (fixturesDir() + "/noopbak-v8.5.2-mini.zip").toPath(), livePath
        )
        assertEquals(BackupRestore.RestoreResult.NeedsReopen, result)
        val db = openWhoopDatabaseAt(livePath.toString())   // expect fun; android + ios actuals from Task 5 builders
        val count = db.whoopDao().let { dao -> daoRowProbe(dao) }  // replace with a real count query; implementer picks a real DAO count method
        assertTrue(count > 0, "restored db should hold rows, got $count")
        db.close()
    }
}
```

Replace `daoRowProbe` with a genuine DAO call found in `WhoopDao.kt` (a `COUNT` query exists for the main streams/sleep tables; the implementer reads the DAO and uses a real method, adding a tiny `@Query("SELECT COUNT(*) FROM ...")` to the DAO ONLY if none exists, table name from the entity annotations).

- [ ] **Step 4: Implement BackupRestore, port DataBackup internals to delegate**

The restore flow mirrors the recon-documented sequence exactly (stage, magic check, quick_check, snapshot to `.import-bak`, swap, delete `-wal`/`-shm` siblings, re-check, rollback on failure). Origin check (`backupOriginOf`) ports too: read `sqlite_master` names via the quick-check connection, reject `grdb_migrations`. Android `DataBackup.importFrom` then delegates staging/validation/swap to `BackupRestore` with `FileSystem.SYSTEM`, keeping its Context/Uri/ContentResolver skin and `WhoopDatabase.close()` + prefs recording exactly as today. App tests must stay green with zero behavior change.

- [ ] **Step 5: Both platforms green: THE milestone**

Run: `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test --console=plain 2>&1 | tail -8`
Expected: restore test passes on iOS simulator: an Android v8.5.2 backup restored via shared code into shared Room on iOS. This is the cross-platform backup proof requested by the user.

- [ ] **Step 6: Full gate, commit**

```bash
git add -A
git commit -m "feat: multiplatform backup restore engine, real v8.5.2 fixture restores in ios simulator test"
```

---

### Task 9: Phase 2a close-out

**Files:**
- Modify: `docs/superpowers/plans/phase1-baseline.md` (append Phase 2a section: final counts per source set, remaining PHASE2 inventory with reasons, test totals)
- Modify: `README.md` (module map row for shared: update the one-line description to mention iOS-ready data layer)
- Modify: `docs/UPSTREAM.md` (note that data/analytics fixes now usually land in commonMain paths)

**Interfaces:** consumes everything above.

- [ ] **Step 1: Regenerate inventory** (`/usr/bin/grep -rl "PHASE2: hoist" shared/src/androidMain | wc -l`), expected remainder roughly 10: NapPrefs, Baselines cluster (3), WorkoutSport, DemoSeeder, NapStore, oura crypto trio (3) unless Task 6 window allowed an AES expect/actual, ImportTrace (Character.getType), plus any Task 5/6 stragglers; each must carry an accurate tag.
- [ ] **Step 2: Docs updates, no em-dashes**
- [ ] **Step 3: Clean full verification** (`clean` + full gate)
- [ ] **Step 4: Commit and push**

```bash
git add -A
git commit -m "docs: phase 2a close-out, shared data layer ios-ready"
git push origin main
```

---

## Deliberately Out of Scope (Phase 2b/2c)

- Xcode/XcodeGen integration, XCFramework build, SKIE: Phase 2b. The iOS app (NOOPiOS target, local SPM packages WhoopProtocol/WhoopStore) does not consume shared yet.
- Kable BLE, iOS UI storage cutover, backup EXPORT writing on iOS (needs a zip writer; ZIPFoundation stays on the Swift side until 2b/2c): later.
- oura javax.crypto expect/actual (CommonCrypto): 2b candidate; stays PHASE2-tagged.
- Ktor update checker: deferred indefinitely; HttpURLConnection works and is androidMain-only by design for now.

## Self-Review Notes

- Spec coverage: Phase 2 spec items covered by 2a+2b+2c split; 2a covers "storage replaced by shared Room" groundwork + spec's backup round-trip test ("fixture backup from current upstream noop to lock compatibility": Task 8 fixture is v8.5.2-real). SKIE/Kable explicitly 2b/2c.
- Placeholder scan: Task 5 Step 4 and Task 8 Step 3 direct the implementer to substitute REAL DAO names read from WhoopDao.kt; flagged explicitly rather than inventing method names that may not exist. FixedFormat implementation explicitly marked as starting point with the parity test as contract.
- Type consistency: MpLru/toFixed/TimeCompat names used consistently across Tasks 1-7; BackupRestore API defined once in Task 8 interfaces.
