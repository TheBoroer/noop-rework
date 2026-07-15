import Foundation

// Pure, value-typed backfill heuristics extracted from BLEManager (T15c-2) so the shim can own the
// Backfiller/Collector without dragging CoreBluetooth along, and so the unit tests keep a
// CoreBluetooth-free seam (EmptySyncTrackerTests / Whoop5EmptyOffloadTrackerTests /
// BackfillContinuationTests / EmptyBankingClassifierTests).

/// Decides when a completed sync that handed over only the strap's console/diagnostic output (no sensor
/// records) is sustained enough to warn that the strap's clock has lost sync and it isn't banking to flash
/// (#77 / #91 / #120). A SINGLE empty cycle is common on a perfectly healthy strap — the strap can hand
/// back a console-only window, especially under heavy live-HR polling — so warning on one cycle
/// false-alarms users whose clock is fine (#126). We require CONSECUTIVE empty cycles; any cycle that banks
/// real sensor records clears the streak. Pure value type → unit-testable without a CoreBluetooth seam.
struct EmptySyncTracker {
    /// Consecutive console-only completed syncs before the clock-lost banner shows. 3 (not 1): a genuinely
    /// un-banking strap is console-only on EVERY cycle, so 3 is reached within minutes, while a transient
    /// empty cycle amid healthy ones never accumulates.
    let threshold: Int
    private(set) var consecutiveEmptySyncs = 0

    init(threshold: Int = 3) { self.threshold = threshold }

    /// Record a COMPLETED (HISTORY_COMPLETE) offload. `bankedSensorRecords` = the strap handed over real
    /// sensor records this cycle (decoded, or undecodable-but-archived — either way the clock is banking).
    /// `consoleOnly` = it handed over only diagnostic frames and no sensor records. Returns true only once
    /// emptiness is SUSTAINED (≥ threshold consecutive console-only cycles) — the caller shows the
    /// "clock has lost sync" banner only then. Any banking cycle, or a caught-up cycle with nothing to
    /// offload, clears the streak.
    mutating func recordCompletedSync(bankedSensorRecords: Bool, consoleOnly: Bool) -> Bool {
        guard consoleOnly, !bankedSensorRecords else {
            consecutiveEmptySyncs = 0
            return false
        }
        consecutiveEmptySyncs += 1
        return consecutiveEmptySyncs >= threshold
    }
}

/// #580: a connected WHOOP 5/MG whose firmware acks SEND_HISTORICAL_DATA but emits ZERO type-0x2F
/// offload frames. Live HR streams fine over the standard 0x2A37 profile, but the historical offload is
/// empty, so every session runs the 60s idle watchdog out to a "timeout" and surfaces the WHOOP-4
/// "strap went quiet" sync error — even though nothing is wrong, the 5/MG history offload is simply
/// experimental/unsupported on that firmware. Worse, the empty offload leaves the link idle, so the
/// 120s liveness watchdog can bounce-disconnect/rescan every ~2 min in a thrash loop.
///
/// This pure tracker counts CONSECUTIVE empty 5/MG offloads (a timeout with no offload frames and no
/// rows persisted). Once `quietThreshold` is reached it reports the strap as "history-empty" so the
/// caller can (a) surface an honest "connected, history sync experimental on 5.0" state instead of a
/// sync error, and (b) back off the bounce loop. Any offload that DOES hand over real records clears the
/// streak — so a strap that later starts banking recovers immediately. Value type → unit-testable
/// (Whoop5EmptyOffloadTrackerTests) without a CoreBluetooth seam. Mirrored on Android.
struct Whoop5EmptyOffloadTracker {
    /// Consecutive empty 5/MG offloads before we treat the strap as history-empty (experimental). 2 (not
    /// 1): the very first offload after connect can race the strap waking its flash, so one empty cycle is
    /// noise; two in a row is the firmware genuinely not serving history.
    let quietThreshold: Int

