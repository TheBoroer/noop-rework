# Phase 2b: iOS (and macOS) Consume Shared Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Kotlin `shared` module ships as an XCFramework (iOS + macOS slices) with SKIE bindings, wired into the Xcode build via SPM binaryTarget inside the existing Swift packages, and the WhoopProtocol package's frame-parsing internals delegate to shared Kotlin with all 37 Swift protocol test files staying green.

**Architecture:** Delegation happens INSIDE the Swift packages, not at app call sites. Recon fact that dictates this: the iOS app (NOOPiOS) and macOS app (Strand) compile the same 74 `Strand/` source files, so per-app import swaps would need platform shims in every file. Instead: `shared` gains macOS targets (its data layer is already pure commonMain), builds one `Shared.xcframework`, `Packages/WhoopProtocol` declares it as an SPM binaryTarget, and Swift file bodies inside the package become thin wrappers over the Kotlin implementations. App targets and their 74 consumer files change zero lines. The 37 Swift test files in WhoopProtocolTests keep testing the SAME Swift API surface and become the cross-language parity net. WhoopStore/GRDB replacement and BLEManager thinning stay Phase 2c.

**Tech Stack:** Kotlin 2.1.21, Kotlin/Native (iosArm64, iosSimulatorArm64, macosArm64, macosX64), Gradle XCFramework assembly, SKIE (Touchlab), XcodeGen 2.45.4, SPM binaryTarget, Xcode 26.6.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-09-kmp-cmp-unification-design.md`. Phase 2a close-out appendix in `docs/superpowers/plans/phase1-baseline.md`.
- Protocol BYTE semantics exactly preserved: the WhoopProtocolTests suite (37 test files) is the acceptance gate for every delegation task and must pass unchanged (test-file edits allowed ONLY for import/availability mechanics, never expectations).
- Android app untouched: no `android/` or `androidApp` changes in this phase (shared/build.gradle.kts changes must keep the Android gate green).
- Package names `com.noop.*` verbatim; Swift public API surfaces of WhoopProtocol stay source-compatible for the 74 consumer files.
- Before ANY gradle command: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Gradle runs from `android/`.
- Full cross-platform gate (every task ends green):
  `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:assembleDebug :app:testDemoDebugUnitTest :app:testFullDebugUnitTest` (counts non-decreasing from JVM 1477 / iOS 778 / app 940x2)
  plus, from repo root once Xcode wiring lands (Task 3 onward): `xcodegen generate && xcodebuild -scheme NOOPiOS -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -5` and `xcodebuild -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -5` and `swift test --package-path Packages/WhoopProtocol 2>&1 | tail -5`.
- New tool versions (SKIE) chosen by the klib-ABI method proven in Phase 2a: highest stable whose build passes under Kotlin 2.1.21; record chosen version + decline reasons in the commit body.
- Device policy: no physical-device interaction; simulators fine.
- No em-dashes in newly authored content. `/usr/bin/grep` for scans.
- Repo is public: no real personal data, no signing secrets in commits.
- Generated Xcode project stays git-ignored; `project.yml` and `Package.swift` files are the source of truth. The XCFramework build output stays git-ignored (add pattern in Task 1); CI builds it fresh.

---

### Task 1: shared gains macOS targets + XCFramework assembly

**Files:**
- Modify: `shared/build.gradle.kts` (macosArm64/macosX64 targets, XCFramework config, framework baseName)
- Modify: `.gitignore` (ignore `shared/build`-external xcframework output dir if placed outside build/)
- Possibly move: files under `shared/src/iosMain/` and `shared/src/iosTest/` to `appleMain`/`appleTest` where they are Darwin-generic (NSDateFormatter, NSTemporaryDirectory, platform.posix)

**Interfaces:**
- Consumes: existing shared module (Phase 2a state).
- Produces: `./gradlew :shared:assembleSharedReleaseXCFramework` (and Debug variant) emitting `shared/build/XCFrameworks/release/Shared.xcframework` containing ios-arm64, ios-arm64-simulator, macos-arm64_x86_64 slices. Framework baseName `Shared`. Later tasks consume that path.

- [ ] **Step 1: Add targets and XCFramework config**

In `shared/build.gradle.kts`, extend the kotlin block (imports at top of file: `import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework`):

```kotlin
kotlin {
    // existing androidTarget { ... } stays

    val xcf = XCFramework("Shared")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        macosArm64(),
        macosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            xcf.add(this)
        }
    }
    // sourceSets: existing config stays
}
```

Note: `iosArm64()`/`iosSimulatorArm64()` already exist; converting them to this list form must not change their source-set names.

- [ ] **Step 2: Introduce appleMain/appleTest and generalize Darwin actuals**

The default hierarchy template already provides `appleMain` between `commonMain` and ios/macos source sets. Move each file under `shared/src/iosMain/kotlin/` to `shared/src/appleMain/kotlin/` IF its imports are Darwin-generic (`platform.Foundation.*`, `platform.posix.*`, `kotlinx.cinterop`): expected movers include `LocalTimeFormat.ios.kt` (NSDateFormatter exists on macOS), `WhoopDatabase.ios.kt` (BundledSQLiteDriver supports macOS), `SqliteQuickCheck.ios.kt`, and the expect/actual test helpers in `iosTest` to `appleTest`. Rename file suffixes `.ios.kt` to `.apple.kt` as you move. A file using UIKit or iOS-only API stays iosMain (expected: none; report any).

Then compile all four native targets:

Run: `cd android && ./gradlew :shared:compileKotlinMacosArm64 :shared:compileKotlinMacosX64 :shared:compileKotlinIosSimulatorArm64 --console=plain 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. Failures here are missing macOS actuals: fix by the same move-or-add pattern, and report each.

