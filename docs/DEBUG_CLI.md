# Debug CLI — driving the Android app from adb

Debug builds of the Android app expose a small **command surface over adb** ("app as CLI") so that
backups can be exported/imported — and, later, other in-app functionality triggered — entirely from
the shell: no UI, no device interaction, fully scriptable. Useful for automated testing, backup
round-trip verification, and seeding a device/emulator with real data.

One mechanism: the synchronous `content call` provider,
[`DebugCliProvider`](../android/app/src/debug/java/com/noop/debug/DebugCliProvider.kt) — blocks
until done, returns the result. (A legacy async broadcast receiver existed briefly; deleted —
fire-and-forget plus logcat-watching invited truncated pulls.)

It lives in the **`debug` source set only** — release builds do not contain the class or
manifest entry at all.

## Security model

- **Release builds:** code absent entirely (`app/src/debug` source set).
- **Debug builds:** the provider is exported (so `content call` can reach it) but rejects any
  caller whose UID is not adb shell (2000), root, or the app itself — other apps on the device
  cannot invoke it.
- Commands delegate to the **same production entry points the UI uses** (`DataBackup`,
  `ImportService`) — the CLI adds no parallel data path that could drift.

## Synchronous CLI (`content call`)

`adb shell content call` is a plain binder call: it **blocks until the method returns** and prints
the result `Bundle` to stdout. No logcat polling, no partial-file races.

```bash
URI=content://com.noop.whoop.rework.debug.cli
DIR=/storage/emulated/0/Android/data/com.noop.whoop.rework.debug/files
```

The app's external-files dir (`$DIR`) is the exchange point: the app can write it without
permissions, and adb can `push`/`pull` it.

### Export a backup

```bash
adb shell content call --uri $URI --method export --arg $DIR/backup.noopbak
# Result: Bundle[{bytes=165856313}]
adb pull $DIR/backup.noopbak
```

Blocks for the full export (tens of seconds for a large database). The returned `bytes` matches
the file size — safe to pull immediately after the call returns.

### Import a backup

```bash
adb push backup.noopbak $DIR/backup.noopbak
adb shell content call --uri $URI --method import --arg $DIR/backup.noopbak
# Result: Bundle[{result=NeedsRestart}]
adb shell am force-stop com.noop.whoop.rework.debug   # restart to load the imported DB
```

`NeedsRestart` is the production import result: the database has been swapped on disk and the
process must restart to load it — hence the `force-stop`. Imports of large backups can take a
minute or more; the call blocks throughout.

### Discover methods

```bash
adb shell content call --uri $URI --method methods
# Result: Bundle[{methods=[export, import, methods]}]
```

### Error handling

Failures come back **as data, not crashes**. Two shapes:

- Domain results (e.g. an unreadable backup) use the production result type:
  `Result: Bundle[{result=Failed(message=...)}]`
- Infrastructure/usage errors (bad method, missing arg, rejected caller) use an `error` key:
  `Result: Bundle[{error=java.lang.IllegalStateException: unknown method 'bogus'; ...}]`

Note `content call` itself exits 0 either way — scripts should grep the output for `error=` /
`Failed(` rather than rely on exit status.

### Round-trip wrapper example

```bash
noop-export() {
  local uri=content://com.noop.whoop.rework.debug.cli
  local dir=/storage/emulated/0/Android/data/com.noop.whoop.rework.debug/files
  local out=${1:-backup-$(date +%F).noopbak}
  local res
  res=$(adb shell content call --uri $uri --method export --arg $dir/backup.noopbak) || return 1
  case $res in *error=*) echo "$res" >&2; return 1;; esac
  adb pull $dir/backup.noopbak "$out"
}
```

## Adding new commands

`DebugCliProvider.call()` is a `when` dispatch — extending it is deliberate boilerplate-free:

1. Add a branch to the `when` in `call()` and the name to `METHODS`.
2. Keep the branch **thin**: delegate to a real production entry point (as export/import delegate
   to `DataBackup`), so the CLI can never drift from what the UI does.
3. Return results in the `Bundle`; throw for usage errors (the catch-all converts them to an
   `error` key).
4. For flags/options beyond the single `--arg`, use the `extras` Bundle — `content call` maps
   `--extra key:type:value` onto it.

Candidate future commands: database stats dump, trigger a sync, seed fixtures, toggle feature
flags.

## Implementation notes

- **External-files dir creation:** a fresh install has no
  `Android/data/<pkg>/files` yet, and apps cannot `File.mkdirs()` it themselves — the tree is
  created by the platform (installd). The provider calls `context.getExternalFilesDir(null)`
  first to force creation, then `mkdirs()` any subdirectories.
- **Foreground-service fallback:** work runs under the same `ImportService` foreground keep-alive
  the UI uses, but FGS starts from a provider binder call are only allowed while the process is in
  a temporarily elevated state (e.g. a fresh cold start by the shell). On a warm process Android
  throws `ForegroundServiceStartNotAllowedException`; the provider then runs the same block
  **inline**, which is safe because the blocking binder transaction from adb keeps the process
  alive.
- The binder thread blocks via `runBlocking` — fine off the main thread, and exactly the semantics
  a CLI caller wants.
