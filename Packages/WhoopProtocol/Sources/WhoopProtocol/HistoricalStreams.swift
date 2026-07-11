import Foundation
import Shared

/// Shared plausibility bounds for a type-47 record's own unix timestamp (#547). A WHOOP strap with a
/// bad clock/flash (repeated trim=0xFFFFFFFF no-cursor) emits records whose decoded unix is scattered
/// garbage — far-past (2024/2029), a bogus 2027=1827642881, and even FUTURE dates. NOOP used to trust
/// these verbatim, so one polluted ~12h block got re-attributed to every day-window and a future-dated
/// record surfaced as the "last night" carry-over. We now reject any record whose ts isn't near "now".
///
/// MIN_PLAUSIBLE_UNIX = 2023-11 — the same 1.7B floor `BLEManager.strapDataBounds` already uses.
/// FUTURE_MARGIN = 1 day — a historical record can never post-date its own capture, so anything more
/// than a day ahead of wall time is a bad-clock artefact.
/// Values of record (Phase 2b): the shared Kotlin `HistoricalStreams.kt` constants, read once here
/// so the two platforms' gates can never drift.
public let MIN_PLAUSIBLE_UNIX = Int(HistoricalStreamsKt.MIN_PLAUSIBLE_UNIX)   // 2023-11
public let FUTURE_MARGIN = Int(HistoricalStreamsKt.FUTURE_MARGIN)             // 1 day

/// SESSION-RELATIVE slack (#547): how far OUTSIDE the strap's own GET_DATA_RANGE oldest/newest markers a
/// record may still be stamped before it's treated as bad-clock pollution. The strap reports its banked
/// history span [oldest, newest] for THIS sync; a real record cannot predate the oldest banked marker nor
/// post-date the newest by more than benign skew, so a record dated MONTHS off the strap's OWN window is a
/// wandering-clock artefact even when it clears the absolute 2023-11 floor (e.g. a 2024-12-25 record against
/// a 2026 strap window). 7 days absorbs marker jitter / a still-banking newest edge / DST while still
/// catching the months-off garbage. Value of record: the shared Kotlin `SESSION_RANGE_MARGIN`.
public let SESSION_RANGE_MARGIN = Int(HistoricalStreamsKt.SESSION_RANGE_MARGIN)    // 7 days

/// True when `ts` is a plausible capture time for a historical record given `wallNow` (#547): on or
/// after the 2023-11 floor and no more than a day ahead of now. The single predicate the ingest gate
/// and the one-time DB heal both use, so both platforms reject the exact same set.
///
/// STAYS SWIFT (Phase 2b): the Kotlin twin of this predicate is a `plausible()` closure LOCAL to
/// `extractHistoricalStreams`: nothing public to route through. The bounds it applies are the
/// delegated shared constants above, so the two platforms' gates are pinned to the same numbers.
public func isPlausibleHistoricalUnix(_ ts: Int, wallNow: Int) -> Bool {
    ts >= MIN_PLAUSIBLE_UNIX && ts <= wallNow + FUTURE_MARGIN
}

/// SESSION-RELATIVE plausibility (#547): the absolute gate (`isPlausibleHistoricalUnix`) PLUS a check
/// that `ts` sits within the strap's OWN GET_DATA_RANGE markers for THIS sync, padded by
/// `SESSION_RANGE_MARGIN`. `sessionOldestUnix`/`sessionNewestUnix` are the markers the BLE client scanned
/// from the strap's range reply (nil when unknown — the replay/import/no-range paths). When BOTH markers
/// are present AND well-formed (both clear the absolute floor and oldest <= newest), a record dated far
/// before the oldest banked marker or far after the newest is rejected as wandering-clock pollution even
/// though it cleared the absolute floor. When the markers are absent or malformed this is byte-identical
/// to the absolute-only gate, so every legacy / range-less caller is unchanged. A legitimately-OLD record
/// that falls WITHIN [oldest, newest] (real history the strap actually banked) is always kept.
public func isPlausibleHistoricalUnix(_ ts: Int, wallNow: Int,
                                      sessionOldestUnix: Int?, sessionNewestUnix: Int?) -> Bool {
    guard isPlausibleHistoricalUnix(ts, wallNow: wallNow) else { return false }
    // Only apply the session-relative window when both markers are trustworthy: present, themselves above
    // the absolute floor, and correctly ordered. A wrong-epoch / partial marker must never reject real data.
    guard let oldest = sessionOldestUnix, let newest = sessionNewestUnix,
          oldest >= MIN_PLAUSIBLE_UNIX, newest >= oldest else { return true }
    return ts >= oldest - SESSION_RANGE_MARGIN && ts <= newest + SESSION_RANGE_MARGIN
}

