import Foundation
import Shared

// MARK: - v22 store: Live Sessions
//
// LiveSessionStore.swift — GRDB CRUD over the `liveSession` table (migration v22), the durable record
// behind the Live Sessions look-back summary + streak. Mirrors the established idiom exactly: a plain
// Codable row struct, raw `Row` fetch + manual decode, idempotent upsert keyed by the natural key
// (deviceId, startTs), all GRDB work via the actor's `syncWrite` / `syncRead` helpers.
//
// Design contract: docs/superpowers/specs/2026-07-04-live-sessions-design.md.

/// One Live Session (silent guardian) record. Natural key (deviceId, startTs). `endTs` is nil while the
/// session is still in progress. All second-totals are non-negative; the two cue counts are how many push
/// nudges / ease-offs the coach sent that session.
public struct LiveSessionRow: Equatable, Codable {
    public let startTs: Int              // unix seconds — session start (the key)
    public let endTs: Int?               // unix seconds — nil while in progress
    public let chargeAtStart: Double?    // 0...100 recovery Charge at start; nil if unknown
    public let floorBpm: Double          // guarded band, floor
    public let ceilingBpm: Double        // guarded band, ceiling
    public let inBandSec: Double
    public let belowSec: Double
    public let aboveSec: Double
    public let pushCount: Int            // "give more" cues sent
    public let easeCount: Int            // "ease off" cues sent
    public let hrSource: String          // "whoop" | "strap" | "power" etc.

    public init(startTs: Int, endTs: Int?, chargeAtStart: Double?, floorBpm: Double, ceilingBpm: Double,
                inBandSec: Double, belowSec: Double, aboveSec: Double, pushCount: Int, easeCount: Int,
                hrSource: String) {
        self.startTs = startTs; self.endTs = endTs; self.chargeAtStart = chargeAtStart
        self.floorBpm = floorBpm; self.ceilingBpm = ceilingBpm
        self.inBandSec = inBandSec; self.belowSec = belowSec; self.aboveSec = aboveSec
        self.pushCount = pushCount; self.easeCount = easeCount; self.hrSource = hrSource
    }
}

extension WhoopStore {

    /// Upsert one Live Session. Natural key (deviceId, startTs) — called once at start (endTs nil) and
    /// again at end with the final totals. Returns rows changed.
    @discardableResult
    public func upsertLiveSession(_ r: LiveSessionRow, deviceId: String) async throws -> Int {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            // @Upsert on the natural key (deviceId, startTs): a single row is always touched,
            // matching the GRDB branch's single-row changesCount below.
            try await roomDb.whoopDao().upsertLiveSession(row: Shared.LiveSessionRow(
                deviceId: deviceId, startTs: Int64(r.startTs),
                endTs: r.endTs.map { KotlinLong(longLong: Int64($0)) },
                chargeAtStart: r.chargeAtStart.map { KotlinDouble(double: $0) },
                floorBpm: r.floorBpm, ceilingBpm: r.ceilingBpm,
                inBandSec: r.inBandSec, belowSec: r.belowSec, aboveSec: r.aboveSec,
                pushCount: Int32(r.pushCount), easeCount: Int32(r.easeCount), hrSource: r.hrSource
            ))
            return 1
            #endif
        }
    }

    /// The most-recent sessions first, for the look-back summary + streak. Newest by startTs.
    public func recentLiveSessions(deviceId: String, limit: Int) async throws -> [LiveSessionRow] {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let rows = try await roomDb.whoopDao().recentLiveSessions(deviceId: deviceId, limit: Int32(limit))
            return rows.map { row in
                LiveSessionRow(startTs: Int(row.startTs), endTs: row.endTs.map { Int(truncating: $0) },
                               chargeAtStart: row.chargeAtStart.map { Double(truncating: $0) },
                               floorBpm: row.floorBpm, ceilingBpm: row.ceilingBpm,
                               inBandSec: row.inBandSec, belowSec: row.belowSec, aboveSec: row.aboveSec,
                               pushCount: Int(row.pushCount), easeCount: Int(row.easeCount),
                               hrSource: row.hrSource)
            }
            #endif
        }
    }
}
