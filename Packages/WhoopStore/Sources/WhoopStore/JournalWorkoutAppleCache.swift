import Foundation
import Shared

// MARK: - v8 cache: journal entries, workouts, and Apple-Health daily aggregates
// Mirrors the MetricsCache pattern: Codable structs, idempotent ON CONFLICT upserts keyed by
// natural key, and range-read accessors. All write/read GRDB work runs via the actor's
// syncWrite/syncRead helpers (off the main thread).

/// One journal answer. Natural key (deviceId, day, question).
public struct JournalEntry: Equatable, Codable {
    public let day: String          // YYYY-MM-DD
    public let question: String
    public let answeredYes: Bool
    public let notes: String?
    /// Optional numeric reading for a numeric journal item (e.g. caffeine mg, alcohol units).
    /// nil for a plain yes/no answer and for every imported WHOOP row (#322). A numeric log writes
    /// answeredYes=true AND numericValue=v, so the BehaviorInsights with/without split is unchanged.
    public let numericValue: Double?
    public init(day: String, question: String, answeredYes: Bool, notes: String?,
                numericValue: Double? = nil) {
        self.day = day; self.question = question
        self.answeredYes = answeredYes; self.notes = notes
        self.numericValue = numericValue
    }
}

/// One workout. Natural key (deviceId, startTs, sport). All metric columns nullable.
/// `zonesJSON` is verbatim JSON of HR-zone percentages, stored as a string so the cache stays
/// schema-agnostic about the zone shape.
public struct WorkoutRow: Equatable, Codable {
    public let startTs: Int          // unix seconds
    public let endTs: Int            // unix seconds
    public let sport: String
    public let source: String
    public let durationS: Double?
    public let energyKcal: Double?
    public let avgHr: Int?
    public let maxHr: Int?
    public let strain: Double?
    public let distanceM: Double?
    public let zonesJSON: String?
    public let notes: String?
    public init(startTs: Int, endTs: Int, sport: String, source: String, durationS: Double?,
                energyKcal: Double?, avgHr: Int?, maxHr: Int?, strain: Double?, distanceM: Double?,
                zonesJSON: String?, notes: String?) {
        self.startTs = startTs; self.endTs = endTs; self.sport = sport; self.source = source
        self.durationS = durationS; self.energyKcal = energyKcal; self.avgHr = avgHr
        self.maxHr = maxHr; self.strain = strain; self.distanceM = distanceM
        self.zonesJSON = zonesJSON; self.notes = notes
    }
}

/// One Apple-Health daily-aggregate row. Natural key (deviceId, day). All metric columns nullable.
public struct AppleDaily: Equatable, Codable {
    public let day: String           // YYYY-MM-DD
    public let steps: Int?
    public let activeKcal: Double?
    public let basalKcal: Double?
    public let vo2max: Double?
    public let avgHr: Int?
    public let maxHr: Int?
    public let walkingHr: Int?
    public let weightKg: Double?
    public init(day: String, steps: Int?, activeKcal: Double?, basalKcal: Double?, vo2max: Double?,
                avgHr: Int?, maxHr: Int?, walkingHr: Int?, weightKg: Double?) {
        self.day = day; self.steps = steps; self.activeKcal = activeKcal; self.basalKcal = basalKcal
        self.vo2max = vo2max; self.avgHr = avgHr; self.maxHr = maxHr
        self.walkingHr = walkingHr; self.weightKg = weightKg
    }
}

extension WhoopStore {

    // MARK: - Upserts (idempotent by natural key; latest value wins on conflict)

