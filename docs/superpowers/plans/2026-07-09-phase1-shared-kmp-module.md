# Phase 1: Shared KMP Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract noop's Android logic packages (protocol, analytics, data, ingest, oura, update) into a `shared` Kotlin Multiplatform module with iOS targets compiling, while the Android app behaves identically and ships normally.

**Architecture:** Lift-and-shift all six packages into `shared/src/androidMain` first (compiles immediately, one commit), then hoist package-by-package into `commonMain` (each hoist is independently green and committable). Files importing app-side packages (`com.noop.ui`, `com.noop.ble`) stay in the app. Files needing Android or JVM-only APIs (`java.time`, `Context`, `SupportSQLite`, `HttpURLConnection`) stay in `androidMain` this phase. iOS targets compile from Task 4 onward so every hoist is verified genuinely multiplatform, but no iOS app consumes the framework yet (that is Phase 2).

**Tech Stack:** Kotlin 2.1.21, KSP 2.1.21-2.0.1, AGP 8.5.2, Gradle 8.7, Room 2.7.1 (KMP), Compose Compiler Gradle plugin, kotlinx-coroutines.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-09-kmp-cmp-unification-design.md`. Read it before starting.
- Package names stay `com.noop.*` verbatim (upstream cherry-pick diffability, spec requirement).
- Backup format must not change: `DataBackup.kt` and everything it serializes stays byte-compatible with upstream noop v8.5.2 in both directions.
- Android app behavior identical after every task. `applicationId com.noop.whoop`, `minSdk 26`, `compileSdk 34`, `versionName 8.5.2` unchanged.
- The Gradle root stays at `android/`. The `shared` module lives at repo root and is wired in via `projectDir` (spec end-state layout, no Xcode churn this phase).
- No em-dashes in any code, comments, commit messages, or docs.
- Every task ends green: `./gradlew :app:assembleDebug :app:testDebugUnitTest :shared:compileKotlinIosSimulatorArm64` (shared target applies from Task 4 on).
- Newer PATCH versions of pinned dependencies are acceptable if the pinned one is unavailable; never change major/minor without stopping.
- All commands run from `/Users/boro/Projects/opensource/noop-rework/android` unless stated otherwise.

---

### Task 1: Baseline verification

Record what green looks like before touching anything. Some upstream tests may already fail; that list is the baseline, not your problem to fix.

**Files:**
- Create: `docs/superpowers/plans/phase1-baseline.md`

**Interfaces:**
- Produces: a committed baseline document every later task compares against.

- [ ] **Step 1: Run the full Android build and unit tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain 2>&1 | tail -40`
Expected: `BUILD SUCCESSFUL`. If any tests fail, capture their exact names.

- [ ] **Step 2: Record the baseline**

Write `docs/superpowers/plans/phase1-baseline.md` (repo root relative):

```markdown
# Phase 1 Baseline (pre-migration)

Date: <fill with today>
Commit: <output of git rev-parse --short HEAD>

- :app:assembleDebug: SUCCESS
- :app:testDebugUnitTest: <SUCCESS | list of failing test FQNs, verbatim>
- Toolchain: Kotlin 1.9.24, AGP 8.5.2, Gradle 8.7, Room 2.6.1, KSP 1.9.24-1.0.20
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/phase1-baseline.md
git commit -m "docs: record pre-migration build and test baseline"
```

---

### Task 2: Kotlin 2.1 toolchain upgrade

Kotlin 1.9 cannot build KMP with Room 2.7 or (later) Compose Multiplatform. This task upgrades Kotlin and switches to the Compose Compiler Gradle plugin that Kotlin 2.x requires. No production logic changes.

**Files:**
- Modify: `android/build.gradle.kts` (root plugins block)
- Modify: `android/app/build.gradle.kts` (plugins block, composeOptions removal)

**Interfaces:**
- Produces: a Kotlin 2.1.21 build every later task compiles under.

- [ ] **Step 1: Bump plugin versions in the root build file**

In `android/build.gradle.kts` replace the plugins block with:

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    // KSP version is <kotlinVersion>-<kspVersion>; must track the Kotlin version exactly.
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
    // Kotlin 2.x moves the Compose compiler into this Gradle plugin (was composeOptions).
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
}
```

Also update the version comment above the block to say `Kotlin 2.1.x`.

- [ ] **Step 2: Apply the Compose plugin in the app module and drop composeOptions**

In `android/app/build.gradle.kts`:

Add to the plugins block:

```kotlin
    id("org.jetbrains.kotlin.plugin.compose")
