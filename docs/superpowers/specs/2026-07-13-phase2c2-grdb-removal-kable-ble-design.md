# Phase 2c-2: GRDB Removal, Shared Outbox, and Kable BLE Core — Design

**Date:** 2026-07-13
**Status:** Approved (section-by-section review with Boro, 2026-07-13)
**Prior art:** `docs/superpowers/plans/2026-07-12-phase2c1-room-storage-cutover.md`, `docs/superpowers/specs/2026-07-09-kmp-cmp-unification-design.md`

## 1. Overview & Goals

Phase 2c-1 moved the primary Apple storage to the shared Room database behind
`SharedStoreBridge`. Two legacy pillars remain on the Apple side:

1. **GRDB** — one last handle serving the raw-batch outbox (`rawBatch` /
   `cursors` tables) plus backup/restore paths.
2. **CoreBluetooth** — `BLEManager.swift` (~3,562 lines) owning the entire
   WHOOP BLE path in platform Swift, duplicating protocol logic that already
   exists in shared Kotlin (`Framing.kt`, `HistoricalStreams.kt`).

Phase 2c-2 finishes both cutovers:

- Replace the GRDB outbox with Room outbox tables owned by shared code
  (`OutboxStore` in `commonMain`), then delete GRDB from the project.
- Replace CoreBluetooth WHOOP transport with a Kable-based shared BLE core in
  `commonMain`, behind a thin Swift shim.

**Sequencing: storage-first (Approach A, approved).** Outbox tables land
first; BLE flows cut over one at a time afterward. Storage layer is stable
ground for the riskier BLE work, and persist-before-ack semantics get tested
once, on the final storage, before any transport changes.

### Non-goals

- Migrating non-WHOOP sources (`FTMSSource`, `GarminBroadcast`,
  `HuamiHRSource`, `OuraLiveSource`, `StandardHRSource`) off CoreBluetooth.
  They stay Swift behind the `SourceCoordinator` seam.
- Removing `WhoopProtocol` Swift implementations (deferred; dead code cleanup
  in a later phase).
- Exposing destructive opcodes (reboot, firmware load, force-trim, ship-mode)
  through the new command layer. Same exclusion `Commands.swift` documents
  today.
- UI changes.

## 2. Shared Outbox Redesign

New Room tables for the raw-batch outbox, owned by shared code:

- **Tables:** outbox batch table + cursor table, mirroring the roles of
  GRDB's `rawBatch` / `cursors`.
- **Migration:** hand-written `MIGRATION_17_18` creates both tables (Room
  schema 17 → 18; current version pinned at `WhoopDatabase.kt:65`). **No data
  backfill in the migration** — in-flight GRDB batches at upgrade time are
  handled by the drain-once step (§3b), not by SQL migration.
- **Migration testing constraint:** `WhoopDatabase` sets
  `exportSchema = false` (deliberate — kills the destructive fallback), so
  there are no exported schema JSONs and Room's `MigrationTestHelper` is
  unavailable. Migration test is fixture-based instead: open a copied v17
  database file, run `MIGRATION_17_18`, assert both tables + indices exist
  (same pattern as `grdb-mini.sqlite` fixtures in 2c-1).
- **API:** `OutboxStore` in `commonMain` — transactional enqueue, flush,
  cursor advance. Enqueue keyed on batch identity so retries are idempotent.
- **Concurrency:** `OutboxStoreConcurrencyTest` in `commonTest` replaces the
  deleted `DatabasePoolConcurrencyTests` (concurrent enqueue/flush
  correctness on the shared handle).

## 3. Call-Site Repointing, Drain-Once, GRDB Deletion

### 3a. Repoint call sites

Full inventory of GRDB-touching Swift files (verified by grep,
`GRDB|DatabasePool|legacyGrdb|rawBatch`):

| File | Touch | New target |
|---|---|---|
| `Strand/Collect/Collector.swift` | `rawBatch` writes (live stream batches) | `OutboxStore.enqueue` |
| `Strand/BLE/BLEManager.swift` | `rawBatch` writes (historical offload) | `OutboxStore.enqueue` |
| `Strand/System/TestCentreReport.swift` | `rawBatch` reads (diagnostics) | outbox query API |
| `Strand/Data/DataBackup.swift` | legacy GRDB fallback export + `.grdb` import routing | see below |
| `Strand/App/AppModel.swift` | storage report reads GRDB file + WAL/SHM sizes | report Room DB + `noop-drained-<ts>.sqlite` snapshot instead |
| `Strand/Data/BackupSync.swift` | comment-only reference | doc sweep at deletion |
| `Strand/Data/DeviceRegistry.swift` | comment-only reference + `registryWriter` | doc sweep; `registryWriter` → `private` (§3c) |

**`DataBackup.swift` detail.** Already Room-primary since 2c-1: export uses
the Room file, falling back to legacy GRDB `whoop.sqlite` only when no Room
file exists yet. Changes here: delete that fallback export path and the GRDB
WAL-checkpoint probe; **keep** the `.grdb` import routing (magic-byte +
GRDB-origin validation, ETL through `GrdbMigrator`, sidecar snapshot,
rollback) — that is the option-(a) import path and it stays.

