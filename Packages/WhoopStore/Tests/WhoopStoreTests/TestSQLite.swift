import Foundation
import SQLite3

/// #65: test-only raw SQLite access (C API), replacing GRDB's `DatabaseQueue` in this test target
/// so the fixture plumbing survives the production GRDB removal. Serial and blocking: every call
/// opens, runs, and closes — fine for tests, wrong for production (use WhoopStore/Room there).
enum TestSQLite {

    struct SQLiteError: Error, CustomStringConvertible {
        let message: String
        var description: String { "TestSQLite: \(message)" }
    }

    /// Opens (creating unless `readOnly`) the database, runs `body`, always closes the handle.
    static func withDatabase<T>(atPath path: String,
                                readOnly: Bool = false,
                                _ body: (OpaquePointer) throws -> T) throws -> T {
        var db: OpaquePointer?
        let flags = readOnly ? SQLITE_OPEN_READONLY : (SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE)
        let rc = sqlite3_open_v2(path, &db, flags, nil)
        guard rc == SQLITE_OK, let handle = db else {
            let msg = db.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed (rc \(rc))"
            if let db { sqlite3_close_v2(db) }
            throw SQLiteError(message: "open \(path): \(msg)")
        }
        defer { sqlite3_close_v2(handle) }
        return try body(handle)
    }

    /// Executes one or more semicolon-separated statements that return no rows.
    static func exec(atPath path: String, _ sql: String) throws {
        try withDatabase(atPath: path) { db in
            var err: UnsafeMutablePointer<CChar>?
            guard sqlite3_exec(db, sql, nil, nil, &err) == SQLITE_OK else {
                let msg = err.map { String(cString: $0) } ?? "exec failed"
                sqlite3_free(err)
                throw SQLiteError(message: "exec `\(sql)`: \(msg)")
            }
        }
    }

    /// First column of every result row, rendered as text (NULLs skipped).
    static func queryStrings(atPath path: String, _ sql: String) throws -> [String] {
        try withDatabase(atPath: path, readOnly: false) { db in
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
                throw SQLiteError(message: "prepare `\(sql)`: \(String(cString: sqlite3_errmsg(db)))")
            }
            defer { sqlite3_finalize(stmt) }
            var rows: [String] = []
            while true {
                let rc = sqlite3_step(stmt)
                if rc == SQLITE_ROW {
                    if let text = sqlite3_column_text(stmt, 0) {
                        rows.append(String(cString: text))
                    }
                } else if rc == SQLITE_DONE {
                    return rows
                } else {
                    throw SQLiteError(message: "step `\(sql)`: \(String(cString: sqlite3_errmsg(db)))")
                }
            }
        }
    }

    /// Single scalar, as text. `nil` when the query yields no row (or a NULL).
    static func queryString(atPath path: String, _ sql: String) throws -> String? {
        try queryStrings(atPath: path, sql).first
    }

    /// Single scalar, as Int. `nil` when the query yields no row (or a NULL).
    static func queryInt(atPath path: String, _ sql: String) throws -> Int? {
        try queryString(atPath: path, sql).flatMap { Int($0) ?? (Double($0).map { Int($0) }) }
    }

    /// All user-visible table names (includes bookkeeping like `grdb_migrations` /
    /// `room_master_table`, which is exactly what the schema-origin tests inspect).
    static func tableNames(atPath path: String) throws -> Set<String> {
        Set(try queryStrings(atPath: path, "SELECT name FROM sqlite_master WHERE type = 'table'"))
    }

    // MARK: - Frozen legacy-schema fixture

    /// The frozen full legacy-GRDB schema (every `makeMigrator` migration applied to an empty
    /// database, generated while GRDB was still linked — see Fixtures/legacy-grdb-full-schema.sql
    /// for the human-readable dump). Lets ETL/backup tests build "a real legacy store" without GRDB.
    static var frozenLegacySchemaURL: URL {
        get throws {
            guard let url = Bundle.module.url(forResource: "legacy-grdb-full-schema",
                                              withExtension: "sqlite",
                                              subdirectory: "Fixtures") else {
                throw SQLiteError(message: "legacy-grdb-full-schema.sqlite missing from test bundle")
            }
            return url
        }
    }

    /// Copies the frozen legacy schema to `path` (must not exist), returning `path`.
    @discardableResult
    static func installFrozenLegacySchema(atPath path: String) throws -> String {
        try FileManager.default.copyItem(at: frozenLegacySchemaURL,
                                         to: URL(fileURLWithPath: path))
        return path
    }
}
