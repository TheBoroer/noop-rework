import Foundation
import SQLite3

/// Tells sqlite3_bind_text/blob to copy the bytes immediately (the Swift String or
/// Data backing them may move or be freed before the statement is finalized).
private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// A decoded SQLite column value. The accessors implement the numeric coercions the
/// previous GRDB call sites relied on: GRDB converts freely between INTEGER, REAL and
/// TEXT on read, so an INTEGER column read as Double (or a REAL column read as Int)
/// keeps working here too.
enum SQLiteValue: Equatable {
    case int(Int64)
    case double(Double)
    case text(String)
    case blob(Data)
    case null

    /// Int, coercing REAL and TEXT storage. Non-finite or out-of-Int-range doubles
    /// return nil rather than trapping (a hostile file can hold 1e308, inf, or nan).
    var intValue: Int? {
        switch self {
        case .int(let n):
            return Int(exactly: n)
        case .double(let d):
            guard d.isFinite, d >= -9e18, d <= 9e18 else { return nil }
            return Int(d)
        case .text(let s):
            return Int(s)
        case .blob, .null:
            return nil
        }
    }

    /// Double, coercing INTEGER and TEXT storage.
    var doubleValue: Double? {
        switch self {
        case .int(let n):
            return Double(n)
        case .double(let d):
            return d
        case .text(let s):
            return Double(s)
        case .blob, .null:
            return nil
        }
    }

    /// String, rendering INTEGER and REAL storage numerically (as GRDB would).
    var stringValue: String? {
        switch self {
        case .text(let s):
            return s
        case .int(let n):
            return String(n)
        case .double(let d):
            return String(d)
        case .blob, .null:
            return nil
        }
    }
}

/// A value that can be bound to a `?` placeholder.
protocol SQLiteBindable {
    var sqliteValue: SQLiteValue { get }
}

extension Int: SQLiteBindable { var sqliteValue: SQLiteValue { .int(Int64(self)) } }
extension Int64: SQLiteBindable { var sqliteValue: SQLiteValue { .int(self) } }
extension Double: SQLiteBindable { var sqliteValue: SQLiteValue { .double(self) } }
extension String: SQLiteBindable { var sqliteValue: SQLiteValue { .text(self) } }
extension Data: SQLiteBindable { var sqliteValue: SQLiteValue { .blob(self) } }

/// Minimal raw-sqlite3 connection, modeled on WhoopStore's RawSQLite probe helper
/// (the pattern that retired GRDB from the main app package), extended with full-row
/// reads and parameter binding for the Mi Fitness importer.
///
/// Behaviour notes preserved from the GRDB version:
/// - a read-only open never creates -wal/-shm siblings or flips the journal mode,
///   so reading a valid live store cannot disturb it
/// - open/prepare/bind/step failures throw `QueryError` carrying SQLite's own
///   message, so callers that surface `localizedDescription` keep an honest,
///   specific string (not a generic Cocoa error)
/// - all access is serialized with a lock, mirroring GRDB's DatabaseQueue guarantee
///   that one connection is never used from two threads at once
final class SQLiteConnection {

    struct QueryError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private var db: OpaquePointer?
    private let lock = NSLock()

