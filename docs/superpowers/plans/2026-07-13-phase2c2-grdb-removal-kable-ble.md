# Phase 2c-2: GRDB Removal, Shared Outbox, and Kable BLE Core — Implementation Plan

> **REQUIRED SUB-SKILL:** Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> `- [ ]` syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-07-13-phase2c2-grdb-removal-kable-ble-design.md` (approved 2026-07-13)
**Prior art:** `docs/superpowers/plans/2026-07-12-phase2c1-room-storage-cutover.md`

## Goal

Finish the two remaining Apple-side legacy pillars:

1. Replace the GRDB raw-batch outbox (`rawBatch` / `cursors` tables in `whoop.sqlite`)
   with Room outbox tables owned by shared code (`OutboxStore` in `commonMain`), drain
   in-flight batches once, then delete GRDB entirely (**zero GRDB imports**).
2. Replace the CoreBluetooth WHOOP transport (`Strand/BLE/BLEManager.swift`, ~3,562
   lines) with a Kable-based shared BLE core in `commonMain` behind a thin Swift shim
   (< ~300 lines), one flow at a time with hardware checkpoints.

**Sequencing is storage-first (Stages 1–2 before Stage 3).** Persist-before-ack gets
tested once, on the final storage, before any transport changes.

## Hard invariant (read before every task in Stages 2–3)

The Backfiller archives raw bytes **before** acking the trim cursor. Current Swift
ordering (`Strand/Collect/Backfiller.swift` ~L20–45, ~L560–640):

```
decode known → await insert (decoded durable)
→ rejectedSink (RawHistoryArchive, fsynced)   — hold ack on failure (#57 persistStalled)
→ await enqueueRawBatch (raw durable, only if enableRawCapture) — hold ack on failure
→ await setCursor("strap_trim")               — hold ack on failure
→ ackTrim (.withResponse)
```

Every repoint/port must preserve: **outbox transaction commits → only then trim ack.**
Ack on commit success, never on enqueue-attempt. A regression silently and permanently
destroys data on the strap.

## Key existing code

| What | Where |
|---|---|
| GRDB outbox API | `Packages/WhoopStore/Sources/WhoopStore/RawOutbox.swift` (`enqueueRawBatch`, `rawFrames`, `pendingRawBatches`, `markRawBatchSynced`, `pruneRaw`) |
| GRDB cursors API | `Packages/WhoopStore/Sources/WhoopStore/Cursors.swift` (`setCursor`/`cursor`, `highwater:`/`read:` prefixes; Backfiller uses name `"strap_trim"`) |
| GRDB DDL | `Packages/WhoopStore/Sources/WhoopStore/Database.swift` (`rawBatch` ~L42, `cursors` ~L57) |
| rawBatch columns | `batchId` (PK, `ON CONFLICT DO NOTHING`), `deviceId`, `capturedAt`, `deviceClockRef`, `wallClockRef`, `startTs`, `endTs`, `frameCount`, `byteSize`, `framesBlob`, `syncedAt` (NULL = pending) |
| Frames blob format | `packFrames` — `[count u32 LE]{[len u32 LE][bytes]}×count`, then `zlibCompressWithLength` (4-byte uncompressed-length LE prefix + zlib). Keep this format in the Room outbox so diagnostics/export stay uniform. |
| Call sites | `Strand/Collect/Collector.swift:178` (live, fire-and-forget `try?`), `Strand/Collect/Backfiller.swift:581` (historical, ack-gated), reads in `Strand/System/TestCentreReport.swift` |
| Room DB | `shared/src/commonMain/kotlin/com/noop/data/WhoopDatabase.kt` — `version = 17` (~L65), `exportSchema = false`, `ALL_MIGRATIONS` ends at `MIGRATION_16_17` |
| Migration test pattern | `shared/src/androidUnitTest/kotlin/com/noop/data/DailySpo2RawMigrationTest.kt` (plain-JVM; `MigrationTestHelper` unavailable because `exportSchema = false`) |
| Retained ETL import | `shared/src/appleMain/kotlin/com/noop/data/GrdbMigrator.kt` + `GrdbMigratorTest` + `shared/src/commonTest/fixtures/grdb-mini.sqlite` + `Tools/make-grdb-fixture.sh` |
| Shared protocol | `shared/src/commonMain/kotlin/com/noop/protocol/Framing.kt`, `HistoricalStreams.kt`, `protocol/Enums.kt` |
| XCFramework rebuild | `Tools/build-shared-xcframework.sh` (run after any `shared/` API change consumed by Swift) |

Verify commands (from 2c-1):

- Kotlin: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :shared:testDebugUnitTest`
- Swift package: `swift test --package-path Packages/WhoopStore` (after `Tools/build-shared-xcframework.sh` if shared API changed)
- App: build/test the Strand scheme via `xcodebuild` (see 2c-1 plan for exact invocation)

---

## Stage 1 — Shared Room outbox

### Task 1: Outbox entities, DAO, `MIGRATION_17_18`

**Files:**
- `shared/src/commonMain/kotlin/com/noop/data/OutboxEntities.kt` (new)
- `shared/src/commonMain/kotlin/com/noop/data/OutboxDao.kt` (new)
- `shared/src/commonMain/kotlin/com/noop/data/WhoopDatabase.kt` (modify)
- `shared/src/androidUnitTest/kotlin/com/noop/data/OutboxMigrationTest.kt` (new)

**Steps:**
- [ ] Add entities mirroring the GRDB roles:
  - `OutboxBatchRow` (table `outboxBatch`): `batchId: String` @PrimaryKey, `deviceId: String`,
    `capturedAt: Long`, `deviceClockRef: Long`, `wallClockRef: Long`, `startTs: Long`,
    `endTs: Long`, `frameCount: Int`, `byteSize: Int`, `framesBlob: ByteArray`,
    `syncedAt: Long?` — plus `@Index` on `syncedAt` (pending scan) and on `capturedAt` (prune).
  - `OutboxCursorRow` (table `outboxCursor`): `name: String` @PrimaryKey, `value: Long`.
- [ ] `OutboxDao`: `@Insert(onConflict = IGNORE)` for batch (idempotent retry — matches GRDB
  `ON CONFLICT(batchId) DO NOTHING`), pending query (`WHERE syncedAt IS NULL ORDER BY capturedAt LIMIT :n`),
  `framesBlob` fetch by `batchId`, mark-synced, prune, cursor upsert/get.
- [ ] `WhoopDatabase.kt`: add both entity classes to `@Database(entities = [...])`, bump
  `version = 17` → `18`, add `internal val OUTBOX_MIGRATION_SQL: List<String>` +
  `MIGRATION_17_18 = object : Migration(17, 18)` executing it (exact pattern of
  `DAILY_SPO2_RAW_MIGRATION_SQL` / `MIGRATION_16_17`), append `MIGRATION_17_18` to
  `ALL_MIGRATIONS`. **No data backfill in SQL** (drain-once handles it, Task 6).
  SQL must match Room's generated schema exactly (`CREATE TABLE` + `CREATE INDEX`,
  NOT NULL-ness per Kotlin nullability).