    private(set) var consecutiveEmpty = 0
    /// True once `quietThreshold` consecutive empty offloads have been seen — the link is up + live HR is
    /// flowing but the 5/MG history offload is empty. Drives the honest home-state flag AND the bounce
    /// backoff. Cleared the moment any offload banks real records.
    private(set) var historyEmpty = false

    init(quietThreshold: Int = 2) { self.quietThreshold = quietThreshold }

    /// Record a completed/timed-out 5/MG offload. `bankedRecords` = this offload routed real offload
    /// frames / persisted rows (the strap IS handing over history). Returns true if THIS call freshly
    /// crossed the threshold (so the caller can log/surface once). A banking offload resets everything.
    mutating func recordOffload(bankedRecords: Bool) -> Bool {
        guard !bankedRecords else {
            consecutiveEmpty = 0
            historyEmpty = false
            return false
        }
        consecutiveEmpty += 1
        if !historyEmpty && consecutiveEmpty >= quietThreshold {
            historyEmpty = true
            return true     // freshly crossed — caller logs/surfaces once
        }
        return false
    }

    /// Clear all suspicion — a fresh connect, or the user explicitly re-requested a sync. Lets a strap
    /// that starts banking (or a transient empty spell) recover without waiting out the streak.
    mutating func reset() {
        consecutiveEmpty = 0
        historyEmpty = false
    }
}

/// Decides whether a backfill session that ended on the 60s IDLE cap (not a true HISTORY_COMPLETE)
/// should immediately re-kick another offload instead of tearing down to wait the 15-min periodic floor
/// (#364). The real bug: the strap offloads OLDEST-first at ~60s/session, so on a deep backlog (e.g. the
/// app was off for days) each connection drains only ~one session's worth of the OLDEST data, then waits
/// 15 minutes — so "last night" can take many connections to reach even while the strap stays connected
/// the whole time. Auto-continuing closes that gap: keep re-offloading back-to-back while the strap is
/// still connected, there's demonstrably more backlog, and we're still making progress.
///
/// Pure + value-typed so the decision is unit-testable without a CoreBluetooth seam (Backfill
/// ContinuationTests). Mirrored byte-for-behaviour on Android (WhoopBleClient.shouldAutoContinue).
///
/// The four guards (ALL must hold):
///  1. `stillConnected` — connected + bonded; a dropped link goes through the normal reconnect path.
///  2. backlog remains — the strap's newest banked record (`strapNewestTs`, from GET_DATA_RANGE) is
///     still AHEAD of our persisted data frontier (`ourFrontierTs` = max persisted HR ts) by more than
///     `behindGapSeconds`. Comparing the frontier (not the trim u32, which climbs on empty ENDs even
///     when stuck) is what separates "more to fetch" from "caught up / off-wrist" — same idiom as
///     StuckStrapDetector. nil on either side ⇒ unknown ⇒ don't auto-continue (let the floor handle it).
///  3. `lastTrimAdvanced` — the just-ended session actually moved the strap's trim cursor. If the cursor
///     is frozen (strap handing back console-only / refusing to trim), re-kicking would spin forever
///     burning battery; stop and let the periodic floor retry slowly.
///  4. `consecutiveCount < maxAutoContinues` — a hard per-connection cap so a pathological strap can't
///     pin the radio. Once hit, fall back to the 15-min floor.
///
/// #1012: a FUTURE-dated `strapNewestTs` (more than `futureSkewSeconds` past the wall clock, #928) not
/// only nulls guard 2a — it also STOPS guard 2b. A future-clock strap banks future-dated records, so the
/// rows it hands over are future-timestamped too and "real rows persisted" is no evidence of genuine
/// backlog; 2b would chase the future-dated range through the whole cap (six back-to-back passes, each to
/// its idle timeout — the reported ~15-min sync). The stale/PAST-epoch case 2b actually exists for (#451)
/// reads BEHIND the frontier, never future-dated, so it is untouched.
struct BackfillContinuation {
    /// Hard cap on consecutive auto-continues per connection (resets on disconnect). 6 × ~60s ≈ 6 min of
    /// back-to-back draining — enough to chew through a multi-night backlog far faster than the 15-min
    /// floor, without letting a misbehaving strap monopolise Bluetooth.
    static let defaultMaxAutoContinues = 6
    /// How far ahead the strap must be (seconds) before "more backlog remains" is real, not clock noise.
    /// Matches StuckStrapDetector.behindGapSeconds (5 min) so the two agree on "behind".
    static let defaultBehindGapSeconds = 300
    /// #928: how far past the WALL CLOCK the strap-reported "newest" may sit before it is implausible.
    /// A strap clock set in the FUTURE (wandering RTC relatch) makes dataRangeNewestUnix read ahead of
    /// every real frontier, so guard 2a would report backlog forever and burn the whole cap in EMPTY
    /// offloads on every connect. 48 h absorbs genuine timezone confusion and mild drift; nothing
    /// legitimate banks records two days ahead of the phone's clock.
    static let defaultFutureSkewSeconds = 48 * 3600

