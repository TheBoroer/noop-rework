import Foundation
import Shared
import WhoopProtocol

extension WhoopStore {
    /// Outcome of a one-time implausible-timestamp heal (#547). `rawRowsDeleted` = garbage raw stream
    /// rows purged (HR/RR/SpO2/skinTemp/resp/gravity/step/ppgHr/event/battery). `computedRowsDeleted` =
    /// future/implausible computed daily-metric + sleep-session rows purged. `didChange` is true when
    /// anything was deleted, so the caller can trigger a single rescore instead of always re-running it.
    public struct TimestampHealResult: Equatable, Sendable {
        public let rawRowsDeleted: Int
        public let computedRowsDeleted: Int
        public var didChange: Bool { rawRowsDeleted > 0 || computedRowsDeleted > 0 }
        public init(rawRowsDeleted: Int, computedRowsDeleted: Int) {
            self.rawRowsDeleted = rawRowsDeleted
            self.computedRowsDeleted = computedRowsDeleted
        }
    }

    /// ONE-TIME repair of a database polluted by a bad-clock strap (#547, pikapik). Before the ingest
    /// gate landed, NOOP trusted each type-47 record's own unix timestamp verbatim, so a WHOOP with a
    /// broken clock/flash (repeated trim=0xFFFFFFFF) wrote rows dated to scattered garbage — far-past
    /// (2024/2029), a bogus 2027=1827642881, and FUTURE dates. The day-windows overlap, so one ~12h
    /// polluted block was re-attributed to every day (the repeated totalSleepMin=721 across 06-14..06-21)
    /// and a future-dated record made the Today "last night" carry-over read "12 Jul".
    ///
    /// This purges that garbage so a normal rescore recomputes the real days cleanly:
    ///   (a) raw stream rows whose `ts` is implausible (< MIN_PLAUSIBLE_UNIX or > now + FUTURE_MARGIN) —
    ///       across every time-keyed raw table the gate now protects;
    ///   (b) computed `dailyMetric` rows whose `day` is in the future (> today, local) or implausibly old,
    ///       and `sleepSession` rows whose `startTs` is future/implausible.
    /// The caller then triggers a normal `analyzeRecent` so the real days recompute (the 721 block is
    /// gone once its garbage raw rows are purged). Idempotent: a re-run on a clean DB deletes nothing.
    ///
    /// `now` and `todayLocalDayKey` are injected (default to the live wall clock / today's yyyy-MM-dd in
    /// the local zone) so the heal is deterministically unit-testable. Bounds come straight from
    /// WhoopProtocol so Swift and the Android Room cleanup reject the identical set.
    @discardableResult
    public func healImplausibleTimestamps(
        now: Int = Int(Date().timeIntervalSince1970),
        todayLocalDayKey: String = WhoopStore.localDayKey(Date())
    ) async throws -> TimestampHealResult {
        // #65 T3: heals the live Room `noop.db`. Historical note: during the migration window this
        // actor carried a vestigial GRDB pool and the heal always ran the GRDB SQL, so on a
        // Room store it "succeeded" against the wrong (empty) database and the polluted rows
        // survived every launch (the heal-wrong-DB bug) — the backend switch here fixed that.
        // Routes through the shared Kotlin `WhoopRepository.healImplausibleTimestamps` (the Android
        // heal), constructed per call over the live handle exactly like `OutboxBridge` — nothing is
        // stored. Bounds are the same WhoopProtocol ingest-gate constants both sides re-export.
        // One deliberate nuance: the Kotlin heal derives the far-past floor day (`minDay`) in the
        // LOCAL calendar (Android parity) where the retired GRDB path used a UTC day key — both are the same
        // 2023-11 sentinel ±1 day, and the floor only gates already-implausible `-noop` rows.
        switch backend {
        case .room(let room):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = room; throw RoomBackendUnavailableError()
            #else
            let counts = try await WhoopRepository(db: room).healImplausibleTimestamps(
                nowSec: Int64(now),
                today: todayLocalDayKey,
                minTs: Int64(WhoopProtocol.MIN_PLAUSIBLE_UNIX),
                futureMargin: Int64(WhoopProtocol.FUTURE_MARGIN)
            )
            return TimestampHealResult(rawRowsDeleted: Int(counts.rawRowsDeleted),
                                       computedRowsDeleted: Int(counts.computedRowsDeleted))
            #endif
        }
    }

    /// `yyyy-MM-dd` for `date` in the LOCAL calendar — matches how dailyMetric `day` keys are written.
    public static func localDayKey(_ date: Date) -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }

    /// `yyyy-MM-dd` for a unix second in UTC — a zone-independent sentinel for the far-past floor day.
    public static func utcDayKey(_ unix: Int) -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(unix)))
    }
}
