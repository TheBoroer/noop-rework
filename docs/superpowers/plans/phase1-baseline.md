# Phase 1 Baseline (pre-migration)

Date: 2026-07-09
Commit: 32b7a38b

- :app:assembleDebug: SUCCESS
- :app:testDemoDebugUnitTest: SUCCESS
- :app:testFullDebugUnitTest: SUCCESS
- Toolchain: Kotlin 1.9.24, AGP 8.5.2, Gradle 8.7, Room 2.6.1, KSP 1.9.24-1.0.20

---

## Phase 1 close-out (Task 9)

Date: 2026-07-09
Final code commit: `9462e393` (Task 8, "Room KMP, entities and DAOs to commonMain, database
builder stays android"). Task 9 is docs-only, no code changes; it commits on top of this sha.
Branch: `phase1-shared-kmp`.

### File counts per source set

`find shared/src/<sourceSet> -name "*.kt" | wc -l`:

| Source set | Files | Package breakdown |
|---|---|---|
| `commonMain` | 76 | `analytics` 50, `protocol` 12, `oura` 6, `data` 7, root (`SharedSmoke.kt`) 1 |
| `androidMain` | 59 | `analytics` 31, `ingest` 13, `data` 9, `oura` 3, `protocol` 2, `update` 1 |
| `commonTest` | 39 | mirrors the `commonMain` production packages |
| `androidUnitTest` | 124 | JUnit/Robolectric/mocking-based tests with no `kotlin.test` equivalent, permanently JVM-only |

`ingest` (13 files) and `update` (1 file) are entirely in `androidMain` by design (zip, `File`,
`java.time`, `HttpURLConnection`, `org.json`), not tagged `PHASE2: hoist` like the rest of the
`androidMain` remainder: they are a deliberate, already-decided platform split, not an
in-progress-hoist backlog item.

### Files tagged `PHASE2: hoist`

`/usr/bin/grep -rl "PHASE2: hoist" shared/src/androidMain | wc -l` (plain `grep` can misbehave on
some of these files, so the count was taken with `/usr/bin/grep` per the task brief):
**42 files**, all under `shared/src/androidMain/kotlin/com/noop/`:

| Package | Count | Recurring blockers |
|---|---|---|
| `analytics` | 31 | `java.time`/`java.util.Calendar,TimeZone,Locale` (kotlinx-datetime replacement), `org.json`, `android.content.SharedPreferences`/`Context`, `java.util.Locale`-based `String.format`, the `SleepStager`/`StagerCache` `synchronized()` + JVM-only `LinkedHashMap(accessOrder)` LRU pair, one Android-platform-only case (`WorkoutSport.kt`, transitively blocked by `ExerciseTypes.kt`'s `androidx.health.connect` dependency) |
| `data` | 6 | `WhoopRepository.kt` (`Context`, `java.time`, `java.util.Calendar/TimeZone/Locale`, `org.json`), `DeviceRegistry.kt` (`androidx.room.withTransaction`, an Android-only 2.6/2.7 extension), `DemoSeeder.kt`/`MoodStore.kt` (`java.time`), `NapStore.kt` (`Context`/`SharedPreferences`), `StreamPersistence.kt` (`org.json`) |
| `oura` | 3 | `javax.crypto` (`Auth.kt`, and `Commands.kt`/`OuraDriver.kt` transitively) |
| `protocol` | 2 | `AlarmPayload.kt` (`java.time`), `HistoricalStreams.kt` (`System.currentTimeMillis` + `StreamPersistence.encodePayload`) |

Full file list is in the git history of this doc / the `PHASE2: hoist` grep above; it is not
repeated file-by-file here to avoid drift from the source of truth (the tags themselves).

### Test totals per target (clean full verification, this task)

`cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" &&
./gradlew clean :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:assembleDebug
:app:testDemoDebugUnitTest :app:testFullDebugUnitTest --console=plain`

| Target | Result |
|---|---|
| `:shared:testDebugUnitTest` | 1427 tests / 0 failures / 1 skipped (pre-existing, baseline-equal) |
| `:shared:iosSimulatorArm64Test` | 355 tests / 0 failures (baseline-equal) |
| `:app:testDemoDebugUnitTest` | 940 tests / 0 failures (baseline-equal) |
| `:app:testFullDebugUnitTest` | 940 tests / 0 failures (baseline-equal) |
| `:app:assembleDebug` | SUCCESS, both `app-demo-debug.apk` and `app-full-debug.apk` produced |

Overall: `BUILD SUCCESSFUL`. Test counts match Task 8's report exactly: app baseline-equal
(940 x 2 flavors), `:shared` baseline-equal (1427 JVM / 355 iOS simulator).

### Pending-manual items

Not verified this task or any prior task, per the no-device-interaction / install-only policy:

- **Sleep history render / existing-database open** on a real device or emulator screen. The Room
  schema-identity check in Task 8 (byte-identical generated `WhoopDatabase_Impl.kt` before/after
  the entity hoist) gives strong indirect confidence, but the actual Sleep screen has not been
  driven.
- **Backup export + restore round-trip** via the Settings screen. Same indirect confidence from
  the schema-identity check; the UI-driven export/import flow itself is unexercised.
- **Task 3's Room 2.6.1 to 2.7.1 migration**, re-checked against real (non-synthetic) user data.
  Task 3 verified the migration path structurally; a real-data pass is still open.

### Phase 2 handoffs

Carried forward from the concerns sections of Tasks 6 through 8, plus this task's own tally:

- **Multiplatform LRU for the sleep-staging caches.** `SleepStager.kt`, `StagerCache.kt`, and
  (transitively) `SleepStagerV2.kt` are blocked on `synchronized()` and the JVM-only
  `LinkedHashMap(capacity, loadFactor, accessOrder = true)` constructor, neither of which resolves
  on Kotlin/Native. Needs a small dedicated task: either `kotlinx-atomicfu`'s `SynchronizedObject`
  plus a hand-rolled access-order LRU, or an equivalent multiplatform cache library.
- **`WhoopRepository` hoist blockers.** `System.currentTimeMillis`, `java.time.LocalDate/Instant/
  ZoneId`, `java.util.Calendar/TimeZone/Locale`, `org.json.JSONArray`, and `android.content.Context`
  on `Companion.from()`, throughout. This is the single largest remaining `androidMain` file and
  blocks its direct dependents (`IntelligenceEngine.kt`, `SleepStageHealer.kt`,
  `protocol/HistoricalStreams.kt` via `StreamPersistence`).
- **kotlinx-datetime adoption.** The single biggest recurring blocker across the 42-file `PHASE2`
  list: `java.time.*` and `java.util.Calendar/TimeZone/Locale` date handling appears in most of
  `analytics` and half of `data`. One deliberate migration pass (rather than per-file one-liners)
  is the efficient path for Phase 2.
- **Ktor for the update checker.** `update/UpdateCheck.kt` uses `java.net.HttpURLConnection` +
  `org.json` directly; it stays `androidMain` by design this phase. Hoisting it to `commonMain`
  (for a future iOS "check for updates") needs a Ktor client + a multiplatform JSON parser
  (`kotlinx.serialization`, replacing `org.json` project-wide would also unblock several `data`/
  `analytics` files above).
- **`PHASE2: hoist` tag inventory** (42 files, see above) is the authoritative Phase 2 backlog.
  Re-run `/usr/bin/grep -rl "PHASE2: hoist" shared/src/androidMain` at the start of Phase 2 planning
  to confirm the count hasn't drifted, and re-verify each file's blocker is still accurate (some
  may resolve as a side effect of the kotlinx-datetime / JSON / LRU work above, without being
  touched directly).
- **`androidx.health.connect` (`WorkoutSport.kt` / `ingest/ExerciseTypes.kt`)** is a distinct
  bucket from the rest: a genuine Android-platform API with no multiplatform equivalent in this
  repo's scope, likely permanent rather than "will port later." Worth flagging separately in Phase
  2 planning so it isn't lumped in with the kotlinx-datetime/JSON items above.
- **`javax.crypto` in `com.noop.oura`** (`Auth.kt` and its two transitive dependents) needs a
  multiplatform crypto library before it can hoist.
