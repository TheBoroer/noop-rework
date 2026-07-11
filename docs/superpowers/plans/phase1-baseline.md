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

## Release-gate verification (2026-07-09, post-merge)

User-performed device pass on the fullDebug build (com.noop.whoop.debug):
- Restored a real v8.5.2 staging backup (119 MB zip, 604 MB sqlite) via in-app restore: SUCCESS
- Sleep history renders with full data: PASS
- Devices screen renders: PASS

This closes the pending-manual items for the upstream-to-fork restore direction on real data.
Still optional: fork-to-upstream direction (export from this build, restore in stock noop).
The backup fixture is archived locally at NOOP/backups/noop-backup-2026-07-09.noopbak.zip.

---

## Phase 2a close-out (Task 9)

Date: 2026-07-10
Final code commit: `0e60b9f2` (Task 8, "feat: multiplatform backup restore engine, synthetic v17
fixture restores in ios simulator test"). Task 9 is docs-only, no code changes; it commits on top
of this sha. Branch: `phase2a-shared-groundwork` (12 commits (10 code + 2 docs) on top of Phase 1
baseline `23a5c59e`).

Phase 2a moved the multiplatform dependency set (kotlinx-datetime, kotlinx-serialization,
atomicfu, okio), a shared LRU cache, a locale-independent `toFixed` formatter, and a
locale-aware short-time formatter into `commonMain`, then used them to hoist `WhoopDatabase`,
`WhoopRepository`, `DeviceRegistry`, the `SleepStager` subtree, and most of `analytics` out of
`androidMain`, and added a multiplatform `BackupRestore` engine proven against a synthetic backup
fixture restoring inside the iOS simulator.

### File counts per source set

`find shared/src/<sourceSet> -name "*.kt" | wc -l`:

| Source set | Files | Package breakdown |
|---|---|---|
| `commonMain` | 114 (was 76) | `analytics` 73, `data` 15, `protocol` 14, `oura` 6, `util` 5, root (`SharedSmoke.kt`) 1 |
| `androidMain` | 33 (was 59) | `ingest` 13, `analytics` 9, `data` 6, `oura` 3, `update` 1, `util` 1 |
| `commonTest` | 89 (was 39) | `analytics` 50, `protocol` 19, `data` 12, `util` 4, `oura` 3, root (`SharedSmokeTest.kt`) 1 |
| `androidUnitTest` | 87 (was 124) | `analytics` 50, `ingest` 16, `data` 14, `oura` 3, `util` 2, `protocol` 1, `update` 1 |
| `iosMain` | 3 (new) | `data` 2 (`WhoopDatabase.ios.kt`, `SqliteQuickCheck.ios.kt`), `util` 1 (`LocalTimeFormat.ios.kt`) |
| `iosTest` | 2 (new) | `WhoopDatabaseSmokeTest.kt` (Room open + round-trip smoke test), `BackupTestEnv.ios.kt` (iOS actual test-support helper backing the commonTest `BackupRestoreTest`, which restores the synthetic fixture) |

`protocol` is now entirely in `commonMain` (zero files left in `androidMain`): its last two
holdouts named in the Phase 1 handoffs above, `AlarmPayload.kt` (`java.time`) and
`HistoricalStreams.kt` (`org.json` via `StreamPersistence.encodePayload`), hoisted in Task 3
(kotlinx-datetime adoption, `f509430d`) and Task 4 (kotlinx-serialization adoption, `bca5c6aa`)
respectively, each exactly as its Phase 1 blocker predicted. `ingest` (13 files) and `update`
(1 file) remain entirely `androidMain` by design, same as the Phase 1 baseline: they are
zip/`File`/`HttpURLConnection`/`androidx.health.connect` I/O with no multiplatform equivalent in
scope for 2a, not an in-progress hoist backlog item.

### Remaining `PHASE2: hoist` inventory

`/usr/bin/grep -rl "PHASE2: hoist" shared/src/androidMain | wc -l`: **14 files** (down from 42 at
the Phase 1 baseline), all under `shared/src/androidMain/kotlin/com/noop/`. Exact list with each
file's own tag reason:

| File | Reason |
|---|---|
| `analytics/NapPrefs.kt` | `android.content.Context` dependency needs a multiplatform settings/preferences abstraction |
| `analytics/Baselines.kt` | `android.content.SharedPreferences.Editor` + `java.time.LocalDate`/`ZoneOffset` usage |
| `analytics/AnalyticsEngineDay.kt` | blocked on Baselines: `analyzeDay` orchestrates over `Baselines.deviation` and related members |
| `analytics/IntelligenceEngine.kt` | blocked on Baselines: folds baselines via `Baselines.metricCfg`/related members |
| `analytics/VitalBands.kt` | blocked on Baselines: `band()` calls `Baselines.foldHistory`/`Baselines.deviation` |
| `analytics/RecoveryScorerTrace.kt` | transitively blocked via `Baselines.kt`'s `SharedPreferences` + `java.time` dependency |
| `analytics/WatchRecovery.kt` | transitively blocked via `Baselines.kt`'s `SharedPreferences` + `java.time` usage |
| `analytics/WorkoutSport.kt` | transitively blocked via `ExerciseTypes.kt`'s `androidx.health.connect` dependency, Android-platform-only |
| `analytics/ImportTrace.kt` | `java.lang.Character.getType` Unicode-category classification has no multiplatform stdlib equivalent |
| `data/DemoSeeder.kt` | `java.time.LocalDate`/`ZoneId` + `org.json` usage on this specific file (kotlinx-datetime and kotlinx-serialization landed elsewhere in 2a but were not applied here) |
| `data/NapStore.kt` | `android.content.Context`/`SharedPreferences` dependency needs a multiplatform settings/preferences abstraction |
| `oura/Auth.kt` | `javax.crypto` `Cipher`/`SecretKeySpec` usage needs a multiplatform crypto library |
| `oura/Commands.kt` | transitively blocked via `Auth.kt`'s `javax.crypto` dependency (kept co-located in the `oura` package) |
| `oura/OuraDriver.kt` | transitively blocked via `Auth.kt`'s `javax.crypto` dependency (kept co-located in the `oura` package) |

Grouped: a six-file **Baselines cluster** (`Baselines.kt` itself plus `AnalyticsEngineDay.kt`,
`IntelligenceEngine.kt`, `VitalBands.kt`, `RecoveryScorerTrace.kt`, `WatchRecovery.kt`, all
directly or transitively blocked on its `SharedPreferences`/`java.time` boundary); a three-file
**oura crypto trio** (`Auth.kt` plus `Commands.kt`/`OuraDriver.kt`); a two-file
**Context/SharedPreferences pair** (`NapPrefs.kt`, `NapStore.kt`); and three standalone files
(`WorkoutSport.kt`, `ImportTrace.kt`, `DemoSeeder.kt`), each with its own distinct blocker.

`StepsEstimateEngineTrace.kt` and `SleepMark.kt` (both named as open questions in the Task
context) are **already in `commonMain`** as of Task 6 / Task 7 respectively and carry no
`PHASE2` tag; they are not part of this inventory. The oura `javax.crypto` trio and
`WorkoutSport.kt`/`ExerciseTypes.kt` are explicitly out of scope for 2a/2b hoisting per this
doc's Phase 2 handoffs above (CommonCrypto expect/actual and health.connect have no
multiplatform equivalent respectively) and are expected to remain `PHASE2`-tagged into Phase 2b
or permanently.