**Persist-before-ack invariant (hard constraint).** The Backfiller archives
raw bytes *before* acking the trim cursor. New flow preserves this exactly:
Room outbox transaction **commits** → only then is the trim ack sent. Ack on
commit success, never on enqueue-attempt. Enforced by a test with a mock
transport asserting no trim ack is emitted before the outbox commit callback.
A regression here silently and permanently destroys data on the strap —
highest-stakes test in the phase.

### 3b. Drain-once

One-shot on first launch post-migration, while GRDB read code is still in
tree:

1. Read pending GRDB outbox batches via existing `rawBatch` / `cursors`.
2. Re-enqueue into the Room outbox (same transaction semantics).
3. Rename the GRDB file → `noop-drained-<ts>.sqlite` snapshot. Matches the
   existing `noop-replaced-<ts>.sqlite` philosophy — recoverable, not
   deleted.

Idempotent: the snapshot-rename doubles as the done-marker; a crash mid-drain
re-runs safely because enqueue is keyed on batch identity.

### 3c. Delete GRDB

- Remove `rawBatch` / `cursors` access, the `.legacyGrdb` fallback, and the
  drain code itself.
- `registryWriter` → `private`.
- Delete `DatabasePoolConcurrencyTests`; `OutboxStoreConcurrencyTest` is the
  replacement (§2).
- Drop the GRDB package dependency from the project. **Acceptance criterion:
  zero GRDB imports.**

**Old `.grdb` export files (decision: option a).** With runtime GRDB gone,
the Swift `.grdb` restore branch dies. The Kotlin `GrdbMigrator` ETL path
(`shared/src/appleMain/kotlin/com/noop/data/GrdbMigrator.kt`, tested by
`GrdbMigratorTest`) is retained for importing old exports — old backups stay
restorable without Swift GRDB.

## 4. Kable BLE Core

### Structure (`commonMain`)

- `BleScanner` — Kable-based discovery, WHOOP advertisement filtering.
- `BleSession` — connection lifecycle, GATT characteristic setup, reconnect
  policy.
- `FrameTransport` — characteristic I/O → existing `Framing.kt` reassembly
  (already shared).
- `CommandChannel` — Kotlin port of the `Commands.swift` opcode table
  (protocol enums already partially in `protocol/Enums.kt`). Destructive
  opcodes excluded (see Non-goals).
- `Backfiller` — historical offload owning persist-before-ack: outbox commit
  → trim ack.

Swift side shrinks to a thin shim: platform permissions, background-mode
plumbing, `SourceCoordinator` adapter. **Target: `BLEManager.swift` deleted,
shim < ~300 lines.**

### Flow migration order

Each flow = a hardware verification checkpoint (real strap), with the
CoreBluetooth code for that flow deleted at cutover (clean cutover, no
long-lived dual path):

1. Scan/discover
2. Connect/session + hello/version/clock
3. Realtime streams (HR, raw, R10/R11)
4. Historical backfill — persist-before-ack test gates this one
5. Device commands (alarms, haptics, config, battery)

### Kotlin v20/21 historical decode — mandatory

The watch list called the Kotlin v18-only historical decode gap a "verified
non-divergence today" — true only while the Swift decoder exists. Deleting
the Swift path makes Kotlin the sole decoder, so the gap becomes real data
loss for WHOOP5 firmware v20/v21. **Required task in this phase:** port
v20/21 decode into `HistoricalStreams.kt` with capture fixtures.
Archive-before-ack keeps raw bytes recoverable meanwhile, but the decode gap
must close before the flow-4 cutover.

## 5. Decision Log

| # | Decision | Choice |
|---|---|---|
| 1 | Sequencing | Storage-first (Approach A) |
| 2 | Outbox migration | `MIGRATION_17_18`, no SQL backfill |
| 3 | In-flight GRDB batches | Drain-once at first launch + persist-before-ack |
| 4 | Old `.grdb` exports | Keep `GrdbMigrator` ETL import path (option a) |
| 5 | BLE scope | WHOOP path only; other sources stay Swift |
| 6 | v20/21 decode | Mandatory before flow-4 cutover |
| 7 | Destructive opcodes | Excluded from `CommandChannel` |
| 8 | `WhoopProtocol` Swift removal | Deferred, out of scope |

## 6. Risks & Verification

- **Persist-before-ack regression** → permanent on-strap data loss. Mock
  transport test asserting commit-before-ack ordering; gates flow 4.
- **Drain-once crash mid-run** → idempotent enqueue + snapshot-rename as
  done-marker; unit test simulating partial drain.
- **Decode gap (v20/21)** → capture fixtures + decode parity tests before
  flow-4 cutover; raw archive as backstop.
- **BLE behavior drift** → per-flow hardware checkpoints on a real strap;
  no flow considered done without one.
- **Data recoverability** → `noop-drained-<ts>.sqlite` snapshot retained;
  `GrdbMigrator` import path keeps old exports restorable.
