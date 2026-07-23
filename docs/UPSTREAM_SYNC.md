# Upstream Sync — Port Tracker

Triage of upstream [`ryanbr/noop`](https://github.com/ryanbr/noop) commits since fork point
`e19db18ba0217924334926535c7c9a631cc21b30` (just past v8.5.2), covering releases
**v8.6.0 → v9.1.0** (~403 commits). Goal: pull bugfixes/improvements into this KMP rework
without re-importing the Swift-first architecture we are migrating away from.

Already ported:

- [x] `e19db18b` Settings: `.menu` pickers so segmented controls can't oversize the screen
  (#161) — cherry-picked as `b0c7f34a`.

Legend: hashes are upstream commits (available locally via the `upstream` remote).
Items that change stored-night outputs must ship behind an analytics-version bump wired
into the existing force-restage/rescore sweep (`SleepStageHealer.STAGING_VERSION` pattern)
so history heals itself.

---

## HIGH — port (verified missing in our tree)

### 1. HRV gap-aware rMSSD (#195 / #204) — biggest accuracy bug
- [x] `4cb435af` gap-aware RMSSD/pNN50 (Android original #204) — ported into
  `shared/.../analytics/HrvAnalyzer.kt` (`CleanSeries` / `cleanRRGapAware` /
  `rmssdGapAware` / `pnn50GapAware`, wired into `analyzeRaw`) + `SleepStager.kt`
  `sessionHrvWindows`, with `HrvGapAwareTest` in commonTest (passes JVM + iosSimulatorArm64).
- [x] `286e8b88` per-night cleaning-pipeline summary in the always-on log (#205) — ported into
  `IntelligenceEngine.kt`'s per-day scoring loop (right after the `analyzeDay` call, next to the
  #691 rhr-floor diag): separate `HrvAnalyzer.analyzeRaw` pass over in-sleep R-R, emits
  `hrv diag day=… rmssd=…ms sdnn=…ms meanNN=…ms rr=nInput/nClean rejected=N%` via the always-on
  `diag` sink, byte-identical line format to upstream. Emits for ANY in-sleep R-R (no MIN_BEATS
  gate — the sparse night `rr=<n>/<m> rmssd=nil` case is the informative one, per upstream's
  re-review fix). Does NOT touch the shipped windowed `avgHrv`. Green: shared JVM suite
  1656/1660 (the 4 fails are pre-existing JDK locale-whitespace pinning fails, proven on a
  clean tree).
- [x] `b8a35820` honour deep-sleep HRV window on edit/backfill re-scores (#206) — ported with
  item 2 (#201): `AppViewModel.rescoreAfterEdit()` now threads
  `deepHrvWindow = UnitPrefs.hrvWindow(...)` like the 15-min loop (it defaulted to `false`,
  so an edit re-score recomputed the touched nights whole-night for a Deep-sleep user).

The cleaned RR series was compacted before RMSSD/pNN50 ran, so a dropped noisy beat spliced
its neighbours and the squared jump across the gap dominated the night (HRV ~2× high, recovery
skewed). Now the cleaner tracks contiguity and successive-difference metrics skip any pair
straddling a gap. Clean nights are byte-identical (existing golden vectors unchanged).

Note: `analyze(rr)` here operates on the raw `rrInterval` table, so a re-score already
re-derives HRV from raw — no stored `stagesJSON`-style column caches the old value. Existing
`analyzeRecent` fingerprint gate picks up the change on next data; a forced full rescore isn't
required for HRV (unlike the sleep-staging items). Deep Timeline `rollingRmssd`, `HrvFreqDomain`
tachogram, and direct `rmssdRaw` in `RhythmScreener`/`ResonanceEngine` still splice — upstream
lists these as the same follow-up class; port later if wanted.

### 2. HRV window switch re-folds baseline (#201)
- [x] `f890e416` re-fold the HRV baseline on a window switch instead of restarting
  calibration (#209) — ported. `SettingsScreen.kt` no longer writes
  `noop.hrvBaselineEpoch` on the window toggle (epoch reset dropped every stored night from
  the fold → CALIBRATING for ~`minNightsSeed` nights, Change nil); it keeps the watermark
  clear + `syncNow()` so the ~21-night re-score under the new window EWMA-dominates the
  full-history fold within days while the baseline stays USABLE throughout. The epoch reset
  remains the manual "Recalibrate Charge baseline" mechanism only. Pinned by
  `windowSwitchRefold_staysUsableWithoutEpochReset` (usable across the switch, ~63 → 53.9
  after the 21 re-scored nights, → 52.0 ten nights later) alongside the existing
  `recalibrateEpoch_afterAllNights_resetsToColdStart` cold-start pin.

### 3. Confirm-sleep runs on median HR, not mean (#268)
- [x] `3b168690` recover dropped nights — ported into
  `shared/.../analytics/SleepStager.kt` (`confirmSleepWithHR` now gates on
  `HrvAnalyzer.median(seg)` instead of the mean) + `SleepStagerHrConfirmTest`
  in commonTest (5 cases, green JVM + iosSimulatorArm64).

Mean HR let a few arousal/wake spike beats pull the run mean over
`baseline * 1.05` and veto a real night; the spike-robust median keeps it. A
genuinely awake run still has a high median and is still rejected, and because
`median <= mean` for right-skewed HR the gate only ever RELAXES — no
currently-detected night regresses. Ported byte-for-byte from upstream (which
shipped it algorithm-only: no version bump, no migration).

**Correction to the earlier plan (STAGING_VERSION is the wrong tool here).**
`confirmSleepWithHR` decides whether a sleep *session* is DETECTED at all; a
dropped night has NO stored `sleepSession` row. `SleepStageHealer.forceRestageAll`
(the `STAGING_VERSION` sweep) iterates `repo.sleepSessions(...)` and only
rewrites the `stagesJSON` breakdown of rows that ALREADY EXIST within their
locked bounds — it cannot resurrect a night that was never detected. So bumping
`STAGING_VERSION` does nothing for #268. Recovery instead comes from
re-DETECTION:
- Nights dropped within the **21-day recompute window** re-detect automatically
  on the next engine pass (every pass re-runs `detectSleep` over that window) —
  no gate needed. This matches upstream's algorithm-only shipping.
- Nights dropped **older than 21 days** would need a one-shot full-history
  re-detect — the `IntelligenceEngine.runEffortRescoreIfNeeded` pattern
  (`analyzeRecent(maxDays = 4000)` behind a spent one-shot flag), NOT
  `forceRestageAll`. Deferred: this fork has little long-term on-device (BLE)
  detection history (imports come verbatim and are never re-detected), so the
  set of >21-day-old dropped on-device nights is ~empty. Add the one-shot only
  if a real strap history turns out to have lost old nights.

### 4. Gradle supply-chain hardening (#658, PR #683 kavemang)

Upstream's PR is three independent mechanisms. Can't cherry-pick any of the files:
upstream is Android-only under `android/` with its own wrapper; rework is a unified
KMP build at the repo root with a completely different dependency set (Compose
Multiplatform, Room KMP, KSP, native iOS/macOS targets). Each mechanism regenerated
for our tree, at the risk level it actually carries:

- [x] **Gradle distribution SHA-256 pin** (`32765235`) — shipped.
  `gradle/wrapper/gradle-wrapper.properties` now carries
  `distributionSha256Sum=a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612`
  for our gradle-8.8-bin.zip (upstream pinned 8.7). Value cross-verified against two
  official sources (the published `.sha256` and gradle.org/release-checksums). The
  wrapper aborts if the downloaded distribution's hash doesn't match — blocks a
  tampered Gradle. Self-contained, zero build-graph risk. `./gradlew --version` green.
- [x] **Dependency locking** (`1928776c`, `dependencyLocking { lockAllConfigurations() }`
  + `gradle.lockfile`) — shipped. Block added to BOTH `:shared` (root build) and android
  `:app` (mirrors upstream's app-module placement). `shared/gradle.lockfile` (344 lines)
  is the union of both roots' resolution — `--write-locks` preserves entries for
  configurations not resolved in a given run; the android pass left it byte-identical.
  `android/app/gradle.lockfile` 330 lines. Verified: both roots compile green under
  enforcement (androidTarget + iosSimulatorArm64 klib + assembleDebug), and a tampered
  lockfile (coroutines pinned to 1.6.0) fails resolution with lock-constraint errors.
- [x] **`verification-metadata.xml` (per-artifact SHA-256)** — shipped, the deliberate
  way: generated per Gradle root from REAL task graphs, not a blind write.
  Root `gradle/verification-metadata.xml` (503 components) from
  `help + :shared:dependencies + compileDebugKotlinAndroid + compileKotlinIosSimulatorArm64
  + testDebugUnitTest`; android `gradle/verification-metadata.xml` (629 components) from
  `help + :app:dependencies + :app:assembleDebug`. Enforcement verified live on both
  roots (full `dependencies` resolution + real builds all green), and a corrupted
  sha256 blocks the build ("Dependency verification failed … activity-1.2.0.aar").
  `dev.sh` safe: it runs `:app:installDebug`, whose external-dependency graph equals the
  verified `assembleDebug`. konan/Kotlin-Native compiler downloads are outside dependency
  resolution and unaffected. If a future dependency bump trips verification, regenerate
  with `--write-verification-metadata sha256 <same task graph>`.

### 5. Backfill clock retry + Data Range fallback (#700) — **investigated, DECLINED**
- [x] `0d252f1d` retry GET_CLOCK when no correlation establishes — **N/A (iOS-only).**
  Upstream's own note: "Android's historical offload uses type-47 real-unix
  timestamps and does not have the same clock-correlation dependency for day
  assignment." No Kotlin change in the commit. Nothing to port.
- [x] `64f35f8d` Data Range fallback when GET_CLOCK retries exhaust — **DO NOT PORT.**
  The 10-line Android portion seeds `backfiller.clockRef = ClockRef(device =
  strapNewestTs, wall = wallNow)` in `beginBackfill`. Ported verbatim it REGRESSES
  in rework, because our `extractHistoricalStreams` evolved well past upstream's:

  Seeding sets `clockOffset = wallNow - strapNewestTs`. That crosses FIX #72's
  1-day `staleThreshold` (activating the constant-skew shift) whenever the strap's
  newest banked record is >1 day old — which happens for a perfectly HEALTHY strap
  that was simply off-wrist / on charger for a day, not only for a drifted RTC.
  Then rework's own guards turn it into a loss:
  - **Session markers present (normal):** `Backfiller.sessionOldest/NewestUnix` are
    the RAW device-epoch `unix` (`AndroidWhoopBleClient.kt:3303`, `Backfiller.kt:147`).
    FIX #72 shifts every record forward by ~offset, then `plausible()` compares the
    CORRECTED ts against the RAW session window → all records fall outside →
    **entire offload dropped** (total data loss for that sync).
  - **Session markers absent:** corrected ≈ `wallNow`, clears only the absolute
    floor → every record **misdated to ~now** — exactly the bug #700 set out to fix.
  The #471 anti-future guard doesn't rescue it (`corrected` never exceeds
  `wallNow + snapGranularity`, so it is kept, not reverted).

  rework doesn't need the seed anyway: IDENTITY keeps type-47 real-unix, and FIX #72
  + the #547 session-range plausibility gate + #471 anti-future + #928/#1012
  future-skew handling already form a far stronger stale-RTC stack than upstream
  Android had when #700 was written. If genuine drift-recovery for a dead-GET_CLOCK
  4.0 is ever wanted, build it as a purpose-made change with an
  `extractHistoricalStreams` unit test pinning the healthy-off-wrist case — NOT this
  cherry-pick.

### 6. WHOOP protocol decoder fixes
- [x] `80da3bca` v20 (2140 B) historical decoder sample count 50 → 25 (#545) —
  **N/A (Swift-only).** The fix corrects `Interpreter.swift`'s v20 decoder; the
  Kotlin side has no v20 historical decoder at all — `HistoricalStreams.kt:280`
  is `if (version != 18) return null`, byte-identical to upstream Android. Nothing
  to port until a Kotlin v20 decoder is deliberately added (upstream flags that as
  its own decision). No-op.
- [x] `f5f64977` `Whoop5Config`: enable_sig12 value is ASCII '1' (0x31) (#522) —
  ported into `shared/.../protocol/Whoop5Config.kt`, carrying the #103 rider it
  depends on. This fork forked at `e19db18` (before upstream #103 `e532929a`), so
  `enableR22Sequence` had only the 15 judes.club flags — the 16th flag `enable_sig12`
  was never present. Added it at the CORRECTED value (0x31) in one step, so the
  strap's on-connect enable burst now matches the official app's real capture.
  `Whoop5ConfigTest` bumped 15→16 (asserts last flag `enable_sig12` == 0x31);
  `CommandChannelTest` picks up the extra planned SET_CONFIG via `.size` (no change).
  `WHOOP5_DEEP_DATA.md` "15 flags" write-burst line updated to 16. Green JVM +
  iosSimulatorArm64.
- [x] `79600cc4` v20 optical: RawImu length-gate parity + RE evidence (#546 → #577) —
  **N/A (no decoder here).** Fixes upstream Android's `Whoop5RawImu.kt` length gate; this
  tree has no raw-IMU/optical decoder at all (zero `RawImu`/`RawOptical` hits outside this
  doc). Same precedent as `80da3bca`: nothing to port until a raw-IMU decoder is
  deliberately added.
- [x] `e56f45ca` persist the WHOOP 5/MG raw-IMU offload buffer (#423) (#675) —
  **N/A (no decoder here).** Persists the output of that same absent decoder
  (`rawImuSample` table, Room `MIGRATION_20_21`, receive-side capture in
  `WhoopBleClient`); instrument-only upstream ("nothing consumes it yet"). Porting it
  requires first porting the decoder — one decision, take both together or neither.
- [x] Steal upstream's `android/app/src/test/resources/decoder_oracle.json` additions —
  **already in sync.** Compared vector-by-vector against
  `shared/src/androidUnitTest/resources/decoder_oracle.json`: 11/11 frames identical in
  both directions, nothing to steal.

---

## MEDIUM — worth it, more integration work

- [x] Faster history sync (#533): all three commits ported.
  - `5b1b31d5` offload connection-priority toggle — rework never got upstream #477's
    dormant plumbing, so this is the minimal combined port: `GattOps.
    requestConnectionPriorityCompat` + pure decision twins `offloadPriorityOnBegin`/
    `offloadPriorityOnRelease` (companion, `shouldTeardownOnGattThrow` pattern), HIGH at
    `beginBackfill`, BALANCED at `exitBackfilling`/retry, release-on-toggle-off edge
    (upstream's re-review catch), flag cleared on teardown (no BLE op). Behind
    `PuffinExperiment.fastHistorySync` (default OFF — default path zero-BLE-op), Settings
    toggle in the every-model Diagnostics card. `ConnectionPriorityTest` (6) green.
  - `73972540` LE 2M PHY preference (#537) — `GattOps.setPreferredPhyCompat` + pure
    `preferredPhyMaskOnBegin` (always 1M|2M, never 2M alone) / `preferredPhyMaskOnRelease`
    (1M, only if requested) / `phyLabel`; requested at offload START (not connect — the
    handshake-fragility rule, #85/#50), released ONLY on the Settings on→off edge (a PHY
    persists once negotiated), `onPhyUpdate` logs the negotiated PHY. Separate
    `PuffinExperiment.fastLinkPhy` toggle (opposite battery profile from the interval
    lever; the two stack). Android-only by necessity (CoreBluetooth has no app-side PHY
    API). `PreferredPhyTest` (6) green.
  - `64bb9e33` auto-continue cap 6 → 24 (#594) — `MAX_AUTO_CONTINUES = 24` with upstream's
    rationale comment; `BackfillContinuationTest` multi-pass drain made cap-relative
    (cap + 5 days) + new `continues_pastOldSixCap_onDeepBacklog` regression. TUNABLE,
    needs on-strap validation.
  All NOT hardware-validated (no strap here) — opt-in toggles ship to collect field
  reports, matching upstream's own posture.
- [x] Strap model / skin-temp seeding (#716): `bd7e7f1a` + `83e722f8` ported, rework-shaped
  (NOT cherry-pickable — upstream stamps inside its BLE client; rework's registry writes are
  owned by `SourceCoordinator`). New `DeviceRegistryDao.setModel` + `DeviceRegistry.setModel`;
  client exposes a `confirmedFamily: StateFlow<DeviceFamily?>` seam (set at service discovery
  next to `connectedFamily`, cleared on teardown — same pattern as
  `connectedPeripheralAddress`); AppViewModel feeds it to
  `SourceCoordinator.connectedFamilyConfirmed`, which one-time-stamps the seeded generic
  `"WHOOP"` active row to the real generation (`AndroidWhoopModel.displayName`). `83e722f8`
  honoured: `modelStamped` set BEFORE the coroutine launches, so repeat confirmations are
  free (no launch, no registry read). Bonus rework-local fix found during the port:
  `RegistryDayOwnerSource.skinTempFamily` matched ONLY `"WHOOP 4.0"`, but the Add-a-device
  wizard writes `"4.0"` — a wizard-added 4.0 silently took the WHOOP5 /100 skin-temp scale;
  matcher now accepts both conventions. Tests: 4 stamp tests in
  `SourceCoordinatorAdoptionTest` + 2 matcher tests in `RegistryDayOwnerSourceTest`
  (+ `setModel` added to the three fake DAOs). App + shared suites green (shared still
  1656/1660 — the 4 pre-existing locale-whitespace fails).
- [x] Strap restart command (#166): ported at its NET upstream state (`a240f74c` add →
  `4a68c34e` 4.0 probe → `1bf8dc02` retire 4.0 → `e03a6f17` 5.0 framing verified):
  `REBOOT_STRAP(29)` added to `CommandNumber` as the documented narrow exception to the
  destructive-command denylist (empty body, non-destructive — the rename flow already reboots
  as a side effect). 5/MG ONLY on the puffin frame (allow-listed in the client's 5/MG send
  gate); `restartStrap()` refuses a 4.0 outright (upstream #275: ~15 probe attempts showed
  no safe frame reboots a 4.0 — empty bodies ignored, non-empty bodies wedge the link).
  COMMAND_RESPONSE ack logged even on SUCCESS ("Reboot: strap acked result=…"). Devices UI:
  "Restart strap…" menu item gated on `live.connected && live.whoop5Detected` (the
  `1bf8dc02` twin gate), confirmation dialog before sending. The 4.0 reboot PROBE (upstream's
  Test Centre RE tool) deliberately NOT ported — it's a research aid for hardware we can't
  validate against. App + shared suites green (shared 1656/1660, the 4 pre-existing
  locale-whitespace fails).
- [x] Clock-wrong warning (#324): `694ae229` ported, both halves.
  - **Banner half**: pure `futureDatedStrapBanner` companion fun (byte-identical string to
    upstream's Swift/Kotlin twins), driven by the existing `isFutureDatedNewest` predicate so
    the banner and the #928/#1012 auto-continue gate can never disagree. Wired into
    `exitBackfilling` for BOTH outcomes (the reporter's session ended on TIMEOUT, not
    HISTORY_COMPLETE): HISTORY_COMPLETE checks it only on the banked-something path
    (`bankedNothing` keeps its #126 sustained-empty precedence, matching upstream's else-if
    order), and timeout prefers it over "strap went quiet" (5/MG #580 suppression unchanged).
    One-shot log line with the hours-ahead figure. 4 banner tests in
    `EmptyBankingClassifierTest`.
  - **Diagnostics half (#332)**: `extractHistoricalStreams` now tracks the dropped-record
    SPAN (`droppedOldestTs`/`droppedNewestTs` on `StreamBatch`, claimed timestamps of the
    #547-gated drops); `BadClockDiagnostics` pure formatters (isoDay UTC / hoursOffset /
    droppedSpanClause — lives in `com.noop.ble`, not shared protocol, because commonMain has
    no `java.text`) append " (dated 2028-06-20 -> 2029-07-14, 35189h ahead)" to the
    Backfiller's once-per-session bad-clock warning. Upstream's dropped-RTC-event capture
    (RTC_LOST/BOOT/SET_RTC) N/A here — rework's EVENT branch corrects rather than kind-drops.
    `BadClockDiagnosticsTest` (5) green; app + shared suites green (1656/1660, the 4
    pre-existing locale fails).
- [ ] Strap pack voltage in Devices (#592)
- [ ] Low-battery heads-up (#250)
- [ ] Android keep-alive / battery (#386, #228) — check vs our rewritten dataSync FGS
  service layer; may be already-fixed or inapplicable
- [ ] Health Connect on Android 13 (#226)
- [ ] Auto-detected workouts save on Android (#214)
- [ ] Latest Workouts dedupe / multi-source (#200)
- [ ] Imported rides count toward Effort (#137)
- [ ] Manual workouts get HR + calories (#510)
- [ ] Journal import "answered yes" header fix (#631)
- [ ] Smart wake alarm arms more reliably (#34)
- [ ] Sleep times/totals read right after an edit (#259)
- [ ] Automatic sync no longer stalls (#266)

## LOW / skip

- iOS Swift UI work (Apple Health writeback #249, tidier menus #336, pull-to-sync #334,
  day-cycle default, DE/FR/ES translations #326/#453) — Strand is migrating onto shared
  Kotlin; don't invest in the Swift side
- Gemini AI coach on Android (#400) — feature, not fix; separate decision
- Oura support (IBI anchor #677, feature-status probe #710, `OURA_PROTOCOL.md`, three
  implementation plan docs) — whole new device family, separate decision
- Release/AltStore/changelog plumbing

## Docs worth grabbing cheaply

- [ ] `docs/DEVICE_DRIVER_ARCHITECTURE.md` (new, 217 lines)
- [ ] `docs/RR-OPTIMIZATION.md` (new)
- [ ] Updated `docs/PROTOCOL.md`, `docs/BLE_REVERSE_ENGINEERING.md`,
  `docs/WHOOP5_DEEP_DATA.md`, `docs/PRIVACY_SECURITY.md`

---

## Suggested order

1. #195 gap-aware rMSSD
2. #268 median-HR confirm-sleep
3. Decoder fixes (`80da3bca`, `f5f64977`)
4. #700 clock retry / Data Range fallback
5. #658 verification-metadata
6. #201 baseline re-fold
7. Rest of MEDIUM

Item 6 (#201) landed WITHOUT a `STAGING_VERSION` bump (the earlier note here was a
plan-time assumption): the port changes no stored data — it removes a prefs write in the
Settings toggle and relies on the existing watermark-cleared re-score, and the baseline
fold already replays the full stored history each pass. Items 1 (#195) and 2 (#268) also
do NOT ride the sweep: #195 re-derives HRV from raw on every rescore (no stored column to
heal), and #268 is a re-DETECTION change that `forceRestageAll` cannot reach (see
section 3) — both recover naturally via the recompute window, matching upstream.