- [ ] **Step 3: Run macOS native tests**

Run: `./gradlew :shared:macosArm64Test --console=plain 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL with the commonTest suite (roughly the iOS count) green on macOS. The fixture-restore test must pass on macOS too (env plumbing: extend the existing test-task environment block to cover macOS test tasks; the `SIMCTL_CHILD_` prefix is iOS-simulator-only, macOS test tasks take plain environment).

- [ ] **Step 4: Assemble the XCFramework**

Run: `./gradlew :shared:assembleSharedReleaseXCFramework --console=plain 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL; verify slices: `ls shared/build/XCFrameworks/release/Shared.xcframework` shows `ios-arm64`, `ios-arm64_x86_64-simulator` or `ios-arm64-simulator`, `macos-arm64_x86_64`. Record exact slice names in the report (Task 3's binaryTarget consumes the path, slice names are informational).

- [ ] **Step 5: Full Android gate still green, commit**

Run the Global Constraints gradle gate.

```bash
git add -A
git commit -m "build: shared ships as Shared.xcframework with ios and macos slices"
```

---

### Task 2: SKIE

**Files:**
- Modify: `shared/build.gradle.kts` (SKIE plugin)
- Modify: `android/build.gradle.kts` (plugin registration if SKIE requires it at root; check SKIE docs pattern: usually only the module plugin block)

**Interfaces:**
- Consumes: Task 1 framework config.
- Produces: SKIE-enhanced framework: Kotlin suspend functions become Swift async, Flow becomes AsyncSequence, sealed classes become Swift enums with associated values. Task 4+ Swift wrappers rely on these shapes.

- [ ] **Step 1: Determine the SKIE version for Kotlin 2.1.21**

SKIE versions pin exact Kotlin compiler versions. Check https://central.sonatype.com/artifact/co.touchlab.skie/gradle-plugin versions (curl the maven-metadata.xml at https://repo1.maven.org/maven2/co/touchlab/skie/gradle-plugin/maven-metadata.xml) and SKIE's compatibility table (https://skie.touchlab.co/intro#kotlin-compatibility fetched via curl if reachable). Pick the newest stable supporting Kotlin 2.1.21 exactly. If NO stable SKIE supports 2.1.21, STOP and report BLOCKED with the compatibility table you found (fallback decision, controller-level: bump Kotlin or skip SKIE).

- [ ] **Step 2: Apply**

`shared/build.gradle.kts` plugins block:

```kotlin
    id("co.touchlab.skie") version "<CHOSEN>"
```

- [ ] **Step 3: Rebuild framework + all gates**

Run: `./gradlew :shared:assembleSharedReleaseXCFramework :shared:iosSimulatorArm64Test :shared:macosArm64Test --console=plain 2>&1 | tail -5`, then the Android gate.
Expected: green; framework size will grow (SKIE runtime). Note build-time delta in the report.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "build: SKIE <version> for Swift-friendly bindings (async, AsyncSequence, sealed enums)"
```

---

### Task 3: Xcode wiring via SPM binaryTarget + build orchestration

**Files:**
- Create: `Tools/build-shared-xcframework.sh`
- Modify: `Packages/WhoopProtocol/Package.swift` (binaryTarget + dependency)
- Modify: `.github/workflows/app-build.yml` (build the xcframework before xcodegen/xcodebuild)
- Modify: `docs/BUILD.md` if it exists, else README build section (one paragraph: the xcframework prerequisite)

**Interfaces:**
- Consumes: Task 1/2 framework.
- Produces: `import Shared` available INSIDE the WhoopProtocol package target (and transitively linkable into every app target that depends on the package). App project.yml unchanged. The canonical local build command: `Tools/build-shared-xcframework.sh` (wraps the gradle call with JAVA_HOME handling).

- [ ] **Step 1: The build script**

`Tools/build-shared-xcframework.sh`:

```bash
#!/usr/bin/env bash
# Builds Shared.xcframework from the Kotlin shared module.
# Xcode builds need this to exist before xcodegen/xcodebuild; CI runs it in app-build.yml.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "(17|21)'; then
  for CAND in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
              "$(/usr/libexec/java_home -v 21 2>/dev/null || true)" \
              "$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; do
    if [ -n "$CAND" ] && [ -x "$CAND/bin/java" ]; then export JAVA_HOME="$CAND"; break; fi
  done
fi
echo "JAVA_HOME=$JAVA_HOME"
CONFIG="${1:-Release}"
cd "$ROOT/android"
./gradlew ":shared:assembleShared${CONFIG}XCFramework" --console=plain
echo "Built: $ROOT/shared/build/XCFrameworks/$(echo "$CONFIG" | tr '[:upper:]' '[:lower:]')/Shared.xcframework"
```

`chmod +x Tools/build-shared-xcframework.sh`. Run it; expected: prints the built path.

- [ ] **Step 2: binaryTarget in WhoopProtocol**

`Packages/WhoopProtocol/Package.swift`: add to `targets:`

```swift
        .binaryTarget(
            name: "Shared",
            path: "../../shared/build/XCFrameworks/release/Shared.xcframework"
        ),
```

and add `"Shared"` to the `WhoopProtocol` library target's `dependencies:` array. Relative path is from the package dir; verify with `swift build --package-path Packages/WhoopProtocol` (expected: builds; if SPM rejects the out-of-package relative path, the sanctioned fallback is a symlink `Packages/WhoopProtocol/Shared.xcframework -> ../../shared/build/XCFrameworks/release/Shared.xcframework` committed as a symlink with the real path git-ignored; report which form landed).

- [ ] **Step 3: Prove import inside the package**

Add a minimal smoke usage in a NEW file `Packages/WhoopProtocol/Sources/WhoopProtocol/SharedBridge.swift`:

```swift
import Shared

/// Bridge marker proving the Kotlin shared framework links into this package.
/// Real delegation lands file by file; see the unification design doc.
enum SharedBridge {
    /// Kotlin: com.noop.update.UpdateCheck-adjacent pure helper is NOT exposed;
    /// use a trivially pure shared symbol to pin linkage.
    static func kotlinLinkProbe() -> String {
        // SharedSmoke lives in commonMain since Phase 1 Task 4.
        SharedSmoke.shared.MODULE
    }
}
```

(If SKIE/Kotlin exposes `SharedSmoke.MODULE` under a different Swift name, adjust: check with `swift build` errors or the generated Shared.h; the point is one real Kotlin symbol referenced from Swift.)

Add a package test `Packages/WhoopProtocolTests/.../SharedBridgeTests.swift` (place beside existing tests):

```swift
import XCTest
@testable import WhoopProtocol

final class SharedBridgeTests: XCTestCase {
    func testKotlinFrameworkLinks() {
        XCTAssertEqual(SharedBridge.kotlinLinkProbe(), "shared")
    }
}
```

Run: `swift test --package-path Packages/WhoopProtocol 2>&1 | tail -5`
Expected: all existing tests + the new one pass (macOS host slice exercises the framework).

- [ ] **Step 4: Whole-app builds**

Run from repo root: `xcodegen generate` then both xcodebuild commands from Global Constraints (NOOPiOS simulator build, Strand macOS build).
Expected: both succeed with zero project.yml changes (the framework rides in through the package dependency). If Xcode complains about the binaryTarget slice for a destination, capture the exact error and fix per its guidance (static framework linking issues surface here); report what was needed.

- [ ] **Step 5: CI wiring**

In `.github/workflows/app-build.yml`, before the `xcodegen generate` step add a step: setup Java 21 (actions/setup-java, temurin 21), then `./Tools/build-shared-xcframework.sh Release`. Add `shared/**` and `android/**` to the workflow's path filters so shared changes trigger the app build. Keep runner as-is.

- [ ] **Step 6: Full gates (gradle + swift test + both xcodebuilds), commit**

```bash
git add -A
git commit -m "build: WhoopProtocol consumes Shared.xcframework via SPM binaryTarget, CI builds the framework"
```

---

### Task 4: First delegation: VersionCheck

Smallest end-to-end delegation proving the wrapper pattern, Swift API preserved, Kotlin doing the work.

**Files:**
- Modify: `Packages/WhoopProtocol/Sources/WhoopProtocol/VersionCheck.swift` (body delegates to Kotlin)
- Kotlin side: `shared/src/commonMain/kotlin/com/noop/update/UpdateCheck.kt` has `isNewer(latest, current)`: it currently lives in ANDROIDMAIN (update package was never hoisted). First move JUST the pure comparator: create `shared/src/commonMain/kotlin/com/noop/update/VersionCompare.kt` with the `isNewer` + version-segment parsing extracted VERBATIM from UpdateCheck.kt, and have UpdateCheck delegate to it (UpdateCheck stays androidMain).
- Test: existing Swift `VersionCheckTests` stays as the parity gate; add mirrored cases to a new `shared/src/commonTest/kotlin/com/noop/update/VersionCompareTest.kt`.

**Interfaces:**
- Consumes: Task 3 linkage.
- Produces: the delegation pattern all later tasks copy: Swift public API unchanged, body calls `Shared` symbol, Swift tests green on both platforms, Kotlin twin test added in commonTest.

- [ ] **Step 1: Extract VersionCompare in Kotlin (with test), Android gate green**

`VersionCompareTest.kt` mirrors the Swift test cases exactly (read `Packages/WhoopProtocol/Tests/.../VersionCheckTests.swift` and port each case's inputs/expectations verbatim).

- [ ] **Step 2: Rebuild framework, delegate the Swift body**

`VersionCheck.swift` keeps its exact public declarations; each function body becomes a call into the Kotlin symbol (e.g. `VersionCompare.shared.isNewer(latest: latest, current: current)` per SKIE naming; verify actual generated name). Delete the Swift implementation lines it replaces. If the Swift file has private helpers now unused, delete them.

- [ ] **Step 3: Swift tests green** (`swift test --package-path Packages/WhoopProtocol`), both xcodebuilds green, gradle gate green.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: VersionCheck delegates to shared Kotlin VersionCompare, first cross-language delegation"
```

---

### Task 5: Delegate frame parsing: Framing.swift

The core protocol seam. Swift `Framing` API stays; internals call Kotlin `com.noop.protocol` Framing. The Swift framing/CRC tests (largest chunk of the 37 test files) become the parity net.

**Files:**
- Modify: `Packages/WhoopProtocol/Sources/WhoopProtocol/Framing.swift`
- Read first: the Kotlin twin `shared/src/commonMain/kotlin/com/noop/protocol/Framing.kt` and both test suites, mapping Swift API to Kotlin API function by function BEFORE writing code; put the mapping table in your report.

**Interfaces:**
- Consumes: Task 4 pattern.
- Produces: Swift `Framing` type surface unchanged; byte outputs identical (tests prove).

- [ ] **Step 1: Write the mapping table** (Swift symbol -> Kotlin symbol -> SKIE-generated Swift name -> conversion notes: Data vs KotlinByteArray bridging). SKIE/Kotlin interop note: `ByteArray` bridges as `KotlinByteArray`, not `Data`: wrappers convert with explicit helpers; write ONE pair of internal conversion helpers in `SharedBridge.swift` (`Data.toKotlin()`, `KotlinByteArray.toData()`) and reuse everywhere:

```swift
import Shared
import Foundation

extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let arr = KotlinByteArray(size: Int32(count))
        withUnsafeBytes { raw in
            for i in 0..<count { arr.set(index: Int32(i), value: Int8(bitPattern: raw[i])) }
        }
        return arr
    }
}
extension KotlinByteArray {
    func toData() -> Data {
        var data = Data(capacity: Int(size))
        for i in 0..<size { data.append(UInt8(bitPattern: get(index: i))) }
        return data
    }
}
```

(Element-loop bridging is O(n) but frames are tens-to-hundreds of bytes; if profiling in tests shows regression on large backfill buffers, switch to `withUnsafeBytes` + `memcpy` via `arr.toNSData()` alternatives and note it.)

- [ ] **Step 2: Delegate function by function, running the Swift framing tests after each** (`swift test --package-path Packages/WhoopProtocol --filter Framing 2>&1 | tail -5`). Any parity failure is a real finding: STOP on the failing case, diagnose whether Swift or Kotlin is wrong (upstream golden hex fixtures decide), and report it; do not adjust expectations.

- [ ] **Step 3: Full gates, commit**

```bash
git add -A
git commit -m "feat: Framing delegates to shared Kotlin frame parser, Swift tests as parity net"
```

---

### Task 6: Delegate the remaining Kotlin-twinned protocol files

Same pattern as Task 5 for the files that have Kotlin twins: `Whoop5Config.swift`, `HapticClock.swift`, `LiveSessionHaptics.swift`, `HistoricalStreams.swift` + `HistoricalMeta.swift` (Kotlin HistoricalStreams stayed commonMain post-2a), `PpgHr.swift`, `Streams.swift` (row shapes: judge whether delegation or keeping Swift value types with conversion at the boundary is cleaner; report the decision per file), `DeviceFamily.swift`.

NOT delegated (no Kotlin twin, stay pure Swift, document in report): `FTMSDecode.swift`, `FitnessSensorDecode.swift`, `Schema.swift`/`Interpreter.swift`/`Values.swift`/`PostHooks.swift` (the schema-driven interpreter has no Kotlin equivalent; Kotlin parses via generated/handwritten decoders), `PuffinCapture.swift`, `whoop-decode` CLI.

**Files:** the listed Swift files; mapping-table-first per file, Swift tests after each file, full gates at the end.

- [ ] **Step 1: Per-file mapping + delegation loop (commit per file or per coherent pair)**
- [ ] **Step 2: Sweep for dead private Swift helpers left behind; delete them**
- [ ] **Step 3: Full gates, commit**

```bash
git add -A
git commit -m "feat: protocol package delegates Kotlin-twinned decoders to shared"
```

---

### Task 7: Phase close-out

**Files:**
- Modify: `docs/superpowers/plans/phase1-baseline.md` (Phase 2b appendix: what delegates, what stays Swift and why, framework/tooling versions, build-time notes)
- Modify: `README.md` (build prerequisite: Tools/build-shared-xcframework.sh before opening Xcode; module map row update)
- Modify: `docs/UPSTREAM.md` (protocol fixes now land in Kotlin only for delegated files; Swift wrappers rarely change)

- [ ] **Step 1: Docs**
- [ ] **Step 2: Clean full verification: gradle clean gate + xcodegen + both xcodebuilds + swift test**
- [ ] **Step 3: Commit (controller pushes after final review)**

```bash
git add -A
git commit -m "docs: phase 2b close-out, protocol layer delegated to shared"
```

---

## Deliberately Out of Scope (Phase 2c+)

- WhoopStore/GRDB replacement by shared Room (the big storage cutover; needs its own plan with data-migration design: GRDB whoop.sqlite to Room noop_whoop.db on Apple platforms).
- BLEManager.swift thinning + Kable.
- StrandAnalytics/StrandImport package delegation.
- oura crypto expect/actual; ingest/update hoists.
- Removing WhoopProtocol's Swift implementations entirely (wrappers stay until 2c proves nothing regresses in the field).

## Self-Review Notes

- Spec coverage: spec Phase 2 step 1 ("protocol parsing first, delete the WhoopModel.swift cluster") is served via package-internal delegation rather than deletion; deletion deferred until wrappers prove out: documented deviation, justified by the 74-shared-files recon finding, and the macOS pull-forward serves the spec's Phase 4 ambition early.
- Placeholder scan: Task 5/6 direct mapping-table-first work against real signatures (implementer reads both sides); conversion helpers given in full; SKIE naming uncertainties explicitly flagged with verify-then-adjust instructions rather than invented API names.
- Type consistency: SharedBridge helpers named identically across Tasks 3/5; framework baseName Shared everywhere; script path consistent.
