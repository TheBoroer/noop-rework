package com.noop.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.noop.data.DataBackup
import com.noop.data.ImportService
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Synchronous debug-only adb command surface ("app as CLI"). `adb shell content call` is a
 * plain binder call: it blocks until the
 * method returns and prints the result Bundle to stdout, so scripts get completion + result in one
 * step — no logcat polling, no partial-file races.
 *
 * Usage:
 *
 *   DIR=/storage/emulated/0/Android/data/com.noop.whoop.rework.debug/files
 *   adb shell content call --uri content://com.noop.whoop.rework.debug.cli \
 *       --method export --arg $DIR/backup.noopbak
 *   adb shell content call --uri content://com.noop.whoop.rework.debug.cli \
 *       --method import --arg $DIR/backup.noopbak
 *   adb shell content call --uri content://com.noop.whoop.rework.debug.cli --method restage
 *   adb shell content call --uri content://com.noop.whoop.rework.debug.cli --method methods
 *
 * Success prints `Result: Bundle[{...}]`; failure prints a Bundle with an `error` key (the binder
 * call itself still exits 0, so wrappers should grep for `error=`).
 *
 * Extending: add a branch to the `when` in [call] and list it in [METHODS]. Keep branches thin —
 * delegate to real production entry points (as export/import do via [DataBackup]) so the CLI can
 * never drift from what the UI does.
 *
 * Security: lives in the `debug` source set (absent from release builds) and [requireShell]
 * additionally restricts callers to the adb shell/root UIDs, so other apps on the device cannot
 * invoke it even on a debug install.
 */
class DebugCliProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        requireShell()
        val context = requireContext()
        return try {
            when (method) {
                "export" -> {
                    val path = requirePath(arg)
                    Log.i(TAG, "export start: $path")
                    // External-files dir must be created by the platform (installd), not by
                    // File.mkdirs(); force creation, then mkdirs any subdirs under it.
                    context.getExternalFilesDir(null)
                    File(path).parentFile?.mkdirs()
                    val bytes = runCli(context, "CLI export") {
                        DataBackup.exportTo(context, Uri.fromFile(File(path)))
                        File(path).length()
                    }
                    Log.i(TAG, "export done: $bytes bytes -> $path")
                    Bundle().apply { putLong("bytes", bytes) }
                }
                "import" -> {
                    val path = requirePath(arg)
                    Log.i(TAG, "import start: $path")
                    val result = runCli(context, "CLI import") {
                        DataBackup.importFrom(context, Uri.fromFile(File(path)))
                    }
                    Log.i(TAG, "import done: $result")
                    Bundle().apply { putString("result", result.toString()) }
                }
                "restage" -> {
                    // Dev shortcut for staging-algorithm work: clear the persisted stager-version
                    // stamp so the NEXT idle rescore (app launch / 15-min backstop tick) runs the
                    // same full-history force re-stage a real STAGING_VERSION bump would. No engine
                    // wiring duplicated here, so the CLI can never drift from production.
                    com.noop.ui.NoopPrefs.setStagerVersion(context, 0)
                    Log.i(TAG, "restage: stager-version stamp cleared")
                    Bundle().apply {
                        putString("result", "stamp cleared; full re-stage runs on next idle rescore")
                    }
                }
                "methods" -> Bundle().apply { putStringArray("methods", METHODS) }
                else -> error("unknown method '$method'; try: ${METHODS.joinToString()}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "$method FAILED: $arg", t)
            Bundle().apply { putString("error", t.toString()) }
        }
    }

    /**
     * Binder-thread block on an [ImportService] job — callers of `content call` want sync.
     *
     * FGS starts from a provider binder call are only permitted while the process is in a
     * temporarily elevated state (e.g. fresh cold start by the shell), so a warm process throws
     * [android.app.ForegroundServiceStartNotAllowedException]. The keep-alive is redundant here
     * anyway — the blocking binder transaction from adb holds the process — so fall back to
     * running the same block inline.
     */
    private fun <T> runCli(context: android.content.Context, label: String, block: suspend () -> T): T =
        try {
            runBlocking { ImportService.run(context, label) { block() }.await() }
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "$label: FGS start not allowed from provider call; running inline")
            runBlocking { block() }
        }

    private fun requirePath(arg: String?): String {
        require(!arg.isNullOrBlank()) { "missing --arg <absolute path>" }
        return arg
    }

    private fun requireShell() {
        val uid = Binder.getCallingUid()
        require(uid == SHELL_UID || uid == Process.ROOT_UID || uid == Process.myUid()) {
            "rejected caller uid $uid: debug CLI is shell-only"
        }
    }

    // Unused mandatory ContentProvider surface — this provider is call()-only.
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

    private companion object {
        const val TAG = "NoopCli"
        const val SHELL_UID = 2000
        val METHODS = arrayOf("export", "import", "restage", "methods")
    }
}