- [ ] `OutboxMigrationTest` (plain-JVM, fixture-based like `DailySpo2RawMigrationTest`):
  open a v17 database file (create in-test via the v17 DDL or a copied fixture, same
  approach as the SpO2 test), run `OUTBOX_MIGRATION_SQL`, assert both tables + indices
  exist and an insert/readback round-trips. Also assert a fresh v18 open (Room-built
  schema) accepts the same insert — catches hand-SQL vs generated-schema drift.

**Verify:** `JAVA_HOME=... ./gradlew :shared:testDebugUnitTest`
**Commit:** `Phase 2c-2 Task 1: Room outbox tables + MIGRATION_17_18`

### Task 2: `OutboxStore` API + concurrency test

**Files:**
- `shared/src/commonMain/kotlin/com/noop/data/OutboxStore.kt` (new)
- `shared/src/commonTest/kotlin/com/noop/data/OutboxStoreTest.kt` (new)
- `shared/src/commonTest/kotlin/com/noop/data/OutboxStoreConcurrencyTest.kt` (new)

**Steps:**
- [ ] `OutboxStore(db: WhoopDatabase)` with suspend API mirroring `RawOutbox.swift` +
  `Cursors.swift` semantics:
  - `enqueue(meta, framesBlob: ByteArray): Boolean` — **transactional**, keyed on
    `batchId`, returns whether the row is durably present (insert-or-already-present
    = true). The Boolean is the commit signal persist-before-ack callers gate on.
  - `pending(limit: Int): List<OutboxBatchMeta>`, `framesBlob(batchId)`,
    `markSynced(batchId, at: Long)`, `prune(now, keepWindowSeconds, maxUnsyncedBytes)` —
    port `pruneRaw` semantics (never drop unsynced under the byte cap; keep window).
  - Cursors: `setCursor(name, value)`, `cursor(name)`, and the `highwater:`/`read:`
    convenience wrappers matching `Cursors.swift` names exactly (drain + repoint keep
    the same cursor names, incl. `"strap_trim"`).
  - The frames blob is **opaque** to `OutboxStore` (already packed+zlib'd by the caller);
    codec parity is Task 12's problem when Kotlin becomes a writer.
- [ ] `OutboxStoreTest`: enqueue idempotency (same `batchId` twice → one row, returns true),
  pending ordering, mark-synced excludes from pending, prune floor behavior.
- [ ] `OutboxStoreConcurrencyTest` (commonTest — this is the named replacement for the
  Swift `DatabasePoolConcurrencyTests` deleted in Task 8): concurrent `enqueue` ×N
  coroutines + interleaved `markSynced`/`pending` on one shared handle; assert no lost
  rows, no duplicate `batchId`, pending+synced partition is exact.

**Verify:** `JAVA_HOME=... ./gradlew :shared:testDebugUnitTest` (and iOS test task if configured, per 2c-1)
**Commit:** `Phase 2c-2 Task 2: OutboxStore + concurrency test`

### Task 3: Bridge outbox to Swift

**Files:**
- Swift-facing bridge in `Packages/WhoopStore` (extend the 2c-1 `SharedStoreBridge`
  surface — locate via `grep -rn "SharedStoreBridge" Packages/WhoopStore/Sources`)
- `Packages/WhoopStore/Tests/WhoopStoreTests/OutboxBridgeTests.swift` (new)

**Steps:**
- [ ] Run `Tools/build-shared-xcframework.sh` to pick up Tasks 1–2 API.
- [ ] Expose async Swift wrappers: `outboxEnqueue(meta:framesBlob:) async throws -> Bool`,
  `outboxPending(limit:)`, `outboxFrames(batchId:)`, `outboxMarkSynced`, `outboxPrune`,
  `outboxSetCursor`/`outboxCursor`. Blob crosses as `Data`↔`ByteArray`
  (`KotlinByteArray` conversion helpers already exist from 2c-1 — reuse them).
  Keep `packFrames`/`zlibCompressWithLength` on the Swift side for now; callers hand
  the bridge the finished blob.
- [ ] `OutboxBridgeTests`: round-trip a batch (enqueue → pending → frames blob decode via
  existing `unpackFrames`/`zlibDecompressWithLength` → byte-equal), idempotent
  re-enqueue returns true, cursor round-trip.

**Verify:** `swift test --package-path Packages/WhoopStore`
**Commit:** `Phase 2c-2 Task 3: Swift bridge for shared outbox`

---

## Stage 2 — Repoint, drain-once, delete GRDB

### Task 4: Repoint writers (Collector + Backfiller) and diagnostics reads

**Files:**
- `Strand/Collect/Collector.swift` (modify — `BackfillStoreWriting`-style protocol at ~L14, write at ~L178)
- `Strand/Collect/Backfiller.swift` (modify — protocol at ~L15, write at ~L581)
- `Strand/BLE/BLEManager.swift` (modify — wherever it constructs the store for Backfiller/Collector)
- `Strand/System/TestCentreReport.swift` (modify — `rawBatch` reads → outbox query API)

**Steps:**
- [ ] Point the `enqueueRawBatch` / `setCursor` protocol requirements at the outbox bridge
  instead of `WhoopStore` GRDB. Keep the protocols (`BackfillStoreWriting`, Collector's
  store protocol) — swap the conforming implementation, so Backfiller/Collector test
  doubles keep working unchanged.
- [ ] **Ordering unchanged:** Backfiller still awaits enqueue success before
  `setCursor("strap_trim")` before `ackTrim`; all `persistStalled` (#57) hold-ack paths
  preserved verbatim. Collector's live path stays fire-and-forget (`try?`).
- [ ] `TestCentreReport.swift`: pending-batch counts/sizes now read from the outbox bridge.
- [ ] Run the existing Backfiller/Collector unit tests — they must pass without edits
  (protocol seam preserved).

**Verify:** app builds; existing Strand tests green; manual: live capture writes rows into `outboxBatch` (inspect via Test Centre report)
**Commit:** `Phase 2c-2 Task 4: repoint outbox writers + diagnostics to Room outbox`

### Task 5: Persist-before-ack regression test (highest-stakes test in the phase)

**Files:**
- New test alongside existing Backfiller tests (locate: `grep -rn "BackfillStoreWriting" --include='*.swift' .` in the test target)

**Steps:**
- [ ] Mock store whose `enqueueRawBatch` records a commit timestamp/sequence and can be
  told to fail or hang; mock transport recording when `ackTrim` fires.
- [ ] Assert, with raw capture ON: (a) no trim ack before the enqueue commit callback
  returns success; (b) enqueue failure → no ack + `persistStalled` set + later empty
  END still not acked (#57 twin); (c) cursor-write failure → no ack.
- [ ] Assert with raw capture OFF: decoded-insert failure still holds the ack.

**Verify:** new tests green; intentionally invert the ordering locally to confirm the test fails (then revert)
**Commit:** `Phase 2c-2 Task 5: persist-before-ack ordering test`

### Task 6: Drain-once

**Files:**
- `Strand/Data/OutboxDrain.swift` (new)
- App startup wiring (where 2c-1's one-shot migration runs — follow that pattern in `Strand/App/AppModel.swift`)
- Unit test simulating partial drain

**Steps:**
- [ ] One-shot on launch, while GRDB read code is still in tree:
  1. If no GRDB `whoop.sqlite` exists → done (fresh installs no-op).
  2. Read pending batches via existing `pendingRawBatches`/`rawFrames`, re-pack with the
     same `packFrames`+zlib helpers, `outboxEnqueue` each (idempotent by `batchId`).
  3. Copy cursor rows worth keeping (`strap_trim`, `highwater:*`, `read:*`) via
     `outboxSetCursor` — **only if the Room outbox cursor is unset** (never clobber a
     newer post-cutover value on a re-run).
  4. Rename `whoop.sqlite` (+ `-wal`/`-shm`) → `noop-drained-<ts>.sqlite` — the rename
     is the done-marker (matches `noop-replaced-<ts>.sqlite` philosophy; recoverable,
     not deleted).
- [ ] Idempotency: crash before rename → re-run re-enqueues (conflict-ignored) and
  re-copies cursors (unset-only guard) safely. Unit test: drain, simulate crash before
  rename, drain again → row set identical, cursors not regressed, then rename happens.

**Verify:** unit test green; manual: upgrade a device/simulator with pending GRDB batches → rows appear in `outboxBatch`, `noop-drained-*.sqlite` present
**Commit:** `Phase 2c-2 Task 6: GRDB→Room outbox drain-once`

### Task 7: `DataBackup` + `AppModel` cleanup

**Files:**
- `Strand/Data/DataBackup.swift` (modify)
- `Strand/App/AppModel.swift` (modify)

**Steps:**
- [ ] `DataBackup.swift`: delete the legacy GRDB fallback **export** path and the GRDB
  WAL-checkpoint probe. **Keep** the `.grdb` **import** routing (magic-byte +
  GRDB-origin validation → `GrdbMigrator` ETL → sidecar snapshot → rollback) — decision
  #4, option (a).
- [ ] `AppModel.swift` storage report: stop reading GRDB file + WAL/SHM sizes; report the
  Room DB size and any `noop-drained-<ts>.sqlite` snapshot size instead.

**Verify:** export produces a Room-only archive; importing an old `.grdb` export still restores (existing `GrdbMigratorTest` + a manual import)
**Commit:** `Phase 2c-2 Task 7: Room-only backup export; storage report off GRDB`

### Task 8: Delete legacy outbox drain (DESCOPED 2026-07-14)

> **Descope decision (human-approved):** original Task 8 ("delete GRDB, zero imports")
> was unsatisfiable at task size. Verified scope reality: 37 files `import GRDB`
> (32 WhoopStore, 3 NoopLocalAccess, 2 StrandImport — the latter two were never in
> this phase's inventory); 105 `.legacyGrdb` refs across 12 source files (~77 switch
> branches / 11 stores); 16 test suites depend on GRDB-only `inMemory()`; 7
> `LegacyGrdb*PinTests` suites exist solely to pin legacy behavior. Full removal
> deferred to its own planned task (see Deferred Work below). This task now covers
> only the self-contained drain cleanup + doc/visibility items.

**Files:**
- Delete: `LegacyOutboxDrain` (Task 6 drain) + its drain-only GRDB helpers
  (verified self-contained: called only by drain), `LegacyOutboxDrain` tests,
  `DatabasePoolConcurrencyTests`
- Modify: `Strand/Data/DeviceRegistry.swift` (`registryWriter` → `private`; doc sweep),
  `Strand/Data/BackupSync.swift` (comment sweep)

**Steps:**
- [ ] Delete drain code (Task 6) — the snapshot rename already happened for every
  upgraded install. Keep `OutboxDrain`'s rename-marker check only if startup still
  references it; otherwise remove wholesale.
- [ ] Delete drain-only GRDB helpers it called; do NOT touch helpers shared with the
  `.legacyGrdb` read path (that path stays until the deferred removal task).
- [ ] `registryWriter` → `private`; comment sweeps in `BackupSync.swift` / `DeviceRegistry.swift`.
- [ ] Delete `DatabasePoolConcurrencyTests` (replacement `OutboxStoreConcurrencyTest`
  landed in Task 2).
- [ ] **Explicitly out of scope:** `.legacyGrdb` enum/fallback paths, `RawOutbox.swift`
  GRDB internals, `Cursors.swift`, `Database.swift` open/DDL, GRDB package dependency,
  `inMemory()` test strategy, `LegacyGrdb*PinTests`.
- [ ] **Acceptance:** `grep -rn "LegacyOutboxDrain" --include='*.swift' .` → zero hits;
  full build + all Swift/Kotlin tests green.

**Verify:** full build + all Swift/Kotlin tests green; grep acceptance above
**Commit:** `Phase 2c-2 Task 8: delete legacy outbox drain (full GRDB removal deferred)`

#### Deferred Work: full WhoopStore GRDB removal (needs own plan)

Not part of Phase 2c-2. Requires brainstorm + plan covering: migrating/removing the
11 `.legacyGrdb` read stores (~77 branches, 12 files), a Room-compatible replacement
for GRDB-only `inMemory()` in 16 test suites, disposition of the 7
`LegacyGrdb*PinTests` suites, dropping the GRDB dependency from
`Packages/WhoopStore/Package.swift` + project refs, and moving the pure
`packFrames`/`unpackFrames`/zlib helpers out of `RawOutbox.swift`. Acceptance
re-scoped to WhoopStore only — `NoopLocalAccess` and `StrandImport` use GRDB
legitimately and are out of scope.

---

## Stage 3 — Kable BLE core

Each flow = hardware verification on a real strap (MANUAL GATE), and the CoreBluetooth
code for that flow is **deleted at cutover** — no long-lived dual path. Destructive
opcodes (reboot, firmware load, force-trim, ship-mode) are excluded throughout.

### Task 9: Kable dependency + `BleScanner` (flow 1: scan/discover)

**Files:**
- `shared/build.gradle.kts` (add Kable)
- `shared/src/commonMain/kotlin/com/noop/ble/BleScanner.kt` (new)
- `shared/src/commonTest/kotlin/com/noop/ble/BleScannerFilterTest.kt` (new — pure filter logic)
- Swift shim start: `Strand/BLE/WhoopBleShim.swift` (new) — permissions, background modes, `SourceCoordinator` adapter
- `Strand/BLE/BLEManager.swift` — delete its scan/discovery path at cutover

**Steps:**
- [ ] Add Kable (commonMain); confirm iOS framework export via `Tools/build-shared-xcframework.sh`.
- [ ] `BleScanner`: Kable scan → WHOOP advertisement filter (port the exact filter rules
  from `BLEManager.swift`'s discovery path — service UUIDs/name rules — as pure,
  unit-tested predicates).
- [ ] Shim surfaces scan results to `SourceCoordinator` exactly where CoreBluetooth
  discovery did; non-WHOOP sources untouched.
- [ ] Delete BLEManager's scan path.
- [ ] **MANUAL GATE (hardware):** real strap discovered on iPhone; non-WHOOP sources still discover.

**Commit:** `Phase 2c-2 Task 9: Kable BleScanner cutover (flow 1)`

### Task 10: `BleSession` (flow 2: connect/session + hello/version/clock)

**Files:**
- `shared/src/commonMain/kotlin/com/noop/ble/BleSession.kt` (new)
- `Strand/BLE/BLEManager.swift` — delete connect/hello path at cutover

**Steps:**
- [x] Connection lifecycle, GATT characteristic setup, reconnect policy; hello/version/clock
  exchange via shared `Framing.kt` (already shared). Port BLEManager's reconnect
  backoff/queue semantics; keep behavior parity notes in KDoc with `#issue` refs.
- [x] **MANUAL GATE (hardware):** connect, hello/version/clock round-trip, reconnect after
  out-of-range on a real strap. Verified on Boro's WHOOP 4.0: full hello/version/clock
  round-trip (`session` harness), walk-away disconnect → auto-reconnect gen=2 → handshake
  re-sent → frames flowing (`session-walkaway` harness).

**Commit:** `Phase 2c-2 Task 10: BleSession cutover (flow 2)`

### Task 11: `FrameTransport` + realtime streams (flow 3: HR, raw, R10/R11)

**Files:**
- `shared/src/commonMain/kotlin/com/noop/ble/FrameTransport.kt` (new)
- Repoint Collector's frame intake through the shim; delete BLEManager realtime path

**Steps:**
- [x] Characteristic I/O → `Framing.kt` reassembly → existing decoded-stream persistence
  (`StreamPersistence.kt` path from 2c-1) + Collector raw path (outbox, Task 4 seam).
- [x] **MANUAL GATE (hardware):** live HR + raw capture session lands rows in Room streams
  and `outboxBatch`; battery/HR UI live. Validated via T11 realtime harness on real WHOOP
  hardware (REALTIME + REALTIME_RAW_DATA frames landed, HR rows + raw batches persisted).

**Scope note (live PPG HR — intentionally excluded):** during v26-heavy stretches the live
path emits no HR rows; this is Swift-parity behavior, not a gap. Swift derives PPG HR only
on the historical path (`PpgHr.derivePpgHr` called solely from `HistoricalStreams.swift`;
`Streams.swift` merely carries the `ppgHr` field). Kotlin already mirrors this end-to-end:
`shared/.../protocol/PpgHr.kt` is the full estimator (with `PpgHrTest.kt`), and
`HistoricalStreams.kt` collects v26 `PpgHr.Sample`s and emits `PpgHrRow`s. HR continuity
through v26 gaps is therefore recovered by the Backfiller (flow 4, Task 13) — no live
decode work belongs in flow 3.

**Commit:** `Phase 2c-2 Task 11: realtime streams over Kable (flow 3)`

### Task 12: Kotlin historical decode gaps + outbox codec (mandatory pre-flow-4)

**Rescoped during execution:** original scope said "v20/v21 layouts", but no v20/21
layout exists anywhere (not in schema, not in Swift). Actual gaps were the hard-coded
v25 path (issue #30, `PostHooks.swift`) and the unmapped-version fallback that decodes
unknown hist_versions with the v24 layout behind a gravity physical-validation gate
(magnitude 0.5..1.5); rejected records stay raw instead of being dropped.

**Files:**
- `shared/src/commonMain/kotlin/com/noop/protocol/Framing.kt` (modified — v25 path +
  v24-fallback landed here, not `HistoricalStreams.kt`)
- `shared/src/commonTest/` decode-parity tests + fixtures (`historical_golden.json`,
  `historical_frames.json`, synthetic v25 / unmapped-version cases)
- Kotlin outbox codec: `shared/src/commonMain/kotlin/com/noop/ble/OutboxCodec.kt`
  (commonMain expect + android/apple actuals; apple actual uses `platform.zlib` raw
  DEFLATE, windowBits -15)

**Steps:**
- [x] Port v25 decode (unix u32 LE @11, gravity 3x i16/16384 @73/75/77, PPG bytes
  23-73, empty rr_intervals) + unmapped-version v24-layout fallback gated by gravity
  validation.
- [x] Parity tests: Kotlin decode of Swift golden fixtures == Swift decoder output
  (`HistoricalGoldenParityTest`, `HistoricalFallbackTest`, `Whoop4HistoricalV25Test`,
  `Whoop4HistoricalV25PpgTest`, `RejectedHistoricalRecordsTest`).
- [x] `OutboxCodec` + cross-impl fixture test (Swift `RawOutboxCrossImplTests` decodes
  a Kotlin-produced blob; `OutboxCodecTest` covers the reverse) — required because the
  Kotlin Backfiller (Task 13) becomes an outbox writer.

**Verify:** done — JVM commonTest 62/62, iosSimulatorArm64 24/24, Swift
RawOutboxCrossImplTests 2/2. **This task gates Task 13 — gate satisfied.**
**Commit:** `037e4b3c` — `Phase 2c-2 Task 12: port historical v25 decode + unmapped-version v24 fallback to Kotlin; shared outbox codec`

### Task 13: Kotlin `Backfiller` (flow 4: historical backfill — persist-before-ack gates this)

**Files:**
- `shared/src/commonMain/kotlin/com/noop/ble/Backfiller.kt` (new)
- `shared/src/commonTest/kotlin/com/noop/ble/BackfillerAckOrderTest.kt` (new)
- Delete Swift `Strand/Collect/Backfiller.swift` + BLEManager backfill path at cutover

**Steps:**
- [x] Port the full state machine including: `persistStalled` (#57) session-wide ack hold,
  `trim == 0xFFFFFFFF` sentinel handling (#150/#783/#1), auto-continuation spin guard
  (#364, `lastTrimAdvanced`), rejected-frame archive hold, watchdog timeout (no ack on
  timeout). Rejected-frame archive: keep writing the same `rejected_history.jsonl`
  record shape (`{"capturedAtMs","trim","family","frameHex"}`) — Android side already
  shares it.
- [x] Ordering: decoded insert → rejected archive → outbox `enqueue` commit →
  `setCursor("strap_trim")` → trim ack. All failure branches hold the ack.
- [x] `BackfillerAckOrderTest` (mock transport, commonTest): the Kotlin twin of Task 5 —
  no ack before outbox commit; failure branches (archive fail, enqueue fail, cursor
  fail) each hold the ack; stalled session never acks a later END.
- [ ] **MANUAL GATE (hardware):** full historical offload on a real strap (ideally one
  holding v20/v21 data); verify decoded rows + trim advance across reconnects; Task 5's
  Swift test retired with the Swift Backfiller. `BackfillHarness.kt` (`backfill` subcommand
  on `scan-harness.kexe`) is built and links; the controller runs the actual gate.

**Commit:** `Phase 2c-2 Task 13: Kotlin Backfiller cutover (flow 4)`

### Task 14: `CommandChannel` (flow 5: device commands)

**Files:**
- `shared/src/commonMain/kotlin/com/noop/ble/CommandChannel.kt` (new)
- `shared/src/commonTest/kotlin/com/noop/ble/CommandChannelTest.kt` (new)
- Delete BLEManager command path at cutover

**Steps:**
- [ ] Port the `Commands.swift` opcode table (alarms, haptics, config, battery), reusing
  `protocol/Enums.kt` where opcodes already exist. **Destructive opcodes excluded** —
  carry over the exclusion doc from `Commands.swift` verbatim.
- [ ] Encode/decode unit tests against known-good byte fixtures from Swift.
- [ ] **MANUAL GATE (hardware):** set/clear alarm, trigger haptic, read battery/config on a real strap.

**Commit:** `Phase 2c-2 Task 14: CommandChannel cutover (flow 5)`

### Task 15: Delete `BLEManager.swift`; final sweep

**Steps:**
- [ ] Delete `Strand/BLE/BLEManager.swift`; shim (`WhoopBleShim.swift`) is the only WHOOP
  BLE Swift left — **assert < ~300 lines**; non-WHOOP sources (`FTMSSource`,
  `GarminBroadcast`, `HuamiHRSource`, `OuraLiveSource`, `StandardHRSource`) untouched
  behind `SourceCoordinator`.
- [ ] Sweep: no orphaned Swift protocol/decoder call sites (WhoopProtocol Swift package
  itself stays — removal deferred per decision #8). Update `docs/` architecture notes +
  the divergence watch list (v20/21 entry closes).
- [ ] Full regression: all Kotlin + Swift tests; battery of manual checks (scan→connect→
  live→backfill→commands) on a real strap; Android build unaffected.

**Commit:** `Phase 2c-2 Task 15: delete BLEManager.swift; Kable core complete`

---

## Risk gates recap

| Risk | Gate |
|---|---|
| Persist-before-ack regression | Task 5 (Swift) + Task 13 test (Kotlin); flow 4 blocked on both |
| Drain crash mid-run | Task 6 idempotency test; snapshot rename = done-marker |
| v20/21 decode gap | Task 12 parity fixtures gate Task 13; raw archive backstop |
| BLE behavior drift | Per-flow MANUAL GATE on real hardware; no flow done without it |
| Data recoverability | `noop-drained-<ts>.sqlite` + retained `GrdbMigrator` import path |
