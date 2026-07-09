# KMP/CMP Unification Design

Date: 2026-07-09
Status: Approved (pending final spec review)
Repo: https://github.com/TheBoroer/noop-rework (upstream: https://github.com/ryanbr/noop)

## Context and Goals

noop currently ships two fully independent native apps: SwiftUI on iOS/watchOS (116 SwiftUI files) and Jetpack Compose on Android (~40 screens). Logic is also duplicated: the WHOOP BLE protocol, data layer, and analytics exist once in Swift and once in Kotlin. Every feature and fix is written twice.

This fork unifies the codebase on Kotlin Multiplatform (shared logic) plus Compose Multiplatform (shared UI), incrementally, with the app shippable at every phase boundary.

Goals, in priority order:

1. One codebase to maintain: shared logic first, shared UI second.
2. Maintainable, readable, contributor-friendly code and layout. A new developer should onboard by reading the repo.
3. Keep the ability to hand-port upstream protocol and BLE fixes (cherry-pick occasionally; full merge compatibility is explicitly sacrificed).
4. Surfaces: iPhone and Android phone apps. macOS desktop is a later nice-to-have. The watchOS app and complications are dropped.
5. Distribution stays sideload-only (APK, IPA via AltStore, DMG later). No app stores.

Non-goals: hot-code OTA updates (impossible for native code on iOS regardless of framework; AltStore auto-update is the update channel), watchOS support, upstream merge compatibility after Phase 1 begins.

### Why KMP/CMP over the alternatives

- Capacitor: the app is background-BLE-heavy with native alarms, widgets, and foreground services. A web shell would still require maintaining Kotlin and Swift native layers plus a TypeScript app on top (three languages, not one). Upstream cherry-picks would require Kotlin-to-TypeScript translation. Rejected.
- Flutter: covers the same targets, but requires rewriting all logic in Dart, discarding the existing complete Kotlin implementation of the protocol, data, and analytics layers. Upstream cherry-picks would require translation. Rejected.
- KMP/CMP: the Android app already contains a complete Kotlin implementation of everything that matters. It gets promoted to shared code largely as-is, and upstream's Kotlin-side fixes remain hand-portable diffs. Chosen.

## Repo Strategy

- `noop-rework` is a fresh clone of ryanbr/noop at v8.5.2. Remote `origin` is TheBoroer/noop-rework; remote `upstream` is ryanbr/noop, kept for cherry-picks.
- The old mirror repo (local folder `noopapp-noop`, GitHub TheBoroer/noop with branch `main-original`) is frozen as a backup of the pre-fork state and is not developed further.
- Divergence is one-way after Phase 1 starts. Upstream intake becomes manual: watch `git log upstream/main -- android/**/protocol/` (and ble/, analytics/), hand-apply relevant Kotlin diffs to `shared/commonMain`. Same package names are kept to maximize diffability. Process documented in `docs/UPSTREAM.md`.

## Target Module Layout

JetBrains wizard-standard KMP layout, chosen for contributor familiarity over prettier names:

```
noop-rework/
├── shared/                      # KMP module: all logic
│   ├── src/commonMain/kotlin/com/noop/
│   │   ├── protocol/            # WHOOP BLE protocol, frames, commands
│   │   ├── ble/                 # scan/connect/stream logic (Kable)
│   │   ├── data/                # Room KMP entities, DAOs, repositories
│   │   ├── analytics/           # sleep staging, HRV, recovery algorithms
│   │   ├── ingest/  oura/       # import pipelines
│   │   └── update/              # release checker (merged from both platforms)
│   ├── src/androidMain/         # Android BLE adapter, alarm scheduling, file IO
│   ├── src/iosMain/             # CoreBluetooth via Kable, UNNotification alarms, HealthKit bridge
│   ├── src/desktopMain/         # later: macOS JVM adapters
│   └── src/commonTest/          # protocol goldens, analytics tests, backup round-trip
├── composeApp/                  # Compose Multiplatform UI: all screens (android + ios + desktop targets)
├── androidApp/                  # thin shell: manifest, foreground services, widgets, self-update install
├── iosApp/                      # thin Xcode shell: entry, background modes, widgets, AltStore plumbing
└── docs/
```

Existing `com.noop.*` package names are preserved inside `shared` to keep upstream diffs readable.

The pre-existing `android/` and `Strand*` trees coexist with the new modules during migration and are deleted in Phase 3 cleanup.

## Phase Plan

Every phase ends with shippable apps on both platforms.

### Phase 0: Sync (done)

Fork base is upstream v8.5.2. Both apps must build before Phase 1 starts.

### Phase 1: KMP-ify Android logic (est. 1-2 weeks)

Add the `shared` Gradle module. Move Kotlin packages in dependency order: `protocol`, then `analytics`, then `data` (Room 2.7+ KMP), then `ingest`/`oura`, then `update`. Pure logic goes to `commonMain` directly; code touching Android APIs goes to `androidMain` temporarily. The Android app depends on `shared` and behaves identically. iOS is untouched. Ship a normal release from this state.

### Phase 2: iOS consumes shared (est. 2-4 weeks)

`shared` builds as an XCFramework wired into `Strand.xcodeproj`. Swift layers are replaced bottom-up:

1. Protocol parsing (delete the `WhoopModel.swift` cluster).
2. Storage (Swift persistence replaced by shared Room; SQLite via the bundled driver).
3. BLE transport via Kable: scan/stream logic in `commonMain`, CoreBluetooth underneath.