```

Delete the entire `composeOptions { ... }` block (the one setting `kotlinCompilerExtensionVersion`). The `buildFeatures { compose = true }` line stays.

- [ ] **Step 3: Build and test**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, test results match the Task 1 baseline. Kotlin 2.1 is stricter in places; if compilation errors surface, fix them mechanically (they are usually nullability or overload-resolution) without changing behavior, and note each fix in the commit body.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "build: upgrade Kotlin 1.9.24 to 2.1.21, adopt Compose Compiler Gradle plugin"
```

---

### Task 3: Room 2.6.1 to 2.7.1

Room 2.7 is the first KMP-capable Room. This task upgrades it while everything still lives in the app module, so Room problems surface before the code moves.

**Files:**
- Modify: `android/app/build.gradle.kts:168-171` (room dependency versions)

**Interfaces:**
- Produces: Room 2.7.1 across the project; Task 8 relies on 2.7 KMP artifacts existing.

- [ ] **Step 1: Bump Room**

In `android/app/build.gradle.kts` change:

```kotlin
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
```

- [ ] **Step 2: Build and test**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, baseline-equal tests. Room 2.7 deprecates some 2.6 APIs but compiles them; deprecation warnings are fine this phase.

- [ ] **Step 3: Install and smoke-test on device or emulator**

Run: `./gradlew :app:installDebug` then launch the app manually.
Expected: app opens, existing data still present (Room migration identity check), Devices and Sleep screens render. This verifies the schema was not invalidated by the upgrade.

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts
git commit -m "build: Room 2.6.1 to 2.7.1 ahead of KMP extraction"
```

---

### Task 4: `shared` KMP module skeleton

Create the module with Android + iOS targets and prove the wiring with one object and one test before any real code moves.

**Files:**
- Create: `shared/build.gradle.kts` (repo root)
- Create: `shared/src/commonMain/kotlin/com/noop/SharedSmoke.kt`
- Create: `shared/src/commonTest/kotlin/com/noop/SharedSmokeTest.kt`
- Modify: `android/settings.gradle.kts`
- Modify: `android/app/build.gradle.kts` (add shared dependency)

**Interfaces:**
- Produces: Gradle project `:shared` with source sets `commonMain`, `androidMain`, `iosMain`, `commonTest`, `androidUnitTest`; the app depends on it. Every later task moves code into these source sets.

- [ ] **Step 1: Create `shared/build.gradle.kts`** (at repo root, sibling of `android/`)

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.noop.shared"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
}
```

- [ ] **Step 2: Register the module in `android/settings.gradle.kts`**

Append at the bottom:

```kotlin
include(":shared")
project(":shared").projectDir = file("../shared")
```

Add to the root `android/build.gradle.kts` plugins block:

```kotlin
    id("org.jetbrains.kotlin.multiplatform") version "2.1.21" apply false
    id("com.android.library") version "8.5.2" apply false
```

- [ ] **Step 3: Write the smoke object and its failing-then-passing test**

`shared/src/commonMain/kotlin/com/noop/SharedSmoke.kt`:

```kotlin
package com.noop

object SharedSmoke {
    const val MODULE = "shared"
}
```

`shared/src/commonTest/kotlin/com/noop/SharedSmokeTest.kt`:

```kotlin
package com.noop

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedSmokeTest {
    @Test
    fun moduleWired() {
        assertEquals("shared", SharedSmoke.MODULE)
    }
}
```

- [ ] **Step 4: Wire the app to shared**

In `android/app/build.gradle.kts` dependencies block add:

```kotlin
    implementation(project(":shared"))
```

- [ ] **Step 5: Verify all targets**

