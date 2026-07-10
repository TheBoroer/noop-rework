package com.noop.data

import java.io.File
import kotlin.random.Random
import okio.FileSystem

actual fun fixturesDir(): String =
    System.getenv("NOOP_FIXTURES")
        ?: error("NOOP_FIXTURES not set; shared/build.gradle.kts exports it to every Test task")

actual fun tempWorkDir(): String {
    val dir = File(System.getProperty("java.io.tmpdir"), "noop-restore-${Random.nextLong().toULong()}")
    check(dir.mkdirs()) { "could not create temp work dir $dir" }
    return dir.absolutePath
}

actual fun platformFileSystem(): FileSystem = FileSystem.SYSTEM

// Plain JVM (no Robolectric): android.database.sqlite is a throwing stub and Room's Android builder
// needs a Context, so the full restore path is proven on the iOS simulator target instead.
actual val canRunFullRestore: Boolean = false

actual fun openWhoopDatabaseAt(path: String): WhoopDatabase =
    error("Room cannot open off-device on the plain-JVM androidUnitTest target; see canRunFullRestore")