SwiftUI screens stay, refitted to read from shared repositories. SKIE generates clean Swift bindings (Flow becomes AsyncSequence). Risk zone: iOS background BLE semantics. `BLEManager.swift` remains as a thin shell owning `CBCentralManager` state restoration, feeding frames into the shared router. Ship after each layer swap proves stable on-device.

### Phase 3: Compose Multiplatform UI (est. 4-8 weeks, screen by screen)

Add `composeApp`. Android Compose screens move nearly as-is (import swaps from androidx-only artifacts to CMP artifacts; charts and resources need real work). On iOS, CMP embeds in the SwiftUI shell via UIViewController interop; screens migrate tab by tab, shipping mixed UI throughout. Android switches to the `composeApp` target once all screens are moved. Cleanup: delete `Strand/Screens`, the old `android/` tree, `Strand*` trees, and the watch targets (`NOOPWatch`, `NOOPWatchComplications`).

### Phase 4: macOS + polish (optional, later)

CMP desktop target, JVM or native BLE adapter, Conveyor for DMG packaging and auto-update feed. README rewrite for contributor onboarding.

## Native Boundary

Rule for contributors (goes in the README): if it touches an OS API, it is an expect/actual or shell code; if it is a decision, calculation, or byte parsing, it is `commonMain`.

Stays iOS-native (thin `iosApp` shell): app entry and background modes, BGTaskScheduler, CBCentralManager ownership and state restoration, alarm delivery via UNUserNotificationCenter, HealthKit read/write, WidgetKit widgets, Siri Shortcuts / App Intents, AltStore and signing plumbing.

Stays Android-native (thin `androidApp` shell): foreground service for BLE streaming, AlarmManager exact alarms and doze exemptions, notification channels, widgets, APK self-update install intent, Health Connect if present.

Shared owns decisions, native executes them. Example: `commonMain` computes "wake at 06:42"; `expect fun scheduleAlarm(time)` dispatches to AlarmManager or UNNotificationRequest.

## Data Flow and Storage

Single write path:

```
Strap BLE frames (Kable, commonMain)
  -> protocol/ frame parsing -> FrameRouter
  -> data/ Room KMP (same schema and migrations on both platforms)
  -> analytics/ (sleep, HRV, recovery) reads Room, writes derived tables
  -> UI (CMP) collects repository Flows
  -> platform fan-out: widget payloads, HealthKit export, notifications (expect/actual)
```

- Room KMP is the one database. Existing Android entities, DAOs, and migrations port with modest changes. The entire Swift persistence layer is deleted, eliminating schema drift between platforms.
- Imports (WHOOP export, Oura) run through the same `ingest` pipeline into the same tables.
- Settings: a multiplatform settings library replaces the UserDefaults/SharedPreferences pair.
- Backups: plaintext, deliberately. Format stays byte-compatible with upstream noop in both directions: this fork restores noop backups and noop restores this fork's backups, across installs and devices. The `commonMain` port of `DataBackup.kt` must not change the format. Keeping the backup safe is the user's responsibility. File IO via okio expect/actual.
- Encryption: none in the backup path today (verified: upstream's Tink usage is only for the AI coach API key in `AiKeyStore.kt`). Deferred, see below.

## Error Handling

- BLE frames are hostile input. Parsers in `protocol/` return typed results (sealed classes), never throw across the Kotlin/Swift boundary; the shared API surface returns results, not exceptions.
- Reconnect, backoff, and stuck-strap detection (upstream hardened these in 8.2-8.5) port as-is into shared code: one implementation instead of two.
- Crash capture extends to shared code. Kotlin/Native stack traces on iOS require the framework dSYM for symbolication; noted in release docs.

## Testing

- `commonTest` runs protocol and analytics tests on JVM (fast, CI default) and on the iOS simulator (catches platform quirks such as timezone and epoch handling).
- Existing Android unit tests migrate with their code.
- Protocol golden files: recorded real strap frame dumps (via the existing `PuffinFrameRecorder`) as fixtures; parsers must produce identical output on every platform. This is the regression net for upstream cherry-picks: apply the diff, run the goldens.
- Backup round-trip test: export, restore, database equality. Plus a fixture backup generated by upstream noop v8.5.2 to lock cross-fork compatibility.
- UI testing stays light: manual on-device passes per phase (BLE cannot be meaningfully simulated); CMP screenshot tests are optional later and never a phase gate.
- CI: GitHub Actions. JVM tests and Android assemble on every PR; iOS framework compile and simulator tests on release branches.

## Deferred / Candidates for Removal

Deliberately out of scope now, recorded so they are not lost:

- Optional backup encryption (passphrase, libsodium XChaCha20-Poly1305 + Argon2id, one commonMain implementation) if ever wanted.
- watchOS app and complications: dropped; the watch never fed data into noop (phone-to-watch display sync plus a foreground wrist-HR glance only), so no data collection is lost.
- Feature-ripping pass: simplify the app by removing features after the migration stabilizes. Candidates to be chosen then.
- macOS target (Phase 4) and Conveyor packaging.
- CMP screenshot testing.

## Update and Distribution

Sideload-only: APK direct download, IPA via AltStore source JSON, DMG later. In-app update check merges `UpdateCheck.kt` and `UpdateChecker.swift` into one `commonMain` checker against GitHub releases; platform hooks open the release page or trigger the APK install prompt. AltStore auto-updates iOS builds from the source feed. No hot-code push (iOS code signing forbids it for native code); full-build delivery via AltStore fills that role.
