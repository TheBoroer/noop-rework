# Phase 2c-1: Apple Storage Cutover to Shared Room Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The WhoopStore actor keeps its public API but stores everything (except the raw outbox and cursors) in the shared Kotlin Room database, existing users' GRDB data migrates via a one-time batched ETL, and Apple backups become Room-format (restorable on Android).

**Architecture:** Same seam strategy as Phase 2b: delegation INSIDE the package. `Packages/WhoopStore` gains the Shared binaryTarget; the actor's method bodies switch from GRDB to Kotlin DAO calls (SKIE async). A Kotlin ETL migrator (appleMain) reads the legacy `whoop.sqlite` with raw SQL over the bundled SQLite driver and batch-copies into Room `noop.db` in the same OpenWhoop directory; WhoopStore's init orchestrates detect-migrate-open with a progress stream. `rawBatch` + `cursors` tables stay on a retained GRDB handle against the legacy file (transient device-local data, never in backups, matching Android), so the shared Room schema and identity hash stay untouched; GRDB removal happens in 2c-2 with the BLE/outbox redesign. The 232 WhoopStore tests are the behavior net: they keep passing against the Room-backed store, except migration/quarantine suites which are rewritten to pin the ETL and the flipped origin logic.

**Tech Stack:** Room 2.7.1 KMP (BundledSQLiteDriver), SKIE 0.10.13 (suspend to async), okio, GRDB 6.29.3 (retained for outbox/cursors only), ZIPFoundation (export zip), existing WhoopDao/WhoopDatabase from shared.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-09-kmp-cmp-unification-design.md`. Recon facts below are binding inputs; re-verify any that sound wrong before relying on them.
- The shared Room schema does NOT change: version stays 17, identity hash `0df28b445fbde09ef5d4b64485b99b1f` untouched. Anything needing a new table stays in the legacy GRDB file this phase.
- WhoopStore public API surface stays source-compatible for all 61 consumer files; app-side changes limited to: (a) the migration progress UI hook, (b) nothing else without a DONE_WITH_CONCERNS report.
- DATA SAFETY IS ABSOLUTE: the ETL never deletes or mutates the legacy `whoop.sqlite`; it is retained read-only as rollback. A failed or interrupted ETL must leave the app able to retry on next launch (idempotent: partial `noop.db` is discarded and rebuilt). Row-count verification per table gates the cutover; on mismatch the store refuses to switch and reports.
- ETL data-shape rules from recon: `ppgHrSample.bpm` REAL to Int rounds via half-up; `stepSample` gains `synced=0` default; `workout.routePolyline` lands NULL; `rawBatch`/`cursors` are NOT copied; Room-only `DismissedWorkout`/`DismissedSleep` stay empty (Apple tombstones remain in UserDefaults this phase).
- Epoch units are seconds everywhere (verified both sides); any Double-to-Long conversion in the ETL must be exact-integer checked, not silently truncated.
- Backups: export zips the Room `noop.db` as entry `noop-backup.sqlite` (entry name unchanged); import accepts Room-origin natively and routes GRDB-origin archives through the ETL. Android side unchanged.
- Concurrency guarantee preserved: dashboard reads must not block behind bulk writes (GRDB DatabasePool WAL behavior, pinned by DatabasePoolConcurrencyTests). The Room-backed equivalent must be demonstrated by an equivalent test before the cutover task completes.
- The DeviceRegistryStore synchronous bypass is redesigned deliberately (Task 6), not papered over with scattered runBlocking.
- Environment: export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" before gradle; gradle from android/; ./Tools/build-shared-xcframework.sh Release after ANY Kotlin change before Swift work; Bash timeout 600000; /usr/bin/grep; no em-dashes in new content; repo public (no secrets, synthetic fixtures only); no push (controller handles PR flow); wait-for-CI lesson applies at merge time.
- Gates per task: swift test --package-path Packages/WhoopStore (232 baseline, adjusted only where a task's brief explicitly rewrites a suite) + swift test --package-path Packages/WhoopProtocol + full gradle gate (:shared:testDebugUnitTest :shared:iosSimulatorArm64Test :shared:macosArm64Test :app:assembleDebug :app:testDemoDebugUnitTest :app:testFullDebugUnitTest; counts non-decreasing from JVM 1481 / iOS 782 / macOS 782 / app 940x2) + xcodegen + both xcodebuilds.

---

### Task 1: WhoopStore gains the Shared framework

**Files:**
- Modify: `Packages/WhoopStore/Package.swift` (binaryTarget + dependency, mirror WhoopProtocol's block exactly)
- Create: `Packages/WhoopStore/Sources/WhoopStore/SharedStoreBridge.swift` (linkage probe + the KotlinByteArray/Data helpers copied from WhoopProtocol's SharedBridge pattern where needed later)
- Test: `Packages/WhoopStore/Tests/WhoopStoreTests/SharedStoreBridgeTests.swift` (probe asserts SharedSmoke.shared.MODULE == "shared")

**Interfaces:**
- Produces: `import Shared` available inside WhoopStore; probe test green; all six swift-packages CI legs still lean/green (WhoopStore leg already builds the framework since the CI fix).

- [ ] Step 1: Package.swift edit (binaryTarget path `../../shared/build/XCFrameworks/release/Shared.xcframework`, add "Shared" to the WhoopStore target deps)
- [ ] Step 2: probe + test, swift test green (233)
- [ ] Step 3: full gates, commit `build: WhoopStore consumes Shared.xcframework`

### Task 2: Kotlin ETL migrator core (GRDB file to Room), fixture-driven

**Files:**
- Create: `shared/src/appleMain/kotlin/com/noop/data/GrdbMigrator.kt`
- Create: `shared/src/appleTest/kotlin/com/noop/data/GrdbMigratorTest.kt`
- Create: `Tools/make-grdb-fixture.sh` (builds a small synthetic GRDB-schema sqlite with sqlite3 CLI: creates the exact GRDB tables per recon's schema list and inserts deterministic synthetic rows: 3 devices, 2 days of 1 Hz hr/rr (~172k rows hr), a handful of sleepSessions/journal/workouts/labMarkers/metricSeries/liveSessions/pairedDevice/dayOwnership, plus grdb_migrations marker table; NO real data)
- Create: `shared/src/commonTest/fixtures/grdb-mini.sqlite` (output of the script, expect under 3 MB)

**Interfaces:**
- Produces:

```kotlin
object GrdbMigrator {
    data class Progress(val table: String, val copied: Long, val totalEstimate: Long)
    sealed interface Result {
        data class Done(val rowCounts: Map<String, Long>) : Result
        data class Failed(val table: String, val message: String) : Result
    }
    /** Copies all user tables from a legacy GRDB whoop.sqlite into the Room db at roomPath.
     *  Batched (10k rows/transaction), resumable-by-restart (truncates Room tables first),
     *  never writes to legacyPath. onProgress fires per batch. */
    fun migrate(legacyPath: String, roomPath: String, onProgress: (Progress) -> Unit = {}): Result
    /** Post-copy verification: per-table SELECT COUNT comparison legacy vs Room (mapped tables only). */
    fun verify(legacyPath: String, roomPath: String): Map<String, Pair<Long, Long>>
}
```

- Implementation reads the legacy file via BundledSQLiteDriver raw connections (read-only open), writes via the Room database's underlying connection in batched transactions using plain INSERT SQL (not DAO objects: this is bulk ETL, object mapping would be needless overhead). Table list + column mappings from the recon matrix (direct for 20 tables; the 3 shape adjustments per Global Constraints; rawBatch/cursors skipped).
- [ ] Step 1: fixture script + fixture (verify with sqlite3: tables, counts, grdb_migrations present)
- [ ] Step 2: failing test: migrate fixture to temp Room db, assert Result.Done, verify() counts equal, spot-assert 5 semantic rows via WhoopDao (an HrSample bpm value, a SleepSession span, a ppgHr rounding case with fractional source bpm, a stepSample synced=0, a workout with NULL routePolyline)
- [ ] Step 3: implement, iterate to green on iOS sim + macOS native tests
- [ ] Step 4: interruption test: run migrate, kill after first table (test hook: onProgress throws once), re-run migrate, verify clean result (idempotency)
- [ ] Step 5: full gates (framework rebuild), commit `feat: GRDB-to-Room ETL migrator with fixture, batched and idempotent`

### Task 3: Store open orchestration + migration progress

**Files:**
- Modify: `Packages/WhoopStore/Sources/WhoopStore/WhoopStore.swift` (init: detect-migrate-open state machine; expose `migrationProgress: AsyncStream<Double>?` and `storageBackend` enum for diagnostics; retain GRDB handle for rawBatch/cursors against the legacy file)
- Modify: `Strand/Collect/StorePaths.swift` (add `roomDatabasePath()` = same OpenWhoop dir, filename `noop.db`, same file-protection treatment on iOS)
- Modify: minimal app hook: the existing launch/loading surface shows migration progress (recon: find the launch gate view; keep the change to a ProgressView bound to the stream; DONE_WITH_CONCERNS if more than ~30 app-side lines needed)
- Test: WhoopStoreTests init-path cases: fresh install (no files) opens Room directly; legacy-only triggers ETL then opens Room; both-present opens Room without ETL; ETL failure falls back to GRDB-backed legacy mode with a diagnostic flag (the store must still WORK on ETL failure: reads/writes hit the old GRDB path so users lose nothing; retry next launch)

**Interfaces:**
- Consumes: GrdbMigrator (Task 2), whoopDatabase(path) (existing appleMain builder).
- Produces: `WhoopStore.init` signature unchanged for callers; internal `enum Backend { room(WhoopDatabase), legacyGrdb(DatabasePool) }`; every actor method in later tasks switches on it (legacy branch = current code, room branch = new). The fallback requirement means GRDB code paths are NOT deleted in this phase, only bypassed when Room is live.

- [ ] Steps: failing init tests, implement state machine, progress stream, gates, commit `feat: WhoopStore opens Room, one-time ETL with progress and legacy fallback`

### Task 4: Streams delegation (write funnel + stream reads)

**Files:**
- Modify: `Packages/WhoopStore/Sources/WhoopStore/StreamStore.swift`, `Reads.swift` (room-branch implementations: insert(_:deviceId:), upsertDevice, hrSamples, hrBuckets, hrFingerprint, rrIntervals, events, batterySamples, spo2Samples, skinTempSamples, stepSamples, respSamples, gravitySamples, latestHRSampleTs, sleepStateSamples, storageStats)
- Kotlin side where a DAO query is missing: add @Query methods to `shared/src/commonMain/kotlin/com/noop/data/WhoopDao.kt` ONLY when no existing method covers the shape (read WhoopDao fully first; Android already queries most of these tables; new queries need mirrored intent, no schema change). Every new DAO method gets a commonTest case.
- Test: existing StreamStore/Reads test suites run against the ROOM backend (test setup flag forces backend); the GRDB legacy branch keeps its coverage via a small pinned subset run in legacy mode.

**Interfaces:**
- Consumes: Backend enum (Task 3), SKIE async DAO calls.
- Produces: the 11 sample tables fully served by Room. Upsert semantics rule: each GRDB ON CONFLICT clause maps to the matching Room @Insert(onConflict)/@Upsert or hand-written @Query upsert; the WhoopStore test for that table is the arbiter, byte-level.

- [ ] Steps: per-method delegation loop with the relevant test suite after each file; storageStats row counts vs GRDB parity test on the ETL fixture; gates; commit

### Task 5: Metrics, sleep, journal, workout, apple, metricSeries, labMarker, liveSession delegation

**Files:**
- Modify: `MetricsCache.swift`, `MetricSeriesStore.swift`, `JournalWorkoutAppleCache.swift`, `LabMarkerStore.swift`, `LiveSessionStore.swift` (room branches)
- Same DAO-gap rule as Task 4. Sleep-edit semantics (applySleepEdit, userEdited, startTsAdjusted, dedup/merge invariants) are the risk center: MetricsCacheTests (27) + SleepMotionStateTests (13) + dedup suites must pass unchanged against Room.

- [ ] Steps: per-file loop, suites after each, gates, commit

### Task 6: Device registry redesign (kill the sync bypass deliberately)

**Files:**
- Modify: `Packages/WhoopStore/Sources/WhoopStore/DeviceRegistryStore.swift`: becomes a thin type whose methods are async and route through the actor (room branch: shared DeviceRegistry/WhoopDao paired-device + dayOwnership queries; legacy branch: existing sync GRDB)
- Modify the 5 construction sites (`AppModel.swift:407`, `BLEManager.swift:814`, `Repository.swift:1449`, `IntelligenceEngine.swift:439`, `AppleDemoSeeder.swift:50`): await the async calls. This IS an app-side change beyond the migration UI: sanctioned here explicitly because the sync bypass cannot survive a suspend-based backend; keep the diff minimal (mechanical await insertion; if a call site is in a synchronous context that cannot await, report DONE_WITH_CONCERNS with the site rather than adding runBlocking).
- Test: DeviceRegistryStore + PairedDevice suites green on Room; registry behavior parity pinned before conversion (write the pinning cases against the sync version first).

- [ ] Steps: pin, convert, call-site sweep, gates, commit

### Task 7: Backup flip (export Room, import both, quarantine rewrite)

**Files:**
- Modify: `Strand/Data/DataBackup.swift`: export zips `noop.db` (entry name `noop-backup.sqlite` unchanged; checkpoint via Room connection PRAGMA wal_checkpoint before zip); import: Room-origin archives stage-swap onto `noop.db` via the shared BackupRestore engine (delegate: it is commonMain and path-based; iOS caller closes/reopens its instances per its documented contract); GRDB-origin archives (grdb_migrations marker) stage to temp then run GrdbMigrator into a fresh Room db then swap: legacy backups remain fully importable forever.
- Modify: `Packages/WhoopStore/Sources/WhoopStore/WhoopStore.swift` quarantine logic: `room_master_table` in `noop.db` is now NATIVE (no quarantine); the ForeignDatabaseQuarantineTests suite is REWRITTEN to pin the new matrix: (grdb file at legacy path = fine legacy), (room file at room path = native), (grdb file at room path = quarantine), (unknown schema anywhere = quarantine). Update BackupSettings snapshot/apply unchanged (same UserDefaults whitelist).
- CROSS-PLATFORM PROOF (the payoff test): commonTest/appleTest case: export-shaped zip built from the ETL-fixture-migrated Room db restores through BackupRestore on the iOS simulator AND the same file passes Android's origin check (assert backupOriginOf equivalent accepts it): an Apple-exported backup is Android-restorable. Note in docs that the Android-side "Mac backups rejected" user message is now reachable only for pre-2c-1 legacy exports.

- [ ] Steps: export flip + tests, import matrix + tests, quarantine rewrite, cross-platform proof, gates, commit

### Task 8: Concurrency + performance gates

**Files:**
- Test: `Packages/WhoopStore/Tests/WhoopStoreTests/RoomConcurrencyTests.swift`: port DatabasePoolConcurrencyTests' guarantee to the Room backend: a bulk insert transaction (50k rows) in flight must not block a concurrent committed-data read beyond a small bound; if BundledSQLiteDriver's default connection pool cannot satisfy it, investigate Room's setQueryCoroutineContext/pool knobs and report findings honestly: this gate DECIDES whether the cutover ships, do not weaken it.
- Create: `Tools/bench-etl.sh` + a large synthetic fixture generated at bench time (not committed): 5M-row hr table via sqlite3; measure GrdbMigrator wall time + peak memory on this Mac; target under 90s for 5M rows (extrapolates to under ~15 min for a heavy multi-year device); tune batch size if missed; record numbers in the report and close-out.

- [ ] Steps: concurrency test (must pass), bench + tuning, gates, commit

### Task 9: Close-out

- Phase appendix in `docs/superpowers/plans/phase1-baseline.md`: backend state machine, ETL numbers, what stays GRDB (rawBatch/cursors) and why, the flipped backup matrix, DeviceRegistry redesign, test-suite disposition (which suites rewritten and why), 2c-2 handoffs (GRDB removal, outbox redesign, BLE thinning, Kable; plus the standing watch list).
- README + docs/UPSTREAM.md updates (storage fixes now land in Kotlin for both platforms).
- Clean full verification, commit. Controller runs PR flow and WAITS FOR CHECKS.

## Deliberately Out of Scope (2c-2 / 2c-3)

- GRDB dependency removal, rawBatch/cursors redesign, BLEManager thinning, Kable, Swift Interpreter/schema decoder retirement, Strand macOS menu-bar polish, watch-list items (bridge-overhead timing lands naturally in Task 8's bench; note results).

## Self-Review Notes

- Spec coverage: spec Phase 2 step 2 (storage replaced by shared Room) is this plan; backup cross-platform restore is the spec's stated payoff, proven in Task 7.
- The recon matrix is embedded as binding constraints rather than re-derived per task; implementers re-verify cited line numbers before use.
- No placeholders: DAO-gap and upsert-mapping rules are decision procedures with the existing test suites as arbiters; GrdbMigrator API is fully specified; fallback semantics explicit.
- Type consistency: Backend enum, GrdbMigrator, roomDatabasePath used consistently across Tasks 3-8.
