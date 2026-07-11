# Upstream cherry-pick protocol

Upstream: https://github.com/ryanbr/noop (remote `upstream`). This fork diverged at v8.5.2.
We do not merge. We hand-port Kotlin diffs for protocol/BLE/analytics fixes.

1. `git fetch upstream`
2. `git log --oneline <last-reviewed>..upstream/main -- android/app/src/main/java/com/noop/protocol android/app/src/main/java/com/noop/ble android/app/src/main/java/com/noop/analytics android/app/src/main/java/com/noop/data`
3. For each relevant commit: `git show <sha>` and hand-apply the Kotlin-side diff.
   Our `protocol` and `analytics` copies live under `shared/src/commonMain/kotlin/com/noop/` and
   `shared/src/androidMain/kotlin/com/noop/` with unchanged package names, so those diffs apply
   with a path prefix swap. `com.noop.ble` (24 files) is the exception: it still lives at
   `android/app/src/main/java/com/noop/ble/`, the same path as upstream, so its diffs apply with
   no path change at all. Ignore all Swift-side changes.
   As of Phase 2a, most `data`/`analytics` files (Room, repository, sleep staging, and the
   bulk of the analytics engines) live in `commonMain`; a shrinking `androidMain` remainder
   still carries the `PHASE2: hoist` tag (see `docs/superpowers/plans/phase1-baseline.md`'s
   Phase 2a close-out for the current list). Check both source sets for the file being patched.
   Caveat since Phase 2a: an upstream diff touching `org.json` or `java.time` code in a hoisted
   `commonMain` file (`WhoopRepository`, `StreamPersistence`, `SleepStager`, `AnalyticsEngine`,
   and others under `shared/src/commonMain`) needs API translation to this codebase's
   `JsonCompat`/kotlinx-datetime conventions, not just a path swap. See
   `shared/src/commonMain/kotlin/com/noop/util/JsonCompat.kt` and `TimeCompat.kt` for the
   reference conventions to translate against.
4. Run the protocol golden tests and full suite:
   `cd android && ./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:testDemoDebugUnitTest :app:testFullDebugUnitTest`
5. Record the reviewed range in this file.

## Phase 2b: protocol delegation changes where a fix lands

As of Phase 2b, `Packages/WhoopProtocol`'s Swift files are no longer all independent
implementations: several are now thin wrappers over the same Kotlin decoders `shared/` ships to
Android, linked in via `Shared.xcframework` (an SPM binary target, built by
`Tools/build-shared-xcframework.sh`). For these files, an upstream protocol fix should be
hand-ported into the Kotlin twin under `shared/src/commonMain/kotlin/com/noop/`, not the Swift
file; the Swift wrapper only needs a change if the delegated call's shape changes (parameter
types, added cases), which is rare.

| Swift file (wrapper) | Kotlin twin (source of truth for the delegated part) | What is delegated |
|---|---|---|
| `VersionCheck.swift` | `update/VersionCompare.kt` | version-string comparison |
| `Framing.swift` | `protocol/Crc.kt`, `protocol/Framing.kt` | CRC8 / CRC16-Modbus / CRC32, the stateful `Reassembler`, `puffinCommandFrame` |
| `Whoop5Config.swift` | `protocol/Whoop5Config.kt` | config command bytes, R22-sequence flag table, byte builders |
| `HapticClock.swift`, `LiveSessionHaptics.swift` | `protocol/HapticClock.kt`, `protocol/LiveSessionHaptics.kt` | pulse timing tables, both clocks' `pulses(...)` encoders |
| `PpgHr.swift` | `protocol/PpgHr.kt` | the canonical (fs=24, window=8s) HR-from-PPG pipeline only |
| `Streams.swift` | `protocol/Streams.kt` | `skinTempCelsius` + `Whoop4SkinTemp` constants only |
| `HistoricalStreams.swift` | `protocol/HistoricalStreams.kt` | plausibility-window constants and `rejectedHistoricalRecords` only |
| `DeviceFamily.swift` | `protocol/DeviceFamily.kt` (`PuffinPacketType`) | `PuffinPacketType` constants only |

Everything else in `Packages/WhoopProtocol` (`FTMSDecode.swift`, `FitnessSensorDecode.swift`,
`Schema.swift`/`Interpreter.swift`/`Values.swift`/`PostHooks.swift`, `PuffinCapture.swift`, the
`whoop-decode` CLI, the `Streams`/`HistoricalStreams` row-shape and extraction functions, the
`DeviceFamily` enum itself, and `verifyFrame`/`FrameCheck`/`frameFromPayload`) has no Kotlin
twin: hand-port those diffs Swift-side exactly as before. See the Phase 2b appendix in
`docs/superpowers/plans/phase1-baseline.md` for the full delegated / stays-Swift table and the
reasoning behind each stays-Swift decision.

After hand-porting a fix into a delegated Kotlin file, re-run both the Kotlin and Swift suites
(step 4 above, plus `swift test --package-path Packages/WhoopProtocol`) since the Swift tests are
the parity net for the delegation.

Last reviewed upstream commit: 7df521c8aace491970df59fbe9c1225837d3e93f (`Release 8.5.2: add to AltStore source`, the last commit on `main` before this fork's Phase 1 KMP migration work began)