    /// #1012: is the strap-reported "newest banked record" FUTURE-dated beyond the skew allowance — more
    /// than `futureSkewSeconds` past the wall clock? The strap's clock is then almost certainly set in the
    /// future (#928), so its range answer AND its freshly-persisted rows are untrustworthy as backlog
    /// evidence. Pure, and shared between `shouldAutoContinue` (it gates 2a AND 2b) and the call site's
    /// honest stop log so the two can never disagree on the reason. nil ⇒ false: an unanswered range is
    /// UNKNOWN, not future-dated, and 2b's stale-epoch rescue (#451) still applies to it.
    static func isFutureDatedNewest(_ strapNewestTs: Int?, wallNowUnix: Int,
                                    futureSkewSeconds: Int = defaultFutureSkewSeconds) -> Bool {
        guard let n = strapNewestTs else { return false }
        return n > wallNowUnix + futureSkewSeconds
    }

    /// `stillConnected`: link up + command channel usable. `strapNewestTs`: newest record the strap holds
    /// (GET_DATA_RANGE). `ourFrontierTs`: newest record WE'VE persisted (max HR ts). `wallNowUnix`: the
    /// REAL wall clock at decision time (#928 future-clock plausibility check; passed in so the predicate
    /// stays pure). `lastTrimAdvanced`: the just-ended session moved the trim cursor. `consecutiveCount`:
    /// auto-continues already done this connection. Returns true to immediately re-kick beginBackfill;
    /// false to tear down to the floor.
    static func shouldAutoContinue(stillConnected: Bool,
                                   strapNewestTs: Int?,
                                   ourFrontierTs: Int?,
                                   wallNowUnix: Int,
                                   rowsPersistedThisSession: Int = 0,
                                   lastTrimAdvanced: Bool,
                                   consecutiveCount: Int,
                                   maxAutoContinues: Int = defaultMaxAutoContinues,
                                   behindGapSeconds: Int = defaultBehindGapSeconds,
                                   futureSkewSeconds: Int = defaultFutureSkewSeconds) -> Bool {
        guard stillConnected else { return false }                 // 1
        guard consecutiveCount < maxAutoContinues else { return false }   // 4 (cap)
        guard lastTrimAdvanced else { return false }               // 3 (don't spin on a frozen cursor)
        // #928: a strap clock set in the FUTURE makes "newest" read ahead of ANY real frontier, so 2a
        // would report backlog forever and drive up to the full cap in EMPTY offloads on every connect.
        // A newest more than futureSkewSeconds past the wall clock is implausible: exclude it from 2a.
        let futureDated = isFutureDatedNewest(strapNewestTs, wallNowUnix: wallNowUnix,
                                              futureSkewSeconds: futureSkewSeconds)
        let plausibleNewest = futureDated ? nil : strapNewestTs
        // 2a: the strap reports newer data than we hold — reliable WHEN its clock epoch is sane.
        if let newest = plausibleNewest, let frontier = ourFrontierTs, (newest - frontier) > behindGapSeconds {
            return true
        }
        // #1012: a future-dated newest also gates 2b, not just 2a. A strap whose clock is set ahead
        // (#928) BANKED future-dated records, so the rows this session persisted are themselves
        // future-timestamped — "real rows" is NOT evidence of genuine backlog there, and 2b used to
        // chase the future-dated range through the whole cap (six back-to-back passes, each run to its
        // idle timeout: the reported ~15-min sync). Stop after this single pass; the periodic floor
        // keeps draining across connects, restoring the pre-#928 single-pass behaviour. The stale/
        // PAST-epoch case 2b exists for (#451) reads BEHIND the frontier, never future-dated, so it
        // falls through untouched below.
        if futureDated { return false }
        // 2b (#451): GET_DATA_RANGE's "newest" can read a STALE / wrong-epoch value — a strap that was
        // fully discharged (or carries a previous owner's history) banks records across multiple clock
        // epochs, and the data-range "newest" can latch an OLD one (e.g. 2024 when the real newest is 2026).
        // That false "we're already past it" would stop the drain after ONE session and make the user
        // tap the strap to re-trigger (exactly #364 / #451). But guard #3 already proved the trim advanced,
        // so if this session also PERSISTED REAL SENSOR ROWS the strap is demonstrably still handing over
        // real backlog — keep going. Empty / console-only ENDs persist 0 rows, so a genuinely stuck or
        // caught-up strap still won't spin, and the consecutive cap bounds it either way.
        return rowsPersistedThisSession > 0
    }
}