Run: `./gradlew :shared:testDebugUnitTest :shared:compileKotlinIosSimulatorArm64 :app:assembleDebug --console=plain 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`. The iOS compile proves Kotlin/Native toolchain works on this machine (first run downloads it, slow once).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "build: add shared KMP module (android + ios targets), wire app dependency"
```

---

### Task 5: Lift-and-shift all six packages into `shared/src/androidMain`

Everything moves at once because the packages import each other cyclically (protocol <-> data <-> analytics). `androidMain` is a full Android/JVM source set, so the code compiles unchanged. The only files that cannot move are the four that import app-side packages; they stay in the app.

**Files:**
- Move: `android/app/src/main/java/com/noop/{protocol,analytics,data,ingest,oura,update}` to `shared/src/androidMain/kotlin/com/noop/`
- Keep in app (move back after the bulk move): `data/BackupSettings.kt`, `ingest/CaptureImporter.kt`, `ingest/HealthConnectWriter.kt`, `analytics/RegistryDayOwnerSource.kt`
- Move: matching test packages from `android/app/src/test/java/com/noop/` to `shared/src/androidUnitTest/kotlin/com/noop/`
- Modify: `shared/build.gradle.kts` (dependencies the moved code needs)

**Interfaces:**
- Consumes: `:shared` module from Task 4.
- Produces: all six packages compiled inside `:shared`, same FQNs, so app code needs zero import changes. Later tasks hoist from `shared/src/androidMain` to `shared/src/commonMain`.

- [ ] **Step 1: Move the packages with git mv**

From repo root (`/Users/boro/Projects/opensource/noop-rework`):

```bash
mkdir -p shared/src/androidMain/kotlin/com/noop
for p in protocol analytics data ingest oura update; do
  git mv android/app/src/main/java/com/noop/$p shared/src/androidMain/kotlin/com/noop/$p
done
```

- [ ] **Step 2: Return the four entangled files to the app**

These import `com.noop.ui` or `com.noop.ble`, which live in the app; shared code cannot see the app.

```bash
mkdir -p android/app/src/main/java/com/noop/data android/app/src/main/java/com/noop/ingest android/app/src/main/java/com/noop/analytics
git mv shared/src/androidMain/kotlin/com/noop/data/BackupSettings.kt        android/app/src/main/java/com/noop/data/
git mv shared/src/androidMain/kotlin/com/noop/ingest/CaptureImporter.kt     android/app/src/main/java/com/noop/ingest/
git mv shared/src/androidMain/kotlin/com/noop/ingest/HealthConnectWriter.kt android/app/src/main/java/com/noop/ingest/
git mv shared/src/androidMain/kotlin/com/noop/analytics/RegistryDayOwnerSource.kt android/app/src/main/java/com/noop/analytics/
```

- [ ] **Step 3: Move the matching unit tests**

```bash
mkdir -p shared/src/androidUnitTest/kotlin/com/noop
for p in protocol analytics data ingest oura update; do
  [ -d android/app/src/test/java/com/noop/$p ] && git mv android/app/src/test/java/com/noop/$p shared/src/androidUnitTest/kotlin/com/noop/$p
done
```

If a moved test imports something that stayed in the app (check with `grep -rl "com.noop.ui\.\|com.noop.ble\." shared/src/androidUnitTest`), move that test file back to `android/app/src/test/java/com/noop/<pkg>/` the same way.

- [ ] **Step 4: Give shared the dependencies the moved code needs**

Attempt compile: `cd android && ./gradlew :shared:compileDebugKotlinAndroid --console=plain 2>&1 | grep -E "^e:" | head -30`

Every unresolved import maps to a dependency that must be copied from `android/app/build.gradle.kts` into `shared/build.gradle.kts`. Expected set (add to the `sourceSets` block; adjust to what the compiler actually demands):

```kotlin
        androidMain.dependencies {
            implementation("androidx.room:room-runtime:2.7.1")
            implementation("androidx.room:room-ktx:2.7.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            implementation("androidx.datastore:datastore-preferences:1.1.1")
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation("junit:junit:4.13.2")
        }
```

Room's compiler must run in shared now. Add to `shared/build.gradle.kts` plugins: `id("com.google.devtools.ksp")` and at the bottom:

```kotlin
dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.7.1")
}
```

Match versions exactly to what `android/app/build.gradle.kts` uses today (read it, do not guess; datastore and junit versions above are expected values, verify).

Repeat compile until `:shared:compileDebugKotlinAndroid` succeeds.

- [ ] **Step 5: Full verification**

Run: `./gradlew :shared:testDebugUnitTest :app:assembleDebug :app:testDebugUnitTest --console=plain 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`, combined test results match the Task 1 baseline (same tests, some now run under `:shared`).

- [ ] **Step 6: Install and smoke-test**

Run: `./gradlew :app:installDebug`, open app.
Expected: identical behavior; data intact; Sleep, Live, Devices screens work. This is the identical-behavior gate from the spec.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move protocol, analytics, data, ingest, oura, update into shared androidMain

Four files entangled with app-side ui/ble packages stay in the app:
BackupSettings, CaptureImporter, HealthConnectWriter, RegistryDayOwnerSource."
```

