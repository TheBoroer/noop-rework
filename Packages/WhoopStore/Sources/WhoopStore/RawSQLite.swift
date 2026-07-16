import Foundation
import SQLite3

/// Minimal raw-sqlite3 query helper for the package's two file-probing utilities
/// (`DatabaseIntegrity.quickCheckFailure` and `WhoopStore.quarantineIncompatibleDatabase`).
///
/// #65 T2: these probes ran on GRDB's `DatabaseQueue` purely for connection plumbing — no
/// migrations, no records, no pool. Reimplementing them on the system SQLite (mirroring the
/// app-side `DataBackup.sqliteTableNames(at:)` pattern) severs the last *runtime* GRDB use in
/// probing paths so T7 can drop the dependency. Behaviour notes preserved from the GRDB
/// versions: a read-only open never creates -wal/-shm siblings or flips the journal mode, so
/// probing a valid live store can't disturb it; the read-write flag (WITHOUT `CREATE` — the
/// probed file always exists) builds the -shm for checkpointed-WAL backups shipped without
/// their sidecars (#18).
enum RawSQLite {

    /// Query/open failure carrying SQLite's own message, so callers that surface
    /// `localizedDescription` keep an honest, specific string (not a generic Cocoa error).
    struct QueryError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// Run `sql` and return column 0 of every row as text (NULLs skipped), opened read-only by
    /// default. Throws `QueryError` with SQLite's error message on open/prepare/step failure.
    static func queryStrings(atPath path: String, sql: String, readonly: Bool = true) throws -> [String] {
        var db: OpaquePointer?
        let flags = readonly ? SQLITE_OPEN_READONLY : SQLITE_OPEN_READWRITE
        guard sqlite3_open_v2(path, &db, flags, nil) == SQLITE_OK else {
            let message = db.map { String(cString: sqlite3_errmsg($0)) } ?? "unable to open database file"
            sqlite3_close(db)
            throw QueryError(message: message)
        }
        defer { sqlite3_close(db) }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            throw QueryError(message: String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        var rows: [String] = []
        while true {
            switch sqlite3_step(stmt) {
            case SQLITE_ROW:
                if let text = sqlite3_column_text(stmt, 0) { rows.append(String(cString: text)) }
            case SQLITE_DONE:
                return rows
            default:
                throw QueryError(message: String(cString: sqlite3_errmsg(db)))
            }
        }
    }
}
