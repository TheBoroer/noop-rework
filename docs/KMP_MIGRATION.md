# KMP/CMP Migration: Deep Analysis and Roadmap

Date: 2026-07-23
Extends: [`docs/superpowers/specs/2026-07-09-kmp-cmp-unification-design.md`](superpowers/specs/2026-07-09-kmp-cmp-unification-design.md) (approved). That spec set the direction; this document replaces its estimates with measured line counts, adds a per-screen conversion cost table, and maps every component type to where it lives today versus where it lands after the migration.

Goal (unchanged from the spec, restated with the owner's words): one unified codebase. All analysis, intelligence, database, sync logic, and the UI itself run the same code on every platform, so a bug reproduced on an Android emulator is the same bug a user hits on iOS, and an imported backup behaves identically everywhere. Only OS integrations (BLE OS layer, Apple Health, Health Connect, alarms, widgets, notifications delivery) stay per-platform, behind thin seams.

---

## 1. Where the migration stands today

| Phase | Content | Status |
|---|---|---|
| 0 | Fork sync at upstream v8.5.2, both apps build | Done |
| 1 | `shared` KMP module, Android logic moved in (protocol, analytics, data, ingest, oura, update) | Done, merged |
| 2a | Shared groundwork for iOS | Done |
| 2b | iOS consumes `Shared.xcframework` (SKIE 0.10.13, Kotlin 2.1.21); Swift protocol layer delegates to Kotlin | Done |
| 2c-1 | Room KMP storage cutover, both platforms on one database (`noop.db`) | Done (PR #4) |
| 2c-2 | GRDB drain, Kable BLE core in `commonMain` (#48) | Done except #65 |
| #65 | Full WhoopStore GRDB removal (plan exists: T1-T9) | **Pending** |
| 3 | Compose Multiplatform UI (`composeApp`) | **Not started** (no `composeApp/` module exists) |
| 4 | macOS desktop target, Conveyor packaging | Not started |

Two important corrections to the mental model:

- The **database is already unified**. Room KMP in `commonMain` is the single write path on Android, iOS, and macOS since Phase 2c-1. Backup restore across platforms already reproduces the same data. #65 only deletes the dead Swift GRDB fallback.
- The **Kable BLE core already exists in `commonMain`** (`shared/src/commonMain/kotlin/com/noop/ble/`: `BleSession`, `BleScanner`, `FrameTransport`, `CommandChannel`, `CommandPacer`, `Backfiller`, `RealtimePolicy`, `OutboxCodec`, `WhoopBleClient`), exercised by the macOS harnesses. But the **Android app does not use it yet**: it still runs its own `AndroidWhoopBleClient.kt` (5,857 lines), and Strand still runs `Strand/BLE/` (6,169 lines). Three BLE policy implementations exist right now. Unifying them is a required pre-Phase-3 step (section 6).

---

## 2. Component map: where each type of logic lives

"Shared" means one implementation in Kotlin `commonMain`, running byte-identically on every platform.

| Component type | Today | After migration |
|---|---|---|
| WHOOP protocol (frame parsing, CRC, command frames, R22 config, historical decode) | **Shared** (`commonMain/protocol`, 15 files). Swift delegates via XCFramework since Phase 2b | Shared (unchanged) |
| Oura protocol | **Shared** (`commonMain/oura`) + Swift twin (`Packages/OuraProtocol`, 2,191) | Shared; Swift twin deleted |
| Database (entities, DAOs, migrations, backup format) | **Shared** (Room KMP, `commonMain/data`, both platforms on it) | Shared; Swift GRDB leftover deleted (#65) |
| BLE policy (reconnect/backoff, backfill pacing, command sequencing, stuck-strap detection) | **Triplicated**: `commonMain/ble` Kable core (unused by apps), `android/app/ble` (12,471), `Strand/BLE` (6,169) | Shared (Kable core becomes the only one) |
| BLE OS transport | Per-platform (Android BluetoothGatt; iOS/macOS CoreBluetooth) | Per-platform, but **under** Kable: only CBCentralManager state restoration (iOS) and the foreground service (Android) remain hand-written |
| Strap sync / backfill orchestration | Duplicated (`AndroidBackfiller`, shared `Backfiller`, Swift) | Shared |
| Analytics and intelligence (sleep staging, HRV, recovery, strain, scoring) | **Split**: `commonMain/analytics` (73 files) + `shared/androidMain` (`IntelligenceEngine`, `AnalyticsEngineDay`, 9 files) + full Swift twin (`Packages/StrandAnalytics`, 17,646) | Shared only; Swift twin deleted |
| Importers/exporters (WHOOP CSV, Apple Health export, Xiaomi, lifting, nutrition, lab markers) | `shared/androidMain/ingest` (12 files) + Swift twin (`Packages/StrandImport`, 7,944) | Shared (parsing in `commonMain`, file IO via okio expect/actual) |
| Apple Health / Health Connect live integration | Per-platform | **Per-platform (by design)**, behind one shared `HealthBridge` interface |
| UI: screens, cards, menus, graphs | **Duplicated**: Jetpack Compose (`android/app/ui`, 95 files, 65,436) + SwiftUI (`Strand/Screens` 44,679 + `Packages/StrandDesign` 7,853) | **Shared** (`composeApp`, Compose Multiplatform). SwiftUI deleted |
| Charts | `Charts.kt` (897 lines, pure Compose Canvas) + Swift equivalents | Shared as-is (Canvas is fully portable in CMP), pixel-identical graphs on all platforms |
| Alarms (smart alarm decision) | Decision partly shared; delivery duplicated | Decision shared; delivery per-platform (`expect fun scheduleAlarm` to AlarmManager / UNUserNotificationCenter) |
| Notifications content | Duplicated | Content decisions shared; channel/delivery per-platform |
| Widgets / menu bar | Per-platform (Glance widgets, WidgetKit, `Strand/MenuBar`) | Per-platform (thin, reads shared repositories) |
| AI coach | `android/app/ai` (978) + `Strand/AI` (1,215) | Shared (Ktor HTTP in `commonMain`); API key storage per-platform keystore |
| Update check / self-update | `shared/androidMain/update/UpdateCheck.kt` + Swift checker | Check shared; install hook per-platform (APK intent / AltStore) |
| Background scheduling | Per-platform (WorkManager, BGTaskScheduler) | Per-platform shells; the decision "what work to run" shared |
| Settings storage | SharedPreferences vs UserDefaults | Shared via multiplatform-settings |

Bottom line for the components you named: **analysis, strap data sync logic, frame decoding, and the database are shared code** (some already, the rest after the hoists in section 5). **Core Bluetooth OS plumbing, Apple Health, Health Connect stay per-platform**, exactly as you intend.

---

## 3. Line-count census and percentage breakdown

Production code only (tests excluded). Measured 2026-07-23.

### Today (~250,700 LOC total)

| Bucket | LOC | Share |
|---|---|---|
| Shared, runs everywhere (`shared/commonMain`) | 29,476 | **12%** |
| Android-only Kotlin (`shared/androidMain` 12,589 + `android/app` 84,455) | 97,044 | 39% |
| Apple-only (Strand 77,393 + Packages sources 45,138 + apple/macos Kotlin glue 1,672) | 124,203 | 49% |

Of the Apple 49%, roughly 75k lines are functional duplicates of Kotlin code (protocol, analytics, import, storage, BLE policy, and a whole second UI). That duplication is the parity-drift machine you are describing.

### After full migration (estimated ~123,000 LOC total)

| Bucket | LOC (est.) | Share |
|---|---|---|
| Shared logic (`commonMain`: today's 29.5k + hoisted analytics/ingest ~11k + unified BLE policy ~6k + AI/alarm/notif decisions ~2.5k) | ~49,000 | 40% |
| Shared UI (`composeApp`: ported Android Compose minus the activity/permission shell) | ~62,000 | 50% |
| Android-only (foreground service, AlarmManager, Glance widgets, Health Connect, APK install, BLE adapter glue) | ~6,500 | **5%** |
| iOS-only (Swift shell: CBCentral restoration, BGTask, UNNotification, HealthKit, WidgetKit, AltStore plumbing) | ~5,500 | **4.5%** |

**Headline numbers:**

- **~90% of all code shared, ~5% Android-only, ~4.5% iOS-only.**
- Per shipped app: each platform's binary is **~95% shared code**.
- Total codebase shrinks **~250k → ~123k lines (roughly half)**: ~122k Swift and ~19k duplicated Kotlin get deleted.
- Everything a user-visible bug can live in (decode, analytics, database, sync policy, UI layout, chart rendering) is in the shared 90%. Reproducing an iOS report on an Android emulator with the user's backup becomes the default workflow.

The macOS desktop target (Phase 4) adds only a JVM/native BLE adapter and packaging on top of the same shared 90%.

---

## 4. Per-screen CMP conversion cost

Method: every file in `android/app/src/main/java/com/noop/ui/` (95 files, 65,436 lines) scored by Android-API import count and platform-call density (`LocalContext`, `SharedPreferences`, `Intent`, `Activity`, `ContextCompat`). Jetpack Compose source is largely CMP-compatible; cost is proportional to platform-call density, not size.

### Tier 1: mechanical (import swap + resource references only)

~55 files, ~25,000 lines. Zero or near-zero platform calls. Examples with LOC:

`AppChangelog` (2,465), `AddDeviceWizard` (1,703), `Components` (1,408), `StressScreen` (1,334), `LiveScreen` (1,258), `CompareScreen` (1,097), `TrendsScreen` (1,029), `DevicesScreen` (967), `Charts` (897), `TrendsExploreScreen` (873), `InsightsHubScreen` (664), `CoupledScreen` (607), `IntervalsScreen` (589), `JournalLog` (565), `HrvSnapshotScreen` (532), `IntelligenceScreen` (512), `LiveSessionScreen` (508), `StepsCalibrationScreen` (497), `FullDayChartScreen` (488), `FusedRecordScreen` (483), `SkinTempCardsScreen` (479), `UpdatesInboxScreen` (430), `TimeOfDayBackground` (408), the entire Liquid rendering suite (`LiquidRender`, `LiquidSky`, `LiquidPrimitives`, `LiquidSim`, `LiquidScreenSky`, `LiquidMotion`, ~1,400 combined), plus ~25 smaller pure-Compose files.

Cost: **1-2 weeks for the whole tier.** The custom Canvas chart code and the Liquid visual effects port unchanged; graphs render pixel-identical on iOS.

### Tier 2: moderate (isolated platform touches → swap to shared seams)

~28 files, ~32,000 lines. A handful of `LocalContext`/prefs/Intent uses each, replaced by the seam layer (section 4b). Examples:

| File | LOC | Platform touches | What needs replacing |
|---|---|---|---|
| `TodayScreen` | 5,928 | 22 | prefs reads, share intents |
| `SleepScreen` | 3,660 | 5 | prefs, one share sheet |
| `HealthScreen` | 2,378 | 6 | prefs, export |
| `AppViewModel` | 2,299 | 26 | context injection, prefs, importer wiring |
| `WorkoutsScreen` | 2,008 | 13 | prefs, GPX export |
| `InsightsScreen` | 1,769 | 25 | prefs-heavy |
| `BreatheScreen` | 1,346 | 7 | haptics seam |
| `OnboardingScreen` | 1,227 | 23 | permission flows |
| `AppRoot` | 926 | 9 | navigation host, back handling |
| `LabBookScreen` | 879 | 7 | file picker |
| `CoachScreen` | 796 | 5 | key store seam |
| `AutomationsScreen` | 758 | 5 | prefs |
| `WorkoutEditing` | 501 | 11 | prefs |
| plus ~15 more in the 300-600 range | | | |

Cost: **3-4 weeks.** Mostly find-replace onto the seam interfaces once those exist; `TodayScreen`/`SleepScreen` are big but their platform touches are shallow.

### Tier 3: platform-heavy (real rework)

~12 files, ~8,500 lines. These ARE the platform integration and get split: shared UI face + per-platform actual.

| File | LOC | Why hard |
|---|---|---|
| `SettingsScreen` | 3,063 | 54 platform calls: battery exemption intents, OEM autostart, notification settings deep links, file pickers |
| `DataSourcesScreen` | 1,094 | 35 calls: SAF document pickers, Health Connect permission launcher |
| `NotificationsSettingsScreen` | 994 | channel management is Android-only; iOS variant differs |
| `MainActivity` | 846 | dies; replaced by `composeApp` entry + thin `androidApp` activity |
| `TrendsReport` | 656 | PDF/print framework |
| `AppleHealthScreen` | 541 | import UX around platform files |
| `BackupSync` + `BackupSyncScreen` | 810 | SAF, WorkManager scheduling |
| `SmartAlarmScreen` | 461 | exact-alarm permission UX |
| `ProfileAvatar` | 274 | image picking/cropping |
| `ConnectionHelp` | 170 | 13 platform calls, settings intents |
| `BlePermissions` | 114 | permission model differs per OS; becomes expect/actual |
| `DebugExportScheduler` | 132 | WorkManager |

Cost: **~3 weeks**, including designing the iOS behavior for each (some sections simply hide on iOS, e.g. OEM battery blocks).

### 4b. One-time seam infrastructure (prerequisite for Tiers 2-3)

New `commonMain` interfaces + per-platform actuals, ~1-2 weeks:

- `multiplatform-settings` replacing SharedPreferences/UserDefaults (NoopPrefs and the ~10 `*Prefs.kt` files move nearly as-is)
- File pickers/SAF → FileKit or hand-rolled expect/actual
- Share/export sheet, open-URL, haptics, keep-screen-on
- Permission flow expect/actual (BLE, notifications, exact alarm / none on iOS)
- CMP resources (`Res.string/drawable/font`) replacing `R.*`
- Navigation: current Compose navigation maps to CMP-compatible navigation directly

### Phase 3 total: **8-11 weeks** (infra 1-2, Tier 1 1-2, Tier 2 3-4, Tier 3 3, plus iOS embed/stabilization overlap). Matches the spec's 4-8 week screen-by-screen estimate at the optimistic end; the measured platform-call density in Settings/DataSources/Onboarding pushes the realistic end higher.

---

## 5. Logic hoist inventory (`shared/androidMain` → `commonMain`)

12,589 lines sit in `shared/androidMain`. Measured blockers (files with `java.*`/`android.*` imports):

| File | Blocker imports | Fix |
|---|---|---|
| `ingest/*` (12 importers, ~8k lines) | 3-11 each (`java.time`, `java.text`, Locale formatting; some XML/zip) | kotlinx-datetime, shared number-format helpers (exist in `util/LocalTimeFormat` expect/actual pattern already), okio for zip/streams, XML via a small multiplatform parser for the Apple Health export |
| `analytics/IntelligenceEngine.kt`, `AnalyticsEngineDay.kt` + 7 more | **None found** | Hoist nearly free; only their imports of androidMain ingest types pin them. Move after (or alongside) the importers |
| `data/DemoSeeder`, `NapStore`, `SleepSessionNapShape` | 3-4 | Same datetime treatment |
| `data/WhoopDatabase.android.kt`, `SqliteQuickCheck.android.kt`, `CorruptionPreservingOpenHelperFactory` | Room/SQLite glue | Stays androidMain (legitimate actuals) |
| `oura/OuraDriver`, `Commands`, `Auth` | Android BLE usage | Re-home onto the shared Kable core, same move as WHOOP BLE |
| `update/UpdateCheck.kt` | 1 | Ktor + kotlinx-serialization, trivial |
| `util/LocalTimeFormat.android.kt` | expect/actual by design | Stays |

Estimated hoistable: **~11k of 12.6k lines.** Cost: **1-2 weeks.** Watch item: the 4 known locale-whitespace test pins (NNBSP) will need re-pinning when formatting moves off JDK locale data; that is desirable (identical formatting on all platforms ends that class of drift).

---

## 6. BLE unification (pre-Phase-3, the riskiest piece)

Move Android and iOS onto the `commonMain` Kable core so BLE policy is single-sourced:

1. Port the policy deltas that currently live only in `AndroidWhoopBleClient` (this fork's recent work: BackfillPolicy gating, connection-priority/PHY escalation twins, dead-binder safeGatt, connectGeneration, reconnect dwell, alarm readback) into the shared core's pure-decision layer. The pure-twin test pattern already used makes this mostly mechanical.
2. `androidApp` keeps only: foreground service, connection-priority/PHY calls (Android-only APIs stay behind a Kable-adjacent seam), bond handling.
3. iOS: `BLEManager.swift` shrinks to CBCentralManager ownership + state restoration feeding the shared router (per spec).
4. Hardware checkpoints per flow on the WHOOP 4.0 strap (scan, connect, live HR, backfill, alarm set) before deleting either legacy client.

Cost: **2-3 weeks + on-device soak.** Risk: iOS background BLE semantics; mitigated by keeping the Swift restoration shell and shipping behind a toggle first.

---

## 7. Pros and cons of the CMP shared-UI decision

Pros:

- **One UI implementation.** Cards, menus, graphs identical on all platforms; the Capacitor-style mental model, but compiled native with no webview.
- **Bug parity by construction.** Same decode, same DB, same analytics, same rendering: an iOS bug report + the user's backup reproduces on an Android emulator. Parity drift (feature exists on one platform only) becomes structurally impossible for shared surfaces.
- **The Android UI is already Compose.** CMP is Compose; Tier 1 (~25k lines including all custom charts and visual effects) moves nearly untouched. No other framework offers this discount.
- **Halves the codebase** (~250k → ~123k) and deletes the twin-maintenance workflow (UPSTREAM_SYNC Swift-twin ports stop existing as a category).
- Upstream cherry-picks stay Kotlin-to-Kotlin.

Cons (accepted, with mitigations):

- **iOS app stops feeling Apple-native.** Accepted explicitly (unified look preferred).
- **CMP-on-iOS maturity:** text-input/IME edge cases, scroll physics differences, weaker accessibility (VoiceOver support is improving but behind SwiftUI). Mitigation: tab-by-tab rollout inside the SwiftUI shell, keep the old screen until the CMP one passes on-device.
- **Binary size** grows on iOS (Compose runtime + Skia, roughly +10-20 MB). Sideload distribution makes this a non-issue for review, still worth noting.
- **Kotlin/Native debugging on iOS** needs the framework dSYM; crash symbolication workflow must be set up before Phase 3 ships (already noted in the spec).
- **Throwaway work exists either way:** ~52k lines of SwiftUI get deleted. Sunk cost; keeping them is what creates the drift.
- watchOS stays dropped (CMP has no watch target; the watch never collected data).

---

## 8. Execution order (remaining work)

| Step | What | Est. | Gate |
|---|---|---|---|
| 1 | #65 GRDB removal (plan T1-T9 already written) | ~1 wk | Swift test suite + old-backup restore |
| 2 | Logic hoists: importers + IntelligenceEngine/AnalyticsEngineDay + oura to `commonMain` (section 5) | 1-2 wks | commonTest goldens pass on JVM + iOS sim |
| 3 | BLE unification on shared Kable core (section 6) | 2-3 wks | Hardware checkpoints on WHOOP 4.0 |
| 4 | Delete Swift logic twins (StrandAnalytics, StrandImport, WhoopStore remainder, OuraProtocol) as Strand consumes shared | 1-2 wks | Parity spot-checks via backup import |
| 5 | `composeApp` bootstrap + seam infra (section 4b) | 1-2 wks | App runs both platforms with one migrated screen |
| 6 | Screen waves: Tier 1 → Tier 2 → Tier 3 (section 4), shipping mixed UI on iOS throughout | 7-9 wks | Per-tab on-device pass |
| 7 | Shell thinning + deletion: `android/` tree, `Strand/Screens`, `StrandDesign`, watch targets; `androidApp`/`iosApp` finalized | 1-2 wks | Full release both platforms |
| 8 | Phase 4: macOS CMP desktop + Conveyor | later | optional |

Total to the unified-UI end state: **roughly 4-5 months** of focused work, shippable at every step boundary.

---

## 9. What can never be shared (the permanent ~10%)

For expectation-setting: OS BLE stacks under Kable, CBCentralManager state restoration, Android foreground service + doze/OEM battery handling, AlarmManager vs UNUserNotificationCenter delivery, HealthKit vs Health Connect, widgets (Glance vs WidgetKit), notification channels, app entry/lifecycle, signing/distribution (APK vs AltStore), and permission dialogs. Each is a thin executor of a shared decision; target size ~5-6.5k lines per platform. Bugs in this layer are, as you said, clear-cut: they can only be platform plumbing.
