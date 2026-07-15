// Root Gradle entry point for the KMP :shared module (macOS harness + XCFramework builds).
// The Android app has its own Gradle root under android/, which also mounts :shared via
// project(":shared").projectDir — keep pluginManagement/repositories aligned with
// android/settings.gradle.kts.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "noop-rework"
include(":shared")