/// The HISTORICAL_DATA record frames in `rawFrames` that FAIL decode — a genuine CRC failure, or an
/// unmapped firmware layout whose envelope parsed but yielded no usable biometrics. These are the
/// records the strap is about to free once we ack the trim, so without an archive they are lost
/// forever while the UI reports a clean sync (#77 / #91).
///
/// Console (type-50, `frame[typeIndex] == 0x32`) frames are strap-side debug-log text that decode to
/// zero rows BY DESIGN and are never returned. 5/MG v26 (raw PPG block, hist_version 26) is also
/// skipped: it is known-and-unstored by design, not lost biometric data. Only genuine type-47
/// record frames whose payload would otherwise be silently dropped are returned.
///
/// Used by the Backfiller/BLEManager to archive undecodable history BEFORE acking the trim.
///
/// DELEGATED (Phase 2b): the rejected set of record is the shared Kotlin
/// `rejectedHistoricalRecords(rawFrames:family:)`, whose predicate was aligned to this function's
/// (`unix == nil || (heart_rate == nil && gravity_x == nil)` after decode, CRC/unmapped rejects, the
/// type-47 gate and the v26-by-design skip) so one archive decision serves both platforms:
/// `RejectedHistoryTests` here and `RejectedHistoricalRecordsTest` on the Kotlin side are the parity
/// net. The family is handed over by implicit member (`.whoop4`/`.whoop5`) so no SKIE Swift type is
/// named (x86_64 iOS-simulator slice safe).
public func rejectedHistoricalRecords(_ rawFrames: [[UInt8]], family: DeviceFamily) -> [[UInt8]] {
    let frames = rawFrames.map { $0.toKotlinByteArray() }
    let rejected: [KotlinByteArray]
    switch family {
    case .whoop4: rejected = HistoricalStreamsKt.rejectedHistoricalRecords(rawFrames: frames, family: .whoop4)
    case .whoop5: rejected = HistoricalStreamsKt.rejectedHistoricalRecords(rawFrames: frames, family: .whoop5)
    }
    return rejected.map { $0.toUInt8Array() }
}

