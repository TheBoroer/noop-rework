import Foundation
import Shared

// MARK: - v17 store: Lab Book markers
//
// LabMarkerStore.swift — GRDB CRUD over the `labMarker` table (migration v17), the
// source-of-truth for the Health Records "Lab Book" pillar
// (spec 2026-06-19-v5-health-records-design.md §"New").
//
// The book is `labMarker` (one row per dated reading the user entered themselves);
// the daily `metricSeries` projection under source id `lab-book` is HOW the book talks
// to the rest of the app (Compare / Explore / correlation / Coach see it unchanged).
// On every write this store ALSO upserts that daily projection, exactly as the spec
// requires, so the two never drift.
//
// Mirrors the established `MetricSeriesStore` / `DeviceRegistryStore` idiom precisely:
// a plain Codable row struct, raw `Row` fetch + manual decode, idempotent upserts keyed
// by the natural key, all GRDB work via the actor's `syncWrite` / `syncRead` helpers.
//
// NON-CLINICAL: stores ONLY user-entered values + an OPTIONAL user-entered
// `referenceText` (their own report's range, verbatim). No reference-range tables, no
// normality judgement, ever.

/// One stored Lab Book reading. Natural key (deviceId, markerKey, takenAt, source) is
/// enforced by a UNIQUE index so re-importing the same reading is idempotent; `id` is a
/// stable client-generated identifier for edit/delete-by-id and backup round-trips.
public struct LabMarkerRow: Equatable, Codable, Sendable {
    public var id: String
    public var deviceId: String
    public var markerKey: String
    public var category: String
    /// Pre-derived yyyy-MM-dd day key (the projection key for this reading).
    public var day: String
    /// Precise instant the reading was taken (epoch seconds).
    public var takenAt: Int
    /// Numeric reading. Nil for a qualitative entry whose meaning is in `valueText`.
    public var value: Double?
    public var valueText: String?
    public var unit: String
    public var source: String
    public var note: String?
    /// User-entered reference range, shown back verbatim. NOOP ships none.
    public var referenceText: String?

    public init(
        id: String,
        deviceId: String,
        markerKey: String,
        category: String,
        day: String,
        takenAt: Int,
        value: Double?,
        valueText: String?,
        unit: String,
        source: String,
        note: String?,
        referenceText: String?
    ) {
        self.id = id
        self.deviceId = deviceId
        self.markerKey = markerKey
        self.category = category
        self.day = day
        self.takenAt = takenAt
        self.value = value
        self.valueText = valueText
        self.unit = unit
        self.source = source
        self.note = note
        self.referenceText = referenceText
    }
}

extension WhoopStore {

    /// The constant device-id the daily marker projection is written under, so Compare/
    /// Explore/Coach see markers as a single-source series (spec §"Cross-platform plan").
    public static let labBookSourceId = "lab-book"

    // MARK: - Upsert (idempotent by natural key) + project to metricSeries

    /// Upsert lab-marker rows, then re-project the affected (markerKey, day) cells into
    /// `metricSeries` under `lab-book`. Idempotent: re-upserting the same
    /// (deviceId, markerKey, takenAt, source) updates that reading in place (UNIQUE
    /// index `idx_labMarker_natural`) and the projection reflects the new value.
    ///
    /// The daily projection rule is LATEST-per-day: the reading with the greatest
    /// `takenAt` for a (markerKey, day) wins — byte-identical to
    /// `LabBookProjection.project(.latest)`. Only NUMERIC readings project (a
    /// REAL-only `metricSeries` cell can't carry `valueText`).
    ///
    /// Returns the number of marker rows written/updated.
    @discardableResult
    public func upsertLabMarkers(_ rows: [LabMarkerRow]) async throws -> Int {
        guard !rows.isEmpty else { return 0 }
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            // upsertLabMarkersPreservingId upserts each row on the natural key (deviceId,
            // markerKey, takenAt, source), preserving the pre-existing row's id on conflict
            // (matching the GRDB ON CONFLICT clause in the legacy branch below), AND runs the
            // reprojectCell pass per touched cell internally, so no manual reprojection loop
            // is needed here.
            return Int(truncating: try await roomDb.whoopDao().upsertLabMarkersPreservingId(rows: rows.map { r in
                Shared.LabMarkerRow(
                    id: r.id, deviceId: r.deviceId, markerKey: r.markerKey, category: r.category,
                    day: r.day, takenAt: Int64(r.takenAt),
                    value: r.value.map { KotlinDouble(double: $0) }, valueText: r.valueText,
                    unit: r.unit, source: r.source, note: r.note, referenceText: r.referenceText
                )
            }))
            #endif
        }
    }

    // MARK: - Reads

    /// All readings in a category, oldest first (by takenAt). Served by
    /// `idx_labMarker_device_category` + the takenAt index.
    public func labMarkers(deviceId: String, category: String) async throws -> [LabMarkerRow] {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let rows = try await roomDb.whoopDao().labMarkersByCategory(deviceId: deviceId, category: category)
            return rows.map { row in
                LabMarkerRow(
                    id: row.id, deviceId: row.deviceId, markerKey: row.markerKey, category: row.category,
                    day: row.day, takenAt: Int(row.takenAt),
                    value: row.value.map { Double(truncating: $0) }, valueText: row.valueText,
                    unit: row.unit, source: row.source, note: row.note, referenceText: row.referenceText
                )
            }
            #endif
        }
    }

    /// Full reading history for one marker, oldest first (by takenAt). Served
    /// index-only by `idx_labMarker_device_marker_takenAt`.
    public func labMarkers(deviceId: String, markerKey: String) async throws -> [LabMarkerRow] {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let rows = try await roomDb.whoopDao().labMarkersByKey(deviceId: deviceId, markerKey: markerKey)
            return rows.map { row in
                LabMarkerRow(
                    id: row.id, deviceId: row.deviceId, markerKey: row.markerKey, category: row.category,
                    day: row.day, takenAt: Int(row.takenAt),
                    value: row.value.map { Double(truncating: $0) }, valueText: row.valueText,
                    unit: row.unit, source: row.source, note: row.note, referenceText: row.referenceText
                )
            }
            #endif
        }
    }

    /// Distinct marker keys present for a device, sorted ascending.
    public func markerKeysPresent(deviceId: String) async throws -> [String] {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            return try await roomDb.whoopDao().markerKeysPresent(deviceId: deviceId)
            #endif
        }
    }

    // MARK: - Delete (removes the row AND its now-orphaned projected day)

    /// Delete one reading by `id`. If that was the last numeric reading for its
    /// (markerKey, day) cell, the projected `metricSeries` day is removed too; otherwise
    /// the projection is recomputed from the remaining same-day readings. Returns true if
    /// a row was deleted.
    @discardableResult
    public func deleteLabMarker(id: String) async throws -> Bool {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            // deleteLabMarker already captures the row's cell, deletes it, and reprojects
            // that cell internally, matching the GRDB legacy branch's sequence exactly.
            return Bool(truncating: try await roomDb.whoopDao().deleteLabMarker(id: id))
            #endif
        }
    }
}
