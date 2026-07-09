plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room") version "2.7.1"
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

            // Room 2.7 entity/DAO annotations are commonMain-safe; the entities/DAOs hoisted from
            // androidMain need this to resolve. The database builder itself stays androidMain (Task 8).
            implementation("androidx.room:room-runtime:2.7.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
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

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.7.1")
}
