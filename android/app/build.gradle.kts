import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional release signing. Credentials live in `keystore.properties` (git-ignored, never
// committed); when it's absent — clones, CI without secrets — release falls back to the debug
// key so `assembleRelease` always produces an installable APK. See docs/BUILD.md.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// #658 (upstream 1928776c): dependency locking — pins every resolvable configuration's
// resolved graph in gradle.lockfile; a substituted/tampered version fails resolution.
// Same block as upstream's android/app module; :shared carries its own twin.
dependencyLocking {
    lockAllConfigurations()
}

android {
    namespace = "com.noop"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.noop.whoop.rework"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        getByName("debug") {
            val forkDebugKeystore = rootProject.file("fork-debug.keystore")
            if (forkDebugKeystore.exists()) {
                storeFile = forkDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Shipped UNMINIFIED for reliability. R8 minification crashes this app at runtime: full-mode
            // over-strips reflective paths, and even with full-mode OFF + broad keeps (com.noop.** +
            // Tink/Worker/ViewModel) a minified build STILL died right after the terms gate on a real
            // device — a library reflective path we couldn't pin without a device to trace. Offline app,
            // a ~18 MB APK is fine. Re-enabling minify needs the exact crash trace + device verification.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real release key when keystore.properties is present; otherwise the debug key,
            // so a fresh clone can still build an installable release APK.
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            // Fork staging release: built with -PstagingRelease (the fork testing-build CI only), the
            // release APK gets its own id/name so it installs BESIDE both the official app and the
            // .debug staging build. A real release (no property) keeps the true com.noop.whoop.rework id.
            if (project.hasProperty("stagingRelease")) {
                applicationIdSuffix = ".staging"
                versionNameSuffix = "-staging"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Hold androidx.core to the last SDK-34-compatible release. AGP 8.5.2 caps compileSdk at 34,
// but transitive graphs (compose 1.6.8 / activity 1.9.0 / health-connect alpha07) drift core up
// to 1.15.0, which demands compileSdk 35 and fails the build. The direct dependency below already
// pins 1.13.1; forcing it holds the whole graph there until AGP + compileSdk move to 35 together.
configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.13.1", "androidx.core:core-ktx:1.13.1")
    }
}

dependencies {
    implementation(project(":shared"))

    // --- Compose (BOM pins all Compose artifact versions in lockstep) ---
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // --- Home-screen widget (1.1.x: last line compatible with compileSdk 34) ---
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // Glance's own POM pins work-runtime 2.7.1 (Oct 2021) — pre-Android-14. Pin a current one
    // explicitly so the widget scheduler runs on a WorkManager that's maintained for targetSdk 34.
    // (2.10+ needs compileSdk 35; 2.9.x is the ceiling for this module.)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // --- Activity / lifecycle / navigation ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2") // collectAsStateWithLifecycle
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- kotlinx-datetime (Task 3, Phase 2a): :shared declares this `implementation`, matching this
    // module's existing pattern of declaring its own copy of shared's transitive deps rather than
    // relying on API leakage (see the Coroutines block above). Needed directly here because
    // MoodStore.todayKey/SleepEditGuard.autoCorrectedBed/AlarmPayload.nextWakeEpochMs now default a
    // parameter to a kotlinx.datetime type (LocalDate/TimeZone), and call sites in this module
    // (MindSection.kt, SleepScreen.kt) omit that argument, so the compiler needs the type resolvable.
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")

    // --- okio (Task 8, Phase 2a): same own-copy pattern as kotlinx-datetime above. DataBackup
    // delegates the .noopbak stage/validate/swap flow to the shared BackupRestore engine, whose
    // API takes okio FileSystem/Path (FileSystem.SYSTEM + File.toOkioPath at the call site).
    implementation("com.squareup.okio:okio:3.15.0")

    // --- Room (local-only persistence; on-device, nothing leaves the phone) ---
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // --- AI Coach (opt-in, bring-your-own-key). HTTP client + Keystore-backed key storage. ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // --- Health Connect (optional native Android import of steps/HR/HRV/sleep/etc.) ---
    // Pinned to alpha07: alpha11+ require compileSdk 35; this module is compileSdk 34.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    // --- Unit / instrumentation tests ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests (android.jar ships throwing stubs)
    testImplementation("net.sf.kxml:kxml2:2.3.0") // real XmlPullParser for JVM tests (android.util.Xml is a throwing stub)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // --- Compose tooling (debug-only) ---
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
