// Root build file — declares plugin versions once; applied per-module in app/build.gradle.kts.
// Keep these versions aligned with the shared contract:
//   Android Gradle Plugin 8.x · Kotlin 2.1.x · KSP matched to the Kotlin version · Room 2.6.x.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    // KSP version is <kotlinVersion>-<kspVersion>; must track the Kotlin version exactly.
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
    // Kotlin 2.x moves the Compose compiler into this Gradle plugin (was composeOptions).
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.21" apply false
    id("com.android.library") version "8.5.2" apply false
    id("androidx.room") version "2.7.1" apply false
}