---

### Task 6: Hoist `protocol` to commonMain

First real multiplatform hoist. Protocol is byte-parsing, almost pure Kotlin: 13 of 14 files should hoist cleanly.

**Files:**
- Move: `shared/src/androidMain/kotlin/com/noop/protocol/*.kt` to `shared/src/commonMain/kotlin/com/noop/protocol/` (except java.time users)
- Move: `shared/src/androidUnitTest/kotlin/com/noop/protocol/` tests to `shared/src/commonTest/kotlin/com/noop/protocol/` where they compile in common

**Interfaces:**
- Consumes: shared source sets from Task 5.
- Produces: `com.noop.protocol` types visible to `commonMain` consumers; Task 7 and 8 depend on that visibility.

- [ ] **Step 1: Identify the impure files**

```bash
grep -rl "^import java\.\|^import android" shared/src/androidMain/kotlin/com/noop/protocol
```

Expected: one file (the java.time user). That file stays in androidMain this phase.

- [ ] **Step 2: Move the pure files**

```bash
mkdir -p shared/src/commonMain/kotlin/com/noop/protocol
for f in shared/src/androidMain/kotlin/com/noop/protocol/*.kt; do
  grep -qE "^import (java|android)" "$f" || git mv "$f" shared/src/commonMain/kotlin/com/noop/protocol/
done
```

- [ ] **Step 3: Compile all targets, demote what breaks**

Run: `cd android && ./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileDebugKotlinAndroid --console=plain 2>&1 | grep -E "^e:" | head -20`

Two failure classes and their fixes:
- A commonMain file references a class still in androidMain (transitive contamination): `git mv` that commonMain file back to androidMain. Common code cannot see androidMain.
- A commonMain file uses a JVM-only stdlib API without a `java.` import (e.g. `String.format`, `synchronized`): replace with the multiplatform equivalent if it is a one-liner (`String.format` becomes string templates or `padStart`; `synchronized` on hot paths means demote instead). If the fix is not a one-liner, demote the file and leave a `// PHASE2: hoist` comment at its top.

Repeat until green.

- [ ] **Step 4: Hoist the protocol tests**

Move test files whose imports are all common (`kotlin.test`, `com.noop.protocol`):

```bash
mkdir -p shared/src/commonTest/kotlin/com/noop/protocol
for f in shared/src/androidUnitTest/kotlin/com/noop/protocol/*.kt; do
  grep -qE "^import (java|android|org\.junit|org\.robolectric)" "$f" || git mv "$f" shared/src/commonTest/kotlin/com/noop/protocol/
done
```

JUnit4-style tests need their imports swapped to `kotlin.test` (`org.junit.Test` becomes `kotlin.test.Test`, `org.junit.Assert.assertEquals` becomes `kotlin.test.assertEquals` with reversed-argument care: kotlin.test uses `assertEquals(expected, actual)` same as JUnit, verify each). If a test uses JUnit rules or Robolectric, leave it in androidUnitTest.

- [ ] **Step 5: Run tests on both platforms**

Run: `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test --console=plain 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`; protocol tests now execute on the iOS simulator too. Any platform-divergent failure (timezone, epoch, byte order) is a real finding: fix the code, not the test.

- [ ] **Step 6: Verify app unchanged and commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, baseline-equal.

```bash
git add -A
git commit -m "refactor: hoist protocol package to commonMain, tests run on ios simulator"
```

---

### Task 7: Hoist `analytics` and `oura` to commonMain

Same mechanics as Task 6, bigger surface: 81 analytics files (expect roughly 70+ to hoist; NapPrefs.kt and 4 java.time users stay) and 9 oura files (expected fully pure).

**Files:**
- Move: pure files from `shared/src/androidMain/kotlin/com/noop/{analytics,oura}/` to `shared/src/commonMain/kotlin/com/noop/{analytics,oura}/`
- Move: their pure tests to `shared/src/commonTest/kotlin/com/noop/{analytics,oura}/`

**Interfaces:**
- Consumes: `com.noop.protocol` in commonMain (Task 6). Analytics imports protocol; the hoist fails if Task 6 did not complete.
- Produces: `com.noop.analytics` and `com.noop.oura` in commonMain. Note: analytics files importing `com.noop.data` classes can only hoist if those data classes are in commonMain, which happens in Task 8. Expect a batch of analytics files to remain in androidMain until Task 8 Step 5 re-hoists them.

- [ ] **Step 1: Move pure files, both packages**

