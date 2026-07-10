package com.noop.data

import kotlin.random.Random
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun fixturesDir(): String =
    getenv("NOOP_FIXTURES")?.toKString()
        ?: error("NOOP_FIXTURES not set; shared/build.gradle.kts exports SIMCTL_CHILD_NOOP_FIXTURES")

actual fun tempWorkDir(): String {
    val dir = NSTemporaryDirectory() + "noop-restore-${Random.nextLong().toULong()}"
    FileSystem.SYSTEM.createDirectories(dir.toPath())
    return dir
}

actual fun platformFileSystem(): FileSystem = FileSystem.SYSTEM

// The simulator opens real Room databases with the bundled SQLite driver (Task 5), so it carries
// the end-to-end restore proof.
actual val canRunFullRestore: Boolean = true

actual fun openWhoopDatabaseAt(path: String): WhoopDatabase = whoopDatabase(path)