### Test totals per target (clean full verification, this task)

`cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" &&
./gradlew clean :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:assembleDebug
:app:testDemoDebugUnitTest :app:testFullDebugUnitTest --console=plain`

| Target | Result |
|---|---|
| `:shared:testDebugUnitTest` | 1477 tests / 0 failures / 1 skipped (pre-existing, unchanged) |
| `:shared:iosSimulatorArm64Test` | 778 tests / 0 failures |
| `:app:testDemoDebugUnitTest` | 940 tests / 0 failures |
| `:app:testFullDebugUnitTest` | 940 tests / 0 failures |
| `:app:assembleDebug` | SUCCESS, both `app-demo-debug.apk` and `app-full-debug.apk` produced |

Overall: `BUILD SUCCESSFUL in 17s` (125 actionable tasks: 52 executed, 67 from build cache, 6
up-to-date; the cache hits are expected since this task made no code changes prior to the run).
Counts match the counts carried into this task from Task 8 exactly (JVM 1477 / iOS 778 / app
940 x 2), confirming no regression across a clean rebuild.

### Pending-manual items (carried forward, not verified this task)

- **DeviceRegistry transaction runtime path on Android compatibility mode.** From Task 6:
  `useWriterConnection`/`immediateTransaction` is compile-verified and API-verified against the
  resolved `room-runtime` klib, and Room's own common `InvalidationTracker` uses the same bridge
  over the support-database compatibility path, so risk is assessed as low. The actual runtime
  transaction path (demote+promote `setActive`, the 17-table `deleteDeviceData`) has still only
  been exercised via `DeviceRegistryTest`'s fake transactor on JVM and iOS simulator, never
  on-device. Flagged for the next on-device smoke pass (no device interaction was permitted in
  any Phase 2a task).