```bash
for p in analytics oura; do
  mkdir -p shared/src/commonMain/kotlin/com/noop/$p
  for f in shared/src/androidMain/kotlin/com/noop/$p/*.kt; do
    grep -qE "^import (java|android)" "$f" || git mv "$f" shared/src/commonMain/kotlin/com/noop/$p/
  done
done
```

- [ ] **Step 2: Compile-driven demotion loop**

Run: `cd android && ./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileDebugKotlinAndroid --console=plain 2>&1 | grep -E "^e:" | head -30`

Apply the same two rules as Task 6 Step 3. The dominant failure here will be analytics files referencing Room entities (`com.noop.data`) still in androidMain: demote those files, tag each with `// TASK8: re-hoist after data hoist` on line 1. Repeat until green.

- [ ] **Step 3: Hoist tests, run both platforms**

Same command pattern as Task 6 Step 4, for `analytics` and `oura` test dirs.

Run: `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test --console=plain 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`. Watch specifically for sleep-staging or timezone-sensitive analytics tests behaving differently on Native; a divergence is a bug to fix in code.

- [ ] **Step 4: Verify app, commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, baseline-equal.

```bash
git add -A
git commit -m "refactor: hoist analytics and oura to commonMain where dependency-clean"
```

---

### Task 8: Room KMP, hoist entities, DAOs, repositories

Room 2.7 lets entities and DAOs live in commonMain. The database builder, Context wiring, and SupportSQLite migrations stay in androidMain this phase (iOS gets a driver in Phase 2).

**Files:**
- Modify: `shared/build.gradle.kts` (Room Gradle plugin + KSP per-target)
- Move: entity/DAO/repository files from `shared/src/androidMain/kotlin/com/noop/data/` to `shared/src/commonMain/kotlin/com/noop/data/`
- Keep in androidMain: `WhoopDatabase.kt` (RoomDatabase subclass and builder), any file importing `androidx.sqlite.db.*` or `android.content.Context`
- Create: `shared/src/commonMain/kotlin/com/noop/data/RoomAnnotations.kt` only if needed (it should not be; Room 2.7 annotations are commonMain-safe)

**Interfaces:**
- Consumes: Room 2.7.1 (Task 3), commonMain analytics/protocol (Tasks 6-7).
- Produces: `com.noop.data` entities, DAOs (as interfaces), and repositories in commonMain. Phase 2 iOS code will construct the database; Android keeps constructing it in androidMain exactly as today.

- [ ] **Step 1: Apply the Room plugin to shared**

In `shared/build.gradle.kts` plugins block add:

```kotlin
    id("androidx.room") version "2.7.1"
```

And below the android block:

```kotlin
room {
    schemaDirectory("$projectDir/schemas")
}
```

Copy the existing schema JSONs so migration verification history survives:

```bash
[ -d android/app/schemas ] && cp -r android/app/schemas shared/schemas
```

(If the app has no `schemas/` directory, skip the copy; the room block still stands.)

Register the plugin version in `android/build.gradle.kts` root plugins block:

```kotlin
    id("androidx.room") version "2.7.1" apply false
```

- [ ] **Step 2: Sort data files by destination**

```bash
grep -rlE "^import (androidx\.sqlite|android\.content)" shared/src/androidMain/kotlin/com/noop/data
```

Those files (expect `WhoopDatabase.kt` and 1-2 others) stay in androidMain. Move the rest (entities in `Entities.kt`, `WhoopDao.kt`, `DeviceRegistryDao.kt`, `WhoopRepository.kt`, `PairedDevice.kt`, stores):

```bash
mkdir -p shared/src/commonMain/kotlin/com/noop/data
for f in shared/src/androidMain/kotlin/com/noop/data/*.kt; do
  grep -qE "^import (androidx\.sqlite|android)" "$f" || git mv "$f" shared/src/commonMain/kotlin/com/noop/data/
done
```

Note: `import androidx.room.*` does NOT disqualify a file; Room annotations and `RoomDatabase` types are multiplatform in 2.7. Only `androidx.sqlite.db.*` (Support* classes) and `android.*` disqualify. The grep above is deliberately broader for `android`; re-check any file it excludes and hoist the ones whose only match is `androidx.room`.

- [ ] **Step 3: Compile-driven demotion loop, then re-hoist tagged analytics files**

Run: `cd android && ./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileDebugKotlinAndroid --console=plain 2>&1 | grep -E "^e:" | head -30`

