import Foundation
import GRDB
import Shared

// MARK: - v9 cache: generic long-format metric store
// The substrate for a metric explorer. Where MetricsCache / JournalWorkoutAppleCache use a
// WIDE column-per-metric layout (one table per source, typed nullable columns), this is the
// TALL/EAV counterpart: one row per (deviceId, day, key) with a single REAL `value`. Any scalar
// metric — whatever its origin — can be projected into this one table and read back uniformly by
// key, so the explorer can list/compare metrics without knowing each source's schema.
// Mirrors the established pattern exactly: Codable struct, idempotent ON CONFLICT upsert keyed by
// natural key, range-read accessors, all GRDB work via the actor's syncWrite/syncRead helpers.

/// One point in the long-format metric store. Natural key (deviceId, day, key).
public struct MetricPoint: Equatable, Codable, Sendable {
    public let day: String           // YYYY-MM-DD
    public let key: String           // metric identifier, e.g. "restingHr", "steps", "recovery"
    public let value: Double
    public init(day: String, key: String, value: Double) {
        self.day = day; self.key = key; self.value = value
    }
}

extension WhoopStore {

    // MARK: - Upsert (idempotent by natural key; latest value wins on conflict)

    /// Upsert metric points. Natural key (deviceId, day, key). Returns rows changed.
    /// Idempotent: re-upserting the same (deviceId, day, key) updates `value` in place rather than
    /// creating a duplicate.
    @discardableResult
    public func upsertMetricSeries(_ rows: [MetricPoint], deviceId: String) async throws -> Int {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            // @Upsert on the natural key (deviceId, day, key): every row is guaranteed to touch
            // exactly one, matching GRDB's accumulated changesCount above.
            try await roomDb.whoopDao().upsertMetricSeries(rows: rows.map { r in
                Shared.MetricSeriesRow(deviceId: deviceId, day: r.day, key: r.key, value: r.value)
            })
            return rows.count
            #endif
        case .legacyGrdb:
            return try syncWrite { db in
                var n = 0
                for r in rows {
                    try db.execute(sql: """
                        INSERT INTO metricSeries
                            (deviceId, day, key, value)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(deviceId, day, key) DO UPDATE SET
                            value = excluded.value
                        """, arguments: [deviceId, r.day, r.key, r.value])
                    n += db.changesCount
                }
                return n
            }
        }
    }

    // MARK: - Reads

    /// Points for a single `key` on days in [from, to] (lexicographic YYYY-MM-DD compare),
    /// oldest day first. Served index-only by idx_metricSeries_device_key_day.
    public func metricSeries(deviceId: String, key: String, from: String, to: String) async throws -> [MetricPoint] {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let rows = try await roomDb.whoopDao().metricSeries(deviceId: deviceId, key: key, from: from, to: to)
            return rows.map { MetricPoint(day: $0.day, key: $0.key, value: $0.value) }
            #endif
        case .legacyGrdb:
            return try syncRead { db in
                try Row.fetchAll(db, sql: """
                    SELECT day, key, value FROM metricSeries
                    WHERE deviceId = ? AND key = ? AND day >= ? AND day <= ?
                    ORDER BY day ASC
                    """, arguments: [deviceId, key, from, to])
                    .map { MetricPoint(day: $0["day"], key: $0["key"], value: $0["value"]) }
            }
        }
    }

    /// Distinct metric keys present for a device, sorted ascending.
    public func metricKeys(deviceId: String) async throws -> [String] {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            return try await roomDb.whoopDao().metricKeys(deviceId: deviceId)
            #endif
        case .legacyGrdb:
            return try syncRead { db in
                try String.fetchAll(db, sql: """
                    SELECT DISTINCT key FROM metricSeries
                    WHERE deviceId = ?
                    ORDER BY key ASC
                    """, arguments: [deviceId])
            }
        }
    }

    /// Earliest and latest day for a given metric `key`, or nil if the key has no points.
    public func metricDays(deviceId: String, key: String) async throws -> (earliest: String, latest: String)? {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let range = try await roomDb.whoopDao().metricDayRange(deviceId: deviceId, key: key)
            guard let earliest = range.earliest, let latest = range.latest else { return nil }
            return (earliest, latest)
            #endif
        case .legacyGrdb:
            return try syncRead { db in
                guard let row = try Row.fetchOne(db, sql: """
                    SELECT MIN(day) AS earliest, MAX(day) AS latest FROM metricSeries
                    WHERE deviceId = ? AND key = ?
                    """, arguments: [deviceId, key]),
                    let earliest: String = row["earliest"],
                    let latest: String = row["latest"]
                else { return nil }
                return (earliest, latest)
            }
        }
    }
}