- The 2026-07-09 release-gate device pass recorded above (real-backup restore, sleep history
  render, Devices screen render) predates and does not cover any Phase 2a `commonMain` work: it
  ran against the pre-Phase-2a `androidMain` data layer. A fresh on-device smoke covering the
  newly-hoisted data layer (Room, repository, `DeviceRegistry`, backup restore) is still open, no
  device interaction having been permitted in any Phase 2a task.

### Privacy note

The Task 8 backup-restore fixture (`shared/src/commonTest/fixtures/noopbak-demo-v17-mini.zip`)
is SYNTHETIC demo data by policy: generated from a fresh `:app:installDemoDebug` + `DemoSeeder`
run plus a deterministic HR/RR generator, never real user data. An earlier draft of that fixture
was built from one real device backup; it was caught before being pushed, replaced with the
synthetic fixture, and rewritten out of local history (the branch was never pushed, so no
real-data blob ever reached a remote). This repository is public: real health data (HR/RR,
sleep, journal entries, or any export derived from a real strap/device) must never be committed,
here or anywhere else in the tree. See `.gitignore`'s "Personal data" section and
`shared/src/commonTest/fixtures/README.md` for the standing rule.

---

## Phase 2b close-out (Task 7)

Date: 2026-07-11
Final code commit: `0717be4f` (Task 6, "docs: SharedBridge x86_64 note updated, Task 6 needed no
packaging change"). Task 7 is docs-only, no code changes; it commits on top of this sha. Branch:
`phase2b-ios-consumes-shared` (base `bba981fb`).

Phase 2a hoisted the data/analytics layers into `commonMain` for Android and iOS-simulator tests.
Phase 2b's job was to make that Kotlin code actually reach the Apple *app* targets: ship `shared`
as a binary framework, wire it into the Swift package graph, and start converting
`Packages/WhoopProtocol`'s Swift protocol implementations into thin wrappers over the equivalent
Kotlin decoders, one file (or coherent file pair) at a time, verified the whole way by the
existing Swift test suite acting as a parity net.

### What ships: Shared.xcframework, and its build/tooling versions

- Three slices: `ios-arm64` (device), `ios-arm64-simulator` (Apple Silicon simulator only), and
  `macos-arm64_x86_64` (universal macOS). There is no `iosX64` (Intel-simulator) slice; the
  Kotlin/Native target set doesn't include it.
- **SKIE 0.10.13** rewrites the generated Objective-C/Swift surface for Swift-friendly bindings
  (suspend funs to `async`, `Flow` to `AsyncSequence`, sealed classes to Swift enums where
  reachable). SKIE's analytics upload is explicitly disabled: this is a privacy-first app, it
  phones home to nobody, including its own build tooling's telemetry.
- **Gradle wrapper pinned to 8.8** (up from the Phase 2a baseline's 8.7), a hard floor for SKIE
  0.10.13 paired with the pinned Kotlin 2.1.21. Reviewed and accepted as a deliberate deviation in
  Task 2.
- Consumed by `Packages/WhoopProtocol` as an SPM `binaryTarget` at a plain relative path
  (`shared/build/XCFrameworks/release/Shared.xcframework`, gitignored, built on demand).
  `Tools/build-shared-xcframework.sh` builds it (auto-detecting a JDK 17/21 `JAVA_HOME`, falling
  back to Android Studio's bundled JBR); CI (`app-build.yml` for the app targets,
  `swift-packages.yml` for `WhoopProtocol` specifically) runs this script before every
  `xcodegen`/`xcodebuild`/`swift build` so a PR can't silently build against a stale or absent
  framework.
- The iOS Simulator **x86_64** slice is excluded project-wide via `project.yml`'s
  `EXCLUDED_ARCHS[sdk=iphonesimulator*]: x86_64` (Task 3 deviation, reviewed sound): the
  Kotlin/Native side ships no matching slice, so an Intel-Mac simulator build would fail to link.
  macOS is different: the shipped app is universal (`ARCHS = x86_64 arm64`), so x86_64 there is a
  live, non-excluded arch, backed by the `macos-arm64_x86_64` slice.
  A constraint discovered while writing the Task 5/6 delegations: when Swift compiles for an
  x86_64 arch, SKIE's Swift-only symbols, Swift-native enum types and Swift-native free
  functions, are invisible inside the package's own compiled module; only the ObjC-bridged
  spellings survive, because the Swift-native names live solely in the arm64 swiftinterface.
  Since the universal macOS build compiles `WhoopProtocol` for x86_64 on every release, every
  delegation in this phase was written to cross the seam using only arch-agnostic spellings (ObjC
  header + apinotes names; Kotlin enums passed only in parameter position via implicit member,
  e.g. `.whoop4`/`.push`), never naming a bare SKIE-Swift enum or free function directly. Net
  effect: the universal macOS build compiles today, and the delegations would also survive
  lifting the iOS-simulator exclusion if an `iosX64` slice were ever added.

### What delegates now (Swift wrapper; Kotlin is the source of truth for the delegated part)

| Swift file | Kotlin twin | What moved |
|---|---|---|
| `VersionCheck.swift` | `update/VersionCompare.kt` | version-string `isNewer` comparison (first delegation, proved the cross-language pattern) |
| `Framing.swift` | `protocol/Crc.kt`, `protocol/Framing.kt` | CRC8 / CRC16-Modbus / CRC32 trio, the stateful `Reassembler`, `puffinCommandFrame` |
| `Whoop5Config.swift` | `protocol/Whoop5Config.kt` | config command bytes, the R22-sequence flag table, `payloadBody`/`deviceConfigBody`/`frame` byte builders |
| `HapticClock.swift` + `LiveSessionHaptics.swift` | `protocol/HapticClock.kt`, `protocol/LiveSessionHaptics.kt` | pulse-timing constants and both clocks' `pulses(...)` encoders |
| `PpgHr.swift` | `protocol/PpgHr.kt` | the canonical (fs=24, window=8s) HR-from-PPG pipeline end to end; the tunable non-canonical lane stays Swift |
| `Streams.swift` | `protocol/Streams.kt` | `skinTempCelsius` + `Whoop4SkinTemp` anchor/slope constants only |
| `HistoricalStreams.swift` | `protocol/HistoricalStreams.kt` | plausibility-window constants (`MIN_PLAUSIBLE_UNIX`/`FUTURE_MARGIN`/`SESSION_RANGE_MARGIN`) and `rejectedHistoricalRecords` |
| `DeviceFamily.swift` | `protocol/DeviceFamily.kt` (`PuffinPacketType`) | `PuffinPacketType` constants only |

### What stays Swift, and why

| Swift file / symbol | Reason it stays Swift |
|---|---|
| `FTMSDecode.swift`, `FitnessSensorDecode.swift` | No Kotlin twin at all; Kotlin decodes these frame types with its own handwritten decoders |
| `Schema.swift`, `Interpreter.swift`, `Values.swift`, `PostHooks.swift` | The schema-driven interpreter (`ParsedFrame = [String: ParsedValue]`); Kotlin's decode path for the equivalent records is handwritten per record type, not schema-driven, so there is no 1:1 twin |
| `PuffinCapture.swift` | Capture/debug tooling, no Kotlin twin |
| `whoop-decode` (CLI executable target) | Thin consumer of the above; nothing to delegate |
| `Streams.swift` row types (`HRSample` ... `SleepStateSample`, `Streams`) and `extractStreams` | Kotlin's `Streams` shape is narrower (no resp/gravity/steps/sleepState/ppgHr/droppedImplausible); the Codable rows are golden-fixture currency consumed per-sample downstream (Phase E / `WhoopStore` / `StrandAnalytics`); `extractStreams` also takes the non-delegated Swift `Interpreter`'s `ParsedFrame` as input |
| `HistoricalStreams.swift`'s `extractHistoricalStreams`, `isPlausibleHistoricalUnix`, `classifyHistoricalMeta`, and `HistoricalMeta.swift` | `extractHistoricalStreams` has a different end-to-end shape (Kotlin returns JSON-string `StreamBatch` events); `isPlausibleHistoricalUnix` has no public Kotlin twin (Kotlin's equivalent is a local closure); `classifyHistoricalMeta`'s sealed-class return has a SKIE Swift enum absent on the x86_64 slice; all three take the non-delegated Swift `ParsedFrame` |
| `DeviceFamily` enum itself | Package-wide public currency (String-raw, `CaseIterable`), used in nearly every family-aware signature including the app layer. Its Kotlin twin's per-family properties are reachable only by naming the Kotlin type, which is SwiftPrivate (`__DeviceFamily`) via SKIE apinotes and exists only in the arm64 swiftinterface; delegating would break the x86_64 slice. Kotlin instances cross the seam only in parameter position via implicit members |
| `verifyFrame`, `FrameCheck`, `frameFromPayload` | Kotlin twins are private or absent; checksums already route through the delegated `Crc` object underneath |

### Findings carried from the Task 6 review, recorded here for Phase 2c

1. **The archive/banking decoder split is now empirical, not structural.** Swift's
   `rejectedHistoricalRecords` now delegates to Kotlin's `decodeHistorical`, while
   `extractHistoricalStreams` still banks rows via the Swift `Interpreter` (schema-driven, not
   delegated). The invariant "the archive is exactly what the extractor drops" used to be
   guaranteed structurally, by both paths sharing one Swift decoder; it now holds only because the
   two decoders were verified to agree on this codebase's fixture set. If the Kotlin and Swift
   historical decoders ever diverge, the failure mode is silent, and the divergence direction would
   silently lose records on iOS/macOS. Watch this in Phase 2c if either decoder changes.
2. **Kotlin lacks WHOOP5 v20/21 historical decode (v18-only)**: Kotlin's `decodeWhoop5Historical`
   only handles v18 records. Today this is a verified non-divergence, not a bug: Swift's own
   decoder likewise emits no `heart_rate`/`gravity_x` for v20/21 records, so the aligned
   rejected-set predicate archives them identically on both platforms. But Android **banks
   nothing** from those records where a future, more complete decoder might. Flagged as a future
   task, not a Phase 2b/2c blocker.

### Deliberate Android behavior change (for release notes)

Task 6 ported Swift's `rejectedHistoricalRecords` predicate into Kotlin
(`shared/src/commonMain/kotlin/com/noop/protocol/HistoricalStreams.kt`) before delegating to it,
because the prior Kotlin predicate (`decodeHistorical == null`) was a strictly weaker rejection
test than Swift's (`unix == nil || (heart_rate == nil && gravity_x == nil)` after decode).
Concretely, Android used to silently keep two categories of record that Swift always archived: a
WHOOP4 v25 record whose gravity fails the ~1g plausibility gate (decodes to timestamp-only, banks
zero real rows), and truncated CRC-valid records with no readable `unix`. The predicate alignment
makes Android's rejected/archived set a **strict superset** of what it archived before: it now
also catches records the old predicate silently accepted without archiving. Nothing previously
banked is removed by this change; it only adds archive coverage. Reviewed safe (Task 6 review, by
opus), with 2 new commonTest cases pinning the aligned behavior
(`v25RecordWithImplausibleGravityIsRejected`, `v25RecordWithPlausibleGravityIsNotRejected`).
Release notes should mention: Android now archives a small number of historical records it
previously discarded without a trace.

### Env facts for posterity

- **Gradle wrapper 8.8 is a hard floor, not a preference.** SKIE 0.10.13 (the SKIE version
  compatible with the pinned Kotlin 2.1.21) requires it; the Phase 2a baseline was 8.7.
- **The iOS Simulator x86_64 slice is excluded project-wide**, via
  `EXCLUDED_ARCHS[sdk=iphonesimulator*]: x86_64` in `project.yml`'s `settings.base`, because the
  Kotlin/Native build ships no `iosX64` target to link against.
- **SKIE Swift symbols are absent on any x86_64 arch**: on arm64 (device, simulator, Apple
  Silicon macOS), SKIE's Swift-native enum types and free functions are visible and usable; an
  x86_64 compile of the package sees only the ObjC-bridged spellings, because the Swift-native
  names live solely in the arm64 swiftinterface. This is not hypothetical: the shipped macOS app
  is universal, so its x86_64 half hits this on every release build. All Phase 2b delegations use
  only arch-agnostic spellings, which is why the universal macOS build (and any future `iosX64`
  slice) compiles without rewriting them.

### Test totals per target (clean full verification, this task)

`cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" &&
./gradlew clean :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :shared:macosArm64Test
:app:assembleDebug :app:testDemoDebugUnitTest :app:testFullDebugUnitTest --console=plain`

| Target | Result |
|---|---|
| `:shared:testDebugUnitTest` (JVM) | 1481 tests / 0 failures / 1 skipped (pre-existing, unchanged) |
| `:shared:iosSimulatorArm64Test` | 782 tests / 0 failures |
| `:shared:macosArm64Test` | 782 tests / 0 failures |
| `:app:testDemoDebugUnitTest` | 940 tests / 0 failures |
| `:app:testFullDebugUnitTest` | 940 tests / 0 failures |
| `:app:assembleDebug` | SUCCESS, both `app-demo-debug.apk` and `app-full-debug.apk` produced |

Overall Gradle result: `BUILD SUCCESSFUL`. Counts match exactly what Task 6 carried into this
task (JVM 1481 / iOS 782 / macOS 782 / app 940 x2), confirming no regression across a clean
rebuild.

Apple-side clean verification (repo root, after the Gradle gate above):

`./Tools/build-shared-xcframework.sh Release && xcodegen generate` then both app targets
(`xcodebuild -scheme NOOPiOS -destination "generic/platform=iOS Simulator"` and
`xcodebuild -scheme Strand -destination "generic/platform=macOS" ARCHS="x86_64 arm64"
ONLY_ACTIVE_ARCH=NO`), then `swift test --package-path Packages/WhoopProtocol`:

| Step | Result |
|---|---|
| `Tools/build-shared-xcframework.sh Release` | SUCCESS |
| `xcodegen generate` | SUCCESS |
| `xcodebuild -scheme NOOPiOS` (generic iOS Simulator) | BUILD SUCCEEDED |
| `xcodebuild -scheme Strand` (macOS universal) | BUILD SUCCEEDED |
| `swift test --package-path Packages/WhoopProtocol` | 242 tests, 1 skipped, 0 failures (unchanged from Task 6 baseline) |

### Deliberately out of scope (Phase 2c+)

- `WhoopStore`/GRDB replacement by shared Room (the big storage cutover; needs its own plan with a
  data-migration design: GRDB `whoop.sqlite` to Room `noop_whoop.db` on Apple platforms).
- `BLEManager.swift` thinning + Kable.
- `StrandAnalytics`/`StrandImport` package delegation.
- oura crypto `expect`/`actual`; the remaining `ingest`/`update` hoists.
- Removing `WhoopProtocol`'s Swift implementations entirely (the wrappers stay until Phase 2c
  proves nothing regresses in the field).

### Self-review notes

- Spec coverage: Phase 2 step 1 of the original spec ("protocol parsing first, delete the
  `WhoopModel.swift` cluster") was served via package-internal delegation rather than deletion;
  deletion is deferred until the wrappers prove out in the field. This is a documented deviation,
  justified by the 74-shared-files recon finding from Phase 2b Task 1, and the macOS pull-forward
  (building `Shared.xcframework` for macOS too, not just iOS) serves the spec's Phase 4 ambition
  early.
- No Swift test or fixture expectation was touched across all of Phase 2b; the Swift suite served
  as the untouched parity net for every delegation.
- No em-dashes in any new content this phase.
- Nothing pushed by any Phase 2b task; the controller pushes after final whole-branch review.