/// Namespace for the pure backfill gates/classifiers that used to be `static func`s on BLEManager.
enum BackfillHeuristics {
    /// Pure decision: should the periodic timer kick off another historical offload? Only when
    /// connected + bonded and NOT already mid-backfill. Extracted so the gate is unit-testable
    /// without a CoreBluetooth seam. Note this intentionally does NOT consult `backfillStarted`
    /// (that flag guards the once-per-connect INITIAL kick); the periodic re-trigger is separate.
    static func shouldRunPeriodicBackfill(connected: Bool, bonded: Bool, backfilling: Bool) -> Bool {
        connected && bonded && !backfilling
    }

    /// Pure classification of a COMPLETED (HISTORY_COMPLETE) offload, extracted from exitBackfilling so
    /// it's unit-testable without a CoreBluetooth seam (EmptyBankingClassifierTests).
    /// - `bankedSensorRecords`: the strap handed over real sensor records — decoded, or
    ///   undecodable-but-archived (either way its clock is banking to flash).
    /// - `bankedNothing` (#77/#120/#214): the offload completed but banked NO sensor records at all,
    ///   in EITHER shape — console-only across ≥3 diagnostic chunks, OR a near-empty metadata-only
    ///   completion (zero rows persisted) with fewer than 3 console frames. The #214 broadening is the
    ///   `rowsPersisted == 0` arm: before it, a metadata-only completion slipped through silently. The
    ///   sustained-streak gate (EmptySyncTracker) still decides whether the banner fires.
    static func classifyCompletedOffload(decodedChunks: Int, archivedFrames: Int,
                                          unarchivedFrames: Int, consoleChunks: Int,
                                          rowsPersisted: Int)
        -> (bankedSensorRecords: Bool, bankedNothing: Bool) {
        let bankedSensorRecords = decodedChunks > 0 || archivedFrames > 0 || unarchivedFrames > 0
        let bankedNothing = !bankedSensorRecords && (consoleChunks >= 3 || rowsPersisted == 0)
        return (bankedSensorRecords, bankedNothing)
    }
}
