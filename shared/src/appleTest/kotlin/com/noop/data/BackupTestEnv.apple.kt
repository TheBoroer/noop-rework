package com.noop.data

import kotlin.random.Random
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.posix.getenv

// getenv sees the plain (unprefixed) NOOP_FIXTURES value either way: on macOS test tasks
// shared/build.gradle.kts exports it directly (KotlinNativeHostTest, plain process environment);
// on the iOS simulator it's exported as SIMCTL_CHILD_NOOP_FIXTURES, which simctl strips down to
// NOOP_FIXTURES before the child process (the simulator's test runner) sees it.
@OptIn(ExperimentalForeignApi::class)
actual fun fixturesDir(): String =
    getenv("NOOP_FIXTURES")?.toKString()
        ?: error("NOOP_FIXTURES not set; shared/build.gradle.kts should export it for this test task")

actual fun tempWorkDir(): String {
    val dir = NSTemporaryDirectory() + "noop-restore-${Random.nextLong().toULong()}"
    FileSystem.SYSTEM.createDirectories(dir.toPath())
    return dir
}

actual fun platformFileSystem(): FileSystem = FileSystem.SYSTEM

// Every Apple native target (iOS simulator, macOS) opens real Room databases with the bundled
// SQLite driver (Task 5), so each carries the end-to-end restore proof.
actual val canRunFullRestore: Boolean = true

actual fun openWhoopDatabaseAt(path: String): WhoopDatabase = whoopDatabase(path)
