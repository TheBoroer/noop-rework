# Upstream cherry-pick protocol

Upstream: https://github.com/ryanbr/noop (remote `upstream`). This fork diverged at v8.5.2.
We do not merge. We hand-port Kotlin diffs for protocol/BLE/analytics fixes.

1. `git fetch upstream`
2. `git log --oneline <last-reviewed>..upstream/main -- android/app/src/main/java/com/noop/protocol android/app/src/main/java/com/noop/ble android/app/src/main/java/com/noop/analytics`
3. For each relevant commit: `git show <sha>` and hand-apply the Kotlin-side diff.
   Our copies live under `shared/src/commonMain/kotlin/com/noop/` and
   `shared/src/androidMain/kotlin/com/noop/` with unchanged package names,
   so diffs apply with a path prefix swap. Ignore all Swift-side changes.
4. Run the protocol golden tests and full suite:
   `cd android && ./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:testDemoDebugUnitTest :app:testFullDebugUnitTest`
5. Record the reviewed range in this file.

Last reviewed upstream commit: 7df521c8aace491970df59fbe9c1225837d3e93f (`Release 8.5.2: add to AltStore source`, the last commit on `main` before this fork's Phase 1 KMP migration work began)