    /// Opens read-only by default. `readonly: false` opens read-write and creates the
    /// file when missing (test fixtures only). `busyTimeoutMs` mirrors GRDB's
    /// `busyMode = .timeout(...)` for callers that configured one.
    init(path: String, readonly: Bool = true, busyTimeoutMs: Int32? = nil) throws {
        let flags = readonly ? SQLITE_OPEN_READONLY : (SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE)
        var handle: OpaquePointer?
        guard sqlite3_open_v2(path, &handle, flags, nil) == SQLITE_OK else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unable to open database file"
            sqlite3_close(handle)
            throw QueryError(message: message)
        }
        db = handle
        if let busyTimeoutMs {
            sqlite3_busy_timeout(handle, busyTimeoutMs)
        }
    }

    deinit {
        sqlite3_close_v2(db)
    }

    /// True when SQLite itself reports the main database as read-only.
    var isReadOnly: Bool {
        sqlite3_db_readonly(db, "main") == 1
    }

    /// Run a SELECT and return every row keyed by column name.
    func fetchRows(sql: String, bindings: [SQLiteBindable] = []) throws -> [[String: SQLiteValue]] {
        lock.lock()
        defer { lock.unlock() }
        let stmt = try prepare(sql, bindings)
        defer { sqlite3_finalize(stmt) }
        let columnCount = sqlite3_column_count(stmt)
        let names = (0..<columnCount).map { String(cString: sqlite3_column_name(stmt, $0)) }
        var rows: [[String: SQLiteValue]] = []
        while true {
            switch sqlite3_step(stmt) {
            case SQLITE_ROW:
                var row: [String: SQLiteValue] = [:]
                row.reserveCapacity(names.count)
                for i in 0..<columnCount {
                    row[names[Int(i)]] = columnValue(stmt, i)
                }
                rows.append(row)
            case SQLITE_DONE:
                return rows
            default:
                throw queryError()
            }
        }
    }

    /// Column 0 of the first row as Int, nil when there is no row or it is NULL.
    /// Matches GRDB's `Int.fetchOne`, including `MAX(...)` over an empty table
    /// (one all-NULL row) coming back as nil.
    func fetchOneInt(sql: String, bindings: [SQLiteBindable] = []) throws -> Int? {
        lock.lock()
        defer { lock.unlock() }
        let stmt = try prepare(sql, bindings)
        defer { sqlite3_finalize(stmt) }
        switch sqlite3_step(stmt) {
        case SQLITE_ROW:
            return columnValue(stmt, 0).intValue
        case SQLITE_DONE:
            return nil
        default:
            throw queryError()
        }
    }

    /// Run a single non-SELECT statement (fixtures and the write probe).
    func execute(sql: String, bindings: [SQLiteBindable] = []) throws {
        lock.lock()
        defer { lock.unlock() }
        let stmt = try prepare(sql, bindings)
        defer { sqlite3_finalize(stmt) }
        while true {
            switch sqlite3_step(stmt) {
            case SQLITE_ROW:
                continue
            case SQLITE_DONE:
                return
            default:
                throw queryError()
            }
        }
    }

    private func prepare(_ sql: String, _ bindings: [SQLiteBindable]) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw queryError()
        }
        for (i, binding) in bindings.enumerated() {
            let index = Int32(i + 1)
            let rc: Int32
            switch binding.sqliteValue {
            case .int(let n):
                rc = sqlite3_bind_int64(stmt, index, n)
            case .double(let d):
                rc = sqlite3_bind_double(stmt, index, d)
            case .text(let s):
                rc = sqlite3_bind_text(stmt, index, s, -1, SQLITE_TRANSIENT)
            case .blob(let data):
                rc = data.withUnsafeBytes {
                    sqlite3_bind_blob(stmt, index, $0.baseAddress, Int32(data.count), SQLITE_TRANSIENT)
                }
            case .null:
                rc = sqlite3_bind_null(stmt, index)
            }
            guard rc == SQLITE_OK else {
                let error = queryError()
                sqlite3_finalize(stmt)
                throw error
            }
        }
        return stmt
    }

    private func columnValue(_ stmt: OpaquePointer, _ index: Int32) -> SQLiteValue {
        switch sqlite3_column_type(stmt, index) {
        case SQLITE_INTEGER:
            return .int(sqlite3_column_int64(stmt, index))
        case SQLITE_FLOAT:
            return .double(sqlite3_column_double(stmt, index))
        case SQLITE_TEXT:
            return .text(sqlite3_column_text(stmt, index).map { String(cString: $0) } ?? "")
        case SQLITE_BLOB:
            if let bytes = sqlite3_column_blob(stmt, index) {
                return .blob(Data(bytes: bytes, count: Int(sqlite3_column_bytes(stmt, index))))
            }
            return .blob(Data())
        default:
            return .null
        }
    }

    private func queryError() -> QueryError {
        QueryError(message: String(cString: sqlite3_errmsg(db)))
    }
}