Demote breakers per Task 6 rules. `WhoopRepository.kt` uses `androidx.room.withTransaction` (Android-only extension in 2.6; in 2.7 `useWriterConnection`/`immediateTransaction` are the common APIs). If the repository fails to hoist for only this reason, keep it in androidMain with a `// PHASE2: hoist` tag rather than rewriting transaction semantics this phase.

Once green, re-hoist every file tagged `// TASK8: re-hoist after data hoist` in Task 7:

```bash
grep -rl "TASK8: re-hoist" shared/src/androidMain/kotlin/com/noop/analytics | while read f; do
  git mv "$f" shared/src/commonMain/kotlin/com/noop/analytics/
done
```

Remove the tag comments, re-run the compile loop until green.

- [ ] **Step 4: Full test pass, both platforms**

Run: `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:testDebugUnitTest --console=plain 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`, baseline-equal.

- [ ] **Step 5: Install and smoke-test with data**

Run: `./gradlew :app:installDebug`, open app.
Expected: existing database opens (schema hash unchanged proves entities moved without semantic change), sleep history renders, a backup export + restore round-trip succeeds (Settings screen). The backup check guards the byte-compatibility constraint.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: Room KMP, entities and DAOs to commonMain, database builder stays android"
```

---

### Task 9: Phase close-out

`ingest` (JVM-heavy: zip, File, java.time) and `update` (HttpURLConnection, org.json) deliberately stay in androidMain; hoisting them is Phase 2 work alongside kotlinx-datetime and Ktor decisions. This task documents state and ships.

**Files:**
- Create: `docs/UPSTREAM.md`
- Modify: `README.md` (module map section)
- Modify: `docs/superpowers/plans/phase1-baseline.md` (append close-out)

**Interfaces:**
- Consumes: everything above.
- Produces: shipped Phase 1; Phase 2 planning starts from the close-out notes.

- [ ] **Step 1: Write `docs/UPSTREAM.md`**

```markdown
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
   `cd android && ./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:testDebugUnitTest`
5. Record the reviewed range in this file.

Last reviewed upstream commit: <sha of v8.5.2 tag>
```

- [ ] **Step 2: Add the module map to `README.md`**

Insert after the project intro:

```markdown
## Repo layout (fork)

| Path | What it is |
|------|-----------|
| `shared/` | Kotlin Multiplatform logic: protocol, analytics, data (Room), oura, ingest, update. `commonMain` is platform-free; `androidMain` holds the not-yet-hoisted remainder. |
| `android/` | Android app (Jetpack Compose UI, BLE service, alarms, widgets) depending on `shared`. |
| `Strand*`, `NOOPWatch*`, `Packages/` | Original Swift apps, untouched until Phase 2/3 of the migration. |
| `docs/superpowers/specs/` | Design specs. Start with the 2026-07-09 unification design. |

Rule of thumb: OS API code lives in the platform app or `androidMain`/`iosMain`; decisions, calculations, and byte parsing live in `commonMain`.
```

- [ ] **Step 3: Append close-out to the baseline doc**

Append to `docs/superpowers/plans/phase1-baseline.md`: date, final commit, file counts per source set (`find shared/src/commonMain -name "*.kt" | wc -l` and same for androidMain), list of files tagged `PHASE2: hoist`, and test totals per target.

- [ ] **Step 4: Final full verification**

Run: `./gradlew clean :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:assembleDebug :app:testDebugUnitTest --console=plain 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL` from clean, baseline-equal tests.

- [ ] **Step 5: Commit and push**

```bash
git add -A
git commit -m "docs: upstream cherry-pick protocol, module map, phase 1 close-out"
git push origin main
```

---

## Self-Review Notes

- Spec coverage: Phase 1 spec items all mapped (shared module, package moves in dependency order via lift-then-hoist, Room KMP, identical Android behavior, upstream protocol doc). Backup byte-compatibility guarded by Task 8 Step 5 round-trip. iOS consumption, kotlinx-datetime, Ktor, CMP are Phase 2+ by design.
- Known judgment points made explicit: compile-driven demotion loops (Tasks 6-8) are bounded by two written rules; entangled-file list is exact (verified by grep against v8.5.2).
- Version pins verified against the repo where possible (AGP 8.5.2, Gradle 8.7, Room 2.6.1 current). Kotlin 2.1.21 / KSP 2.1.21-2.0.1 / Room 2.7.1 are the executor's to confirm as still-published; patch bumps allowed per Global Constraints.
