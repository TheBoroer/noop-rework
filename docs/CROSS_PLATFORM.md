# Cross-platform architecture — macOS, iOS & Android

NOOP ships three clients. They look different and are written in two languages, but they implement the
**same on-device pipeline**: read a WHOOP strap over BLE → decode the protocol → persist locally →
score recovery/strain/sleep → render. This doc is the map of **what's shared, what's mirrored, and how
to add a feature that should land on more than one of them** — so a new capability is built once
(conceptually) and ported deliberately, not reinvented three times with drift.

> New to the codebase? Pair this with [`ARCHITECTURE.md`](ARCHITECTURE.md) (module map),
> [`IOS.md`](IOS.md) (the iOS target + fold-in lessons) and [`ANDROID.md`](ANDROID.md).

## The three clients

| Client | Language / UI | Code home | Shares with |
|---|---|---|---|
| **macOS** | Swift + SwiftUI/AppKit | `Strand/` (app) + `Packages/` | the reference implementation; shares the 5 Swift packages with iOS |
| **iOS** | Swift + SwiftUI/UIKit/WidgetKit/HealthKit | `StrandiOS/` + most of `Strand/` + `Packages/` | **shares almost everything with macOS** at the source level (folded in v1.94, build-from-source) |
| **Android** | Kotlin + Jetpack Compose | `android/` | a **hand-ported parity twin** of the Swift logic — no shared binary, mirrored value-for-value |

## Two sharing models, by language boundary

### macOS ⇄ iOS — *real* shared source (the 5 Swift packages)

Everything platform-neutral lives in five SwiftPM packages under [`Packages/`](../Packages/), each
declaring **both** `.macOS(.v13)` and `.iOS(.v16)`. Both app targets depend on them unchanged:

- **`WhoopProtocol`** — BLE frame parsing, CRC, command/event/historical decode (the reverse-engineering core). *Never* imports CoreBluetooth or any UI framework; exposes GATT UUIDs as plain strings the app wraps in `CBUUID`.
- **`WhoopStore`**: on-device SQLite persistence (decoded streams, daily/sleep/workout caches). Same Swift API across macOS and iOS; as of Phase 2c-1 its storage is backed by the **shared Kotlin Room database** (the same store Android uses, via `Shared.xcframework`), so this is now genuinely shared with Android too, not just parity-mirrored. GRDB is retained only for the device-local raw outbox and BLE cursors (removed in Phase 2c-2). See [`DATA_MODEL.md`](DATA_MODEL.md) and the Phase 2c-1 appendix in [`superpowers/plans/phase1-baseline.md`](superpowers/plans/phase1-baseline.md).
- **`StrandAnalytics`** — HRV / recovery / strain / sleep / calories / correlation math. Pure computation.
- **`StrandImport`** — WHOOP CSV + Apple Health (`export.xml`) importers.
- **`StrandDesign`** — the SwiftUI design system (palette, components, charts).

**Rule of thumb:** if logic is platform-neutral and would otherwise be duplicated between the macOS and
iOS apps, it belongs in a package, behind a UI-framework-free API. The app targets hold only the
SwiftUI screens + the platform service shims.

### macOS/iOS ⇄ Android — *parity*, not shared code

Android is a separate Kotlin reimplementation. There is no Kotlin Multiplatform / shared binary — the
protocol decode, the analytics, and the scoring are **hand-ported from the Swift packages and kept in
lockstep**. This is a deliberate trade-off: KMP would couple the two toolchains and the Swift core is
small enough that a disciplined mirror is cheaper than the integration tax. The discipline that keeps
it honest:

- **Value-for-value parity.** A Swift change to decode/analytics/scoring gets the same change in the
  Kotlin twin (`android/.../protocol`, `.../analytics`, `.../data`). Field names, formulas, thresholds,
  and constants match. Source comments cross-reference (e.g. `STARTER_JOURNAL_QUESTIONS` notes "Mirrors
  macOS `JournalCatalogStore.starterQuestions` value-for-value").
- **Tests on both.** Pure logic is unit-tested on each platform with the *same* cases (e.g. the #137
  `ManualWorkoutRescore` and the journal hidden-filter tests exist in `swift test` *and* JUnit form).
- **The changelog is mirrored byte-for-byte.** `Strand/System/AppChangelog.swift`,
  `android/.../ui/AppChangelog.kt` and `CHANGELOG.md` tell the same story; releases ship in lockstep.

## The `Platform.swift` shim (macOS vs iOS UI frameworks)

Where macOS and iOS differ only in the *UI framework* (AppKit vs UIKit), don't fork the call site —
route it through [`Strand/System/Platform.swift`](../Strand/System/Platform.swift):

```swift
PlatformImage              // NSImage on macOS, UIImage on iOS
Image(platformImage:)      // build a SwiftUI Image from either
PlatformPasteboard.copy(_) // NSPasteboard / UIPasteboard
PlatformOpen.url(_)        // NSWorkspace.open / UIApplication.open  (@MainActor)
```

For genuinely platform-*specific* behaviour (no shared abstraction makes sense), use `#if os(macOS)` /
`#if os(iOS)` (or `#if canImport(AppKit)` / `#elseif canImport(UIKit)`), and exclude wholly-macOS files
from the iOS target in `project.yml`. The enumerated macOS-only surface and how each piece was handled
is in [`IOS.md` → "Lessons from the fold-in"](IOS.md).

## Adding a feature across clients — the playbook

1. **Find the seam.** Is the new logic platform-neutral (decode/analytics/scoring/storage)? Then it's a
   *package* change on Swift + a *parity* change on Kotlin. Is it UI/platform-service (a screen, a
   widget, a permission flow)? Then it's per-client.
2. **Swift first (it's the reference).** Put neutral logic in the right package with a small, pure,
   **unit-tested** API (scalars in/out beats passing app types around — it keeps the helper out of the
   WhoopStore/app module graph and trivially testable). Wire it into the macOS app and the iOS app
   (usually the same call — they share the screens).
3. **Mirror to Kotlin.** Reimplement the pure helper in `android/.../analytics` (or the matching pkg),
   matching formulas/constants exactly, with the **same** unit-test cases.
4. **UI per client.** SwiftUI screen (shared macOS+iOS, guarded where frameworks differ) + a Compose
   screen on Android, each built from its design system (`StrandDesign` / `NoopType`+`Palette`).
5. **Mirror the changelog** in all three surfaces; **bump all version surfaces** in lockstep (see
   [`BUILD.md`](BUILD.md) / the release process). Verify: `swift test` (packages) + the macOS/iOS
   `app-build` CI + `:app:testFullDebugUnitTest` (Android).

Worked examples from recent releases: **#137** (workout re-score) — a pure `ManualWorkoutRescore`
helper in `StrandAnalytics` + a Kotlin twin, both wired into each platform's `IntelligenceEngine`;
**#131** (local-LLM AI Coach) — a provider abstraction added on both; **#140** (journal curation) —
shared model + per-client edit UI. Each is "one idea, ported deliberately."

## What is *not* shared (and why that's fine)

- **No KMP / shared binary with Android** — see the parity rationale above.
- **Android reimplements the design system** (Compose) rather than sharing `StrandDesign` (SwiftUI).
- **Platform-only features stay platform-only:** macOS menu-bar extra / screen-lock / Shortcuts;
  iOS WidgetKit + Live Activity + two-way HealthKit; Android home-screen widget + Health Connect.
  These are *front doors* to the same shared core, not duplicated logic.

---

*Everything stays on the device on every client. The platforms differ at the edges — the front door and
the OS integrations — but the strap protocol, the math, and your data are the same NOOP underneath.*