/// Turn historical (offload) parsed frames into datastore rows. Port of
/// interpreter.extract_historical_streams.
///
/// HR/R-R come from REALTIME_RAW_DATA (type 43) headers — the canonical stream
/// during a historical backfill, where type-40 frames are absent.
/// EVENT and COMMAND_RESPONSE handling is identical to extractStreams.
/// CRC-failed and non-ok frames are skipped.
///
/// STAYS SWIFT (Phase 2b, judged): the Kotlin twin `extractHistoricalStreams(rawFrames:...)` is a
/// DIFFERENT SHAPE end to end: it consumes raw BLE frames (decoding type-47 itself via
/// `decodeHistorical`, because the Kotlin live parser skips type-47) and returns a
/// `com.noop.data.StreamBatch` of Room-oriented rows whose event payloads are JSON-encoded strings.
/// This one consumes the Swift schema Interpreter's `ParsedFrame`s (the Interpreter/PostHooks
/// subsystem is deliberately not delegated) and returns the Swift `Streams` (typed
/// `[String: ParsedValue]` event payloads, golden-fixture Codable). Bridging would rebuild every
/// frame and row across the seam and flatten the typed payloads through JSON. The seams that CAN
/// share one implementation already do: the plausibility bounds (shared constants above), the
/// v26-PPG HR derivation (`PpgHr.derivePpgHr`, delegated) and the archive decision
/// (`rejectedHistoricalRecords`, delegated).
public func extractHistoricalStreams(_ parsed: [ParsedFrame],
                                     deviceClockRef: Int, wallClockRef: Int,
                                     // SESSION-RELATIVE bounds (#547): the strap's own GET_DATA_RANGE
                                     // oldest/newest markers for THIS sync. nil on the replay/import/no-range
                                     // paths — the gate then falls back to the absolute-only floor (unchanged).
                                     sessionOldestUnix: Int? = nil,
                                     sessionNewestUnix: Int? = nil) -> Streams {
    func wall(_ deviceTs: Int?) -> Int? {
        guard let d = deviceTs else { return nil }
        return wallClockRef + (d - deviceClockRef)
    }
    // FIX #72: type-47 `unix` and EVENT `event_timestamp` are the strap RTC's own real-unix seconds.
    // When the strap RTC is grossly stale (it sat unused for months, so its clock is months behind),
    // those land far in the past — live HR works but all offloaded history is misdated. Correct them by
    // the (wall - device) clock offset, but ONLY when the strap is grossly stale, and SNAPPED to a 5-min
    // grid so the same record re-syncs to the SAME corrected ts (offloaded rows dedupe by (deviceId, ts);
    // an un-snapped, slightly-different offset on re-sync would duplicate every row). For a normal or
    // identity clockRef the offset is ~0 (< threshold) → rawTs is returned unchanged (current behavior).
    let staleThreshold = 86_400          // 1 day
    let snapGranularity = 300            // 5 min
    let clockOffset = wallClockRef - deviceClockRef
    // The wall "now" the plausibility gate's FUTURE bound measures against. A record genuinely can't
    // post-date its own capture, so the ground truth for "future" is the LIVE wall clock. We take the
    // LATER of the live clock and `wallClockRef` so neither a synthetic/older ref (RawHistoryArchive's
    // identity `wallClockRef == 0`, unit fixtures, or a session ref a hair behind a just-captured record)
    // nor a paused live clock wrongly rejects a real record. The MIN_PLAUSIBLE_UNIX floor is unconditional
    // and still catches the far-past garbage in every caller. Genuine future garbage (pikapik's records
    // dated beyond now, the field's year-2081 overshoot) is still > now + FUTURE_MARGIN → dropped. (#547)
    let wallNow = max(wallClockRef, Int(Date().timeIntervalSince1970))
    // PRIMARY FIX (#547): a record's own decoded ts must be near "now". A bad-clock strap emits records
    // whose unix is scattered garbage (far-past, a bogus 2027, even future dates); trusted verbatim, one
    // polluted block was re-attributed to every day and a future row surfaced as "last night". Returns
    // nil for an out-of-bounds ts so EVERY call site can skip the record. Applied to BOTH the raw
    // pass-through branch and the corrected branch, so a clock-correction can never re-introduce a bad ts.
    func correctedWall(_ rawTs: Int) -> Int? {
        let candidate: Int
        if abs(clockOffset) <= staleThreshold {
            candidate = rawTs
        } else {
            // sign-aware round-half-up snap to the nearest `snapGranularity`
            let snapped = (clockOffset >= 0
                ? (clockOffset + snapGranularity / 2)
                : (clockOffset - snapGranularity / 2)) / snapGranularity * snapGranularity
            let corrected = rawTs + snapped
            // A fully-drained strap whose RTC has reset to ~epoch (year ~1971) reports a near-zero
            // deviceClockRef while its offloaded frames still carry the true-unix rawTs. clockOffset is
            // then ~decades, and this "correction" hurls every historical sample into the future
            // (observed in the field: year 2081), which silently breaks sleep & recovery because the
            // night never lands on the right day. A historical record can never post-date its own
            // capture, so when corrected overshoots wall time the offset was bogus — keep the raw ts.
            // The genuine stale case (strap behind real time) has corrected <= wallClockRef, so this
            // guard is a no-op there. (PR #471, @cataboysbusiness-debug)
            candidate = corrected <= wallClockRef + snapGranularity ? corrected : rawTs
        }
        // Final ingest gate (#547): drop the record if the resolved ts is implausible — either by the
        // absolute floor OR, when the strap's GET_DATA_RANGE markers are known, by sitting months outside
        // the strap's OWN banked window (wandering-clock pollution that clears the absolute floor). Counted
        // once per session via `droppedImplausible` so a bad-clock strap is visible in the diag/strap-log seam.
        guard isPlausibleHistoricalUnix(candidate, wallNow: wallNow,
                                        sessionOldestUnix: sessionOldestUnix,
                                        sessionNewestUnix: sessionNewestUnix) else {
            droppedImplausible += 1
            return nil
        }
        return candidate
    }
    // #547: how many records this chunk dropped for an implausible ts. Surfaced to the Backfiller via
    // `Streams.droppedImplausible` so the strap log can show a bad-clock strap (observability only).
    var droppedImplausible = 0
    var out = Streams()
    // v26 optical-PPG records (issue #156): no measured HR/motion, just the 24 Hz waveform. Collect
    // (corrected-wall ts, samples) here and derive a per-second HR after the loop (PpgHr.derivePpgHr),
    // so the timeline stays continuous through the v26-heavy stretches that have no v18 HR summary.
    var ppgRecords: [(ts: Int, samples: [Int])] = []
    for r in parsed {
        if !r.ok || r.crcOK == false { continue }
        let p = r.parsed
        switch r.typeName {
        case "HISTORICAL_DATA":
            // type-47 carries the strap RTC's real-unix seconds. Correct for a grossly-stale RTC
            // (FIX #72); a normal strap is unchanged (offset < threshold). The #547 ingest gate inside
            // `correctedWall` returns nil for an implausible ts (covers the v26 PPG baseTs too, since the
            // v26 waveform rides this same `unix`) — skip the whole record so no garbage-ts row is banked.
            guard let rawTs = p["unix"]?.intValue, let ts = correctedWall(rawTs) else { continue }
            // v26 PPG buffer: stash the waveform for the post-loop HR estimator. A v26 record carries
            // no heart_rate/spo2/gravity, so it adds nothing to the branches below — handled here only.
            if let samples = p["ppg_waveform"]?.intArrayValue, !samples.isEmpty {
                ppgRecords.append((ts: ts, samples: samples))
            }
            if let bpm = p["heart_rate"]?.intValue, bpm != 0 {  // skip startup hr=0
                out.hr.append(HRSample(ts: ts, bpm: bpm))
            }
            if let rrs = p["rr_intervals"]?.intArrayValue {
                for rr in rrs { out.rr.append(RRInterval(ts: ts, rrMs: rr)) }
            }
            if let red = p["spo2_red"]?.intValue {
                out.spo2.append(SpO2Sample(ts: ts, red: red, ir: p["spo2_ir"]?.intValue ?? 0))
            }
            if let raw = p["skin_temp_raw"]?.intValue {
                out.skinTemp.append(SkinTempSample(ts: ts, raw: raw))
            }
            // step_motion_counter@57 is the WHOOP5 cumulative u16 counter — decoded but, until now,
            // dropped on macOS (Android persists it). APPROXIMATE; semantics unverified vs the app (#78).
            if let c = p["step_motion_counter"]?.intValue {
                // activity_class@63 (0=still/1=walk/2=run) rides on the same record — nil when invalid/absent.
                out.steps.append(StepSample(ts: ts, counter: c, activityClass: p["activity_class"]?.intValue))
            }
            // Band sleep_state (#175): the strap's OWN @81 high-nibble state (0 wake/1 still/2 asleep/3 up),
            // decoded but DROPPED here until now, so the whole band-state chain (persist → the H7 re-onset
            // confirm guard → Deep Timeline track) had no source. Carried VERBATIM including 0 (a real wake
            // reading, not "absent"): only 5/MG v18 records emit the key, so a WHOOP 4.0 simply adds nothing.
            if let st = p["sleep_state"]?.intValue {
                out.sleepState.append(SleepStateSample(ts: ts, state: st))
            }
            if let raw = p["resp_rate_raw"]?.intValue {
                out.resp.append(RespSample(ts: ts, raw: raw))
            }
            if let gx = p["gravity_x"]?.doubleValue {
                out.gravity.append(GravitySample(ts: ts, x: gx,
                    y: p["gravity_y"]?.doubleValue ?? 0, z: p["gravity_z"]?.doubleValue ?? 0))
            }
        case "REALTIME_RAW_DATA":
            // #547 gate: the device-epoch→wall mapping can also land out of bounds on a bad clock, so
            // drop the row unless the resulting wall ts is plausible (mirrors the type-47/EVENT gate).
            var rtTs: Int?
            if let w = wall(p["timestamp"]?.intValue) {
                if isPlausibleHistoricalUnix(w, wallNow: wallNow,
                                             sessionOldestUnix: sessionOldestUnix,
                                             sessionNewestUnix: sessionNewestUnix) { rtTs = w }
                else { droppedImplausible += 1 }
            }
            if let ts = rtTs, let bpm = p["heart_rate"]?.intValue {
                out.hr.append(HRSample(ts: ts, bpm: bpm))
            }
            if let ts = rtTs, let rrs = p["rr_intervals"]?.intArrayValue {
                for rr in rrs { out.rr.append(RRInterval(ts: ts, rrMs: rr)) }
            }
        case "EVENT":
            // EVENT carries the strap RTC's real-unix seconds. Correct for a grossly-stale RTC
            // (FIX #72); a normal strap is unchanged (offset < threshold). #547 gate: skip the event
            // when `correctedWall` rejects an implausible ts so no future/far-past event is banked.
            guard let rawTs = p["event_timestamp"]?.intValue, let ts = correctedWall(rawTs) else { continue }
            let kind = p["event"]?.stringValue ?? ""
            if kind.hasPrefix("BATTERY_LEVEL") { appendBattery(&out, ts: ts, p: p) }  // "BATTERY_LEVEL(3)"
            var payload = p
            payload.removeValue(forKey: "event")
            payload.removeValue(forKey: "event_timestamp")
            out.events.append(WhoopEvent(ts: ts, kind: kind, payload: payload))
        case "COMMAND_RESPONSE":
            // No device timestamp on COMMAND_RESPONSE → stamp battery at wallClockRef.
            appendBattery(&out, ts: wallClockRef, p: p)
        default:
            continue
        }
    }
    // Derive per-second HR from the collected v26 PPG bursts (issue #156). Empty when there were no v26
    // records (the WHOOP 4 / v18-only common case), so this is a no-op cost there.
    out.ppgHr = PpgHr.derivePpgHr(records: ppgRecords)
    out.droppedImplausible = droppedImplausible   // #547 diag count (not persisted, not encoded)
    return out
}
