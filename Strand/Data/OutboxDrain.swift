import Foundation
import WhoopStore

/// Phase 2c-2 Task 6 (app-side wrapper): the thin production entry that runs the one-shot GRDB→Room
/// outbox drain on launch. The reusable, unit-tested core lives in the WhoopStore package
/// (`OutboxDrain`); this only resolves the legacy path, adds the app-level one-shot guard, and calls
/// it. Named `LegacyOutboxDrain` (not `OutboxDrain`) so it never shadows the package type it delegates
/// to.
///
/// Why an app-level flag on top of the package's file-rename done-marker: while GRDB read code is still
/// in tree (removed in Task 8, along with this drain), `WhoopStore`'s own open (`detectMigrateOpen`)
/// re-creates an empty
/// `whoop.sqlite` on EVERY launch, so the file's mere presence can't gate re-runs — a bare
/// `OutboxDrain.run` would re-archive a freshly-created empty file each launch, piling up
/// `noop-drained-*` files. The persisted flag makes this run exactly once per install; the file rename
/// remains the recoverable, crash-safe marker for the single real pass.
enum LegacyOutboxDrain {
    /// Set only after a full `run` (copy + archive) succeeds, so a crash mid-drain leaves it unset and
    /// the next launch retries the (idempotent) copy. Versioned in case a future migration needs to
    /// force a re-drain.
    private static let doneKey = "legacyOutboxDrainDone.v1"

    /// One-shot on launch. Safe to call every launch and from any device state:
    ///  - `.legacyGrdb` fallback store → returns early: the outbox still lives in `whoop.sqlite` and IS
    ///    the live store, nothing to fold yet (the next launch that reaches `.room` will drain it).
    ///  - already drained (persisted flag set) → returns early.
    ///  - otherwise → fold pending batches + keep-set cursors into the live Room outbox and archive the
    ///    GRDB file, then set the flag. On any failure the flag stays unset so the next launch retries;
    ///    the copy is idempotent and the cursor copies are unset-only, so a retry never duplicates rows
    ///    or clobbers a newer value.
    static func runIfNeeded(room: WhoopStore) async {
        guard room.storageBackend == .room else { return }
        guard !UserDefaults.standard.bool(forKey: doneKey) else { return }

        let legacyPath: String
        do {
            legacyPath = try StorePaths.defaultDatabasePath()
        } catch {
            NSLog("LegacyOutboxDrain: skip — couldn't resolve legacy DB path: \(error)")
            return
        }

        do {
            let result = try await OutboxDrain.run(
                legacyURL: URL(fileURLWithPath: legacyPath),
                room: room,
                openLegacy: { try WhoopStore.openLegacyGrdb(path: $0.path) })
            // Crash window (accepted): a crash between `run`'s rename and this flag-set means the next
            // launch re-drains the freshly-recreated EMPTY `whoop.sqlite`, producing one extra empty
            // `noop-drained-*` archive. Harmless — no rows involved — so not worth engineering around.
            UserDefaults.standard.set(true, forKey: doneKey)
            if result.batchesDrained > 0 || result.cursorsCopied > 0 {
                NSLog("LegacyOutboxDrain: folded \(result.batchesDrained) batch(es) + \(result.cursorsCopied) cursor(s) into Room; archived → \(result.archivedURL?.lastPathComponent ?? "-")")
            }
        } catch {
            NSLog("LegacyOutboxDrain: FAILED (will retry next launch): \(error)")
        }
    }
}
