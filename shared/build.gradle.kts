import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room") version "2.7.1"
    kotlin("plugin.serialization")
    // Phase 2b Task 2: SKIE rewrites the Apple framework API surface at link time: suspend
    // functions become Swift async, Flow becomes AsyncSequence, sealed classes become Swift
    // enums with associated values. 0.10.13 is the newest stable; it supports Kotlin 2.0.0
    // through 2.4.0, covering the pinned 2.1.21 (2.1.21 support landed in 0.10.2). SKIE with
    // Kotlin 2.1.20+ requires Gradle 8.8+, hence the wrapper bump from 8.7 in this commit.
    id("co.touchlab.skie") version "0.10.13"
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Phase 2b Task 1: ship as Shared.xcframework across all four Apple slices. iosArm64/
    // iosSimulatorArm64 already existed (Phase 2a); this list form adds macosArm64/macosX64
    // without renaming their source sets. Each target's release framework feeds the same
    // XCFramework bundle so Xcode links one artifact regardless of which Apple slice it runs on.
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

    // Phase 2c-2 Task 9 harness: a bare macOS executable that runs a live Kable scan from the
    // terminal, so the WHOOP advertisement filter can be verified against real hardware without
    // going through Xcode/iPhone. Manual-gate tooling only; not shipped in the XCFramework.
    macosArm64 {
        binaries.executable {
            entryPoint = "com.noop.ble.harness.main"
            baseName = "scan-harness"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

            // Room 2.7 entity/DAO annotations are commonMain-safe; the entities/DAOs hoisted from
            // androidMain need this to resolve. Phase 2a Task 5: the @Database class + KMP-form
            // migrations now live in commonMain too.
            implementation("androidx.room:room-runtime:2.7.1")

            // Bundled SQLite driver, used by the iOS builder (and the JVM/iOS smoke tests). Version
            // pinned to what androidx.room 2.7.1 resolves for androidx.sqlite:sqlite (2.5.0), verified
            // via `./gradlew :shared:dependencies --configuration debugRuntimeClasspath | grep sqlite`.
            implementation("androidx.sqlite:sqlite-bundled:2.5.0")

            // Phase 2a: KMP multiplatform deps.
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:atomicfu:0.28.0")
            implementation("com.squareup.okio:okio:3.15.0")

            // Phase 2c-2 Task 9: Kable — Kotlin multiplatform BLE (CoreBluetooth on Apple,
            // android.bluetooth.le on Android). 0.37.1 is the newest stable on Maven Central.
            implementation("com.juul.kable:kable-core:0.37.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Smoke test drives suspend DAO calls (insert + count) inside runTest on JVM and iOS.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        }
        // Versions below are matched exactly to what android/app/build.gradle.kts uses today, so the
        // lifted-and-shifted protocol/analytics/data/ingest/oura/update packages compile unchanged.
        androidMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

            // Room (WhoopDatabase + DAOs/entities moved in from the app).
            implementation("androidx.room:room-runtime:2.7.1")
            implementation("androidx.room:room-ktx:2.7.1")

            // Health Connect import (HealthConnectImporter.kt).
            implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation("junit:junit:4.13.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            implementation("org.json:json:20240303") // real org.json for JVM unit tests (android.jar ships throwing stubs)
            implementation("net.sf.kxml:kxml2:2.3.0") // real XmlPullParser for JVM tests (android.util.Xml is a throwing stub)
        }
    }
}

android {
    namespace = "com.noop.shared"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

skie {
    analytics {
        enabled.set(false)
    }
}

// Task 8: the backup-restore tests read the committed .noopbak fixture from disk. All test targets
// get the fixtures directory via NOOP_FIXTURES; the iOS simulator child process only inherits
// variables carrying the SIMCTL_CHILD_ prefix, so the same value is exported twice for it.
tasks.withType<Test>().configureEach {
    environment("NOOP_FIXTURES", "$projectDir/src/commonTest/fixtures")
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_NOOP_FIXTURES", "$projectDir/src/commonTest/fixtures")
}
// Phase 2b Task 1: macosArm64Test/macosX64Test run their test binary directly on the build host
// (KotlinNativeHostTest, not the simulator subclass above), so they inherit the process
// environment unprefixed, same as the plain-JVM Test tasks.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest>().configureEach {
    environment("NOOP_FIXTURES", "$projectDir/src/commonTest/fixtures")
}

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.7.1")
    // Phase 2a Task 5: the @Database class moved to commonMain, so Room's KSP must also run for the
    // iOS targets to generate WhoopDatabase_Impl + the actual WhoopDatabaseConstructor there.
    add("kspIosArm64", "androidx.room:room-compiler:2.7.1")
    add("kspIosSimulatorArm64", "androidx.room:room-compiler:2.7.1")
    // Phase 2b Task 1: same reason, for the new macOS targets.
    add("kspMacosArm64", "androidx.room:room-compiler:2.7.1")
    add("kspMacosX64", "androidx.room:room-compiler:2.7.1")
}

// Room's KSP2 processor resolves the @Database's type graph (RoomDatabase and the room-runtime
// native klib it extends) through the Kotlin/Native platform libraries. The kspKotlin<AppleTarget>
// tasks do NOT declare a dependency on the Kotlin/Native distribution being provisioned, so on a
// clean KONAN_DATA_DIR (every fresh CI runner) KSP runs before the platform klibs exist, can't
// resolve room-runtime's transitive native types, and Room aborts with
//   e: [ksp] [MissingType]: Element 'com.noop.data.WhoopDatabase' references a type that is not present
// A developer's ~/.konan is already warm from earlier native builds, which is why this only ever
// failed in CI. commonizeNativeDistribution provisions (downloads + commonizes) those platform
// libraries and already runs as part of the appleMain build, so this only adds the missing ordering
// edge: KSP waits for the distribution instead of racing ahead of it. Android/JVM KSP tasks are not
// matched (they need no Kotlin/Native distribution).
tasks.matching {
    it.name.startsWith("ksp") && Regex("Ios|Macos").containsMatchIn(it.name)
}.configureEach {
    dependsOn("commonizeNativeDistribution")
}
