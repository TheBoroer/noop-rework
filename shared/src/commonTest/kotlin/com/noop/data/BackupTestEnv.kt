package com.noop.data

import okio.FileSystem

/**
 * Platform plumbing for the backup-restore tests (Task 8). The fixture directory is handed to the
 * test process by `shared/build.gradle.kts` via the NOOP_FIXTURES environment variable (with the
 * SIMCTL_CHILD_ prefix on the iOS simulator, which strips it before the child sees it).
 */

/** Absolute path of the committed fixtures directory (`shared/src/commonTest/fixtures`). */
expect fun fixturesDir(): String

/** A fresh, unique, existing temp directory for one test's scratch files. */
expect fun tempWorkDir(): String

/** The real file system of the host platform (JVM or iOS simulator). */
expect fun platformFileSystem(): FileSystem

/**
 * Whether this platform can drive the FULL restore path (PRAGMA quick_check + Room open) in unit
 * tests. False on the plain-JVM androidUnitTest target: `sqliteQuickCheck`'s Android actual uses
 * android.database.sqlite (a throwing stub off-device, no Robolectric in this module) and Room's
 * Android builder needs a Context. True on the iOS simulator, which carries the end-to-end proof,
 * exactly like the Task 5 WhoopDatabaseSmokeTest.
 */
expect val canRunFullRestore: Boolean

/** Open the shared Room database at [path] (iOS: bundled-driver builder; Android JVM: unsupported). */
expect fun openWhoopDatabaseAt(path: String): WhoopDatabase