    /// Upsert journal entries. Natural key (deviceId, day, question). Returns rows changed.
    @discardableResult
    public func upsertJournal(_ rows: [JournalEntry], deviceId: String) async throws -> Int {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            // @Upsert on the natural key (deviceId, day, question): every row is guaranteed to touch
            // exactly one, matching GRDB's accumulated changesCount above.
            try await roomDb.whoopDao().upsertJournal(rows: rows.map { r in
                Shared.JournalEntry(deviceId: deviceId, day: r.day, question: r.question,
                                     answeredYes: r.answeredYes, notes: r.notes,
                                     numericValue: r.numericValue.map { KotlinDouble(double: $0) })
            })
            return rows.count
            #endif
        }
    }

    /// Delete one journal answer by natural key (the native logging card's "clear"). Source-scoped
    /// by deviceId, so clearing a native ("noop-journal") answer never removes an identical imported
    /// row. Returns rows deleted.
    @discardableResult
    public func deleteJournal(deviceId: String, day: String, question: String) async throws -> Int {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            return Int(truncating: try await roomDb.whoopDao().deleteJournalEntry(
                deviceId: deviceId, day: day, question: question))
            #endif
        }
    }

    /// Atomically replace a device's journal within a day range (#136): clear [from, to] then upsert
    /// `rows`, all in ONE transaction, so the wake-day keying fix leaves no pre-fix onset-keyed duplicates
    /// AND a crash mid-import can't leave the range deleted-but-not-repopulated. Bounded to [from, to] —
    /// journal outside the imported range is never touched. deviceId-scoped (native log untouched).
    @discardableResult
    public func replaceJournalRange(_ rows: [JournalEntry], deviceId: String,
                                    from: String, to: String) async throws -> Int {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            // Kotlin's replaceJournalRange is a @Transaction wrapper (delete [from, to] then upsert
            // rows) mirroring this exact GRDB sequence; it returns Unit, so report rows upserted, same
            // as the GRDB branch's `n` (which never counted the delete's changesCount either).
            try await roomDb.whoopDao().replaceJournalRange(
                deviceId: deviceId, from: from, to: to,
                rows: rows.map { r in
                    Shared.JournalEntry(deviceId: deviceId, day: r.day, question: r.question,
                                         answeredYes: r.answeredYes, notes: r.notes,
                                         numericValue: r.numericValue.map { KotlinDouble(double: $0) })
                })
            return rows.count
            #endif
        }
    }

    /// Upsert workouts. Natural key (deviceId, startTs, sport). Returns rows changed.
    @discardableResult
    public func upsertWorkouts(_ rows: [WorkoutRow], deviceId: String) async throws -> Int {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            // upsertWorkoutPreservingRoute, NOT the plain @Upsert: Swift's WorkoutRow has no
            // routePolyline field, so the plain @Upsert (a REPLACE) would clobber a GPS route Android
            // already recorded for this workout. This per-row @Query omits routePolyline from both the
            // INSERT column list and the ON CONFLICT SET clause, byte-identical to the GRDB SQL below.
            // Every call touches exactly one row (insert or update, no DO NOTHING branch), matching
            // what GRDB's accumulated changesCount would have summed to.
            let dao = roomDb.whoopDao()
            for r in rows {
                _ = try await dao.upsertWorkoutPreservingRoute(
                    deviceId: deviceId, startTs: Int64(r.startTs), endTs: Int64(r.endTs),
                    sport: r.sport, source: r.source,
                    durationS: r.durationS.map { KotlinDouble(double: $0) },
                    energyKcal: r.energyKcal.map { KotlinDouble(double: $0) },
                    avgHr: r.avgHr.map { KotlinInt(int: Int32($0)) },
                    maxHr: r.maxHr.map { KotlinInt(int: Int32($0)) },
                    strain: r.strain.map { KotlinDouble(double: $0) },
                    distanceM: r.distanceM.map { KotlinDouble(double: $0) },
                    zonesJSON: r.zonesJSON, notes: r.notes)
            }
            return rows.count
            #endif
        }
    }

    /// Delete one source's workouts of a given sport whose startTs is in [from, to]
    /// (makes detected-workout re-derivation idempotent). Returns rows deleted.
    /// Port of Android WhoopDao.deleteWorkoutsBySport (#78).
    @discardableResult
    public func deleteWorkouts(deviceId: String, sport: String, from: Int, to: Int) async throws -> Int {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            return Int(truncating: try await roomDb.whoopDao().deleteWorkoutsBySport(
                deviceId: deviceId, sport: sport, from: Int64(from), to: Int64(to)))
            #endif
        }
    }

    /// Upsert Apple-Health daily aggregates. Natural key (deviceId, day). Returns rows changed.
    @discardableResult
    public func upsertAppleDaily(_ rows: [AppleDaily], deviceId: String) async throws -> Int {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            // @Upsert on the natural key (deviceId, day): every row is guaranteed to touch exactly
            // one, matching GRDB's accumulated changesCount above.
            try await roomDb.whoopDao().upsertAppleDaily(rows: rows.map { r in
                Shared.AppleDaily(
                    deviceId: deviceId, day: r.day,
                    steps: r.steps.map { KotlinInt(int: Int32($0)) },
                    activeKcal: r.activeKcal.map { KotlinDouble(double: $0) },
                    basalKcal: r.basalKcal.map { KotlinDouble(double: $0) },
                    vo2max: r.vo2max.map { KotlinDouble(double: $0) },
                    avgHr: r.avgHr.map { KotlinInt(int: Int32($0)) },
                    maxHr: r.maxHr.map { KotlinInt(int: Int32($0)) },
                    walkingHr: r.walkingHr.map { KotlinInt(int: Int32($0)) },
                    weightKg: r.weightKg.map { KotlinDouble(double: $0) })
            })
            return rows.count
            #endif
        }
    }

    // MARK: - Reads

    /// Journal entries for days in [from, to] (lexicographic YYYY-MM-DD compare),
    /// oldest day first, then by question.
    public func journalEntries(deviceId: String, from: String, to: String) async throws -> [JournalEntry] {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let rows = try await roomDb.whoopDao().journal(deviceId: deviceId, from: from, to: to)
            return rows.map { row in
                JournalEntry(day: row.day, question: row.question, answeredYes: row.answeredYes,
                             notes: row.notes,
                             numericValue: row.numericValue.map { Double(truncating: $0) })
            }
            #endif
        }
    }

    /// Workouts overlapping [from, to] (by startTs), oldest first.
    public func workouts(deviceId: String, from: Int, to: Int, limit: Int) async throws -> [WorkoutRow] {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let rows = try await roomDb.whoopDao().workouts(
                deviceId: deviceId, from: Int64(from), to: Int64(to), limit: Int32(limit))
            // Kotlin's WorkoutRow carries an extra routePolyline field (GPS route, Android-only
            // concern here); Swift's WorkoutRow has no such field, so it is simply never read.
            return rows.map { row in
                WorkoutRow(startTs: Int(row.startTs), endTs: Int(row.endTs), sport: row.sport,
                           source: row.source,
                           durationS: row.durationS.map { Double(truncating: $0) },
                           energyKcal: row.energyKcal.map { Double(truncating: $0) },
                           avgHr: row.avgHr.map { Int(truncating: $0) },
                           maxHr: row.maxHr.map { Int(truncating: $0) },
                           strain: row.strain.map { Double(truncating: $0) },
                           distanceM: row.distanceM.map { Double(truncating: $0) },
                           zonesJSON: row.zonesJSON, notes: row.notes)
            }
            #endif
        }
    }

    /// Apple-Health daily aggregates for days in [from, to] (lexicographic compare), oldest first.
    public func appleDaily(deviceId: String, from: String, to: String) async throws -> [AppleDaily] {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let rows = try await roomDb.whoopDao().appleDaily(deviceId: deviceId, from: from, to: to)
            return rows.map { row in
                AppleDaily(day: row.day, steps: row.steps.map { Int(truncating: $0) },
                           activeKcal: row.activeKcal.map { Double(truncating: $0) },
                           basalKcal: row.basalKcal.map { Double(truncating: $0) },
                           vo2max: row.vo2max.map { Double(truncating: $0) },
                           avgHr: row.avgHr.map { Int(truncating: $0) },
                           maxHr: row.maxHr.map { Int(truncating: $0) },
                           walkingHr: row.walkingHr.map { Int(truncating: $0) },
                           weightKg: row.weightKg.map { Double(truncating: $0) })
            }
            #endif
        }
    }
}
