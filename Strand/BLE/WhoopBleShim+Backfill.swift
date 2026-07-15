//
//  WhoopBleShim+Backfill.swift
//  Strand
//
//  T15c-2: the store-side offload engine, moved from BLEManager (§bootstrapStore, §backfill
//  helpers, §requestSync) onto the Kotlin-facade shim. Transport concerns changed
//  (CoreBluetooth writes → WhoopBleClient suspend calls); the session policy, gating, and
//  user-facing log strings deliberately did not. Every behavioural adaptation is commented
//  inline; anything uncommented is a verbatim port.
//

import Foundation
import Shared
import StrandAnalytics
// Scoped import: shadows the Kotlin `Shared.SedentaryDetector` for unqualified lookup, and the
// `StrandAnalytics` namespace enum makes module-qualified `StrandAnalytics.SedentaryDetector`
// unspellable (BLEManager never imported Shared, so it never hit this collision).
import enum StrandAnalytics.SedentaryDetector
import WhoopProtocol
import WhoopStore

extension WhoopBleShim {

    // MARK: - Constants (legacy BLEManager values; same UserDefaults keys for continuity)

    /// Same persisted key the legacy manager used, so the rate-limit window survives the
    /// BLEManager→shim migration (a fresh key would allow one extra immediate sync).
    static let backfillLastAtKey = "backfillLastAt"
    /// 15-min periodic offload cadence while connected (legacy `backfillIntervalSeconds`).
    static let backfillIntervalSeconds = 900
    /// Generous 60 s (not 20 s): the unstoppable ~2/s live type-43 raw flood eats BLE airtime,
    /// so genuine offload frames arrive in bursts with multi-second lulls between chunks — a
    /// short watchdog cut sessions short mid-drain. Longer = more records drained per session.
    static let backfillIdleTimeoutSeconds = 60
    /// Frames handed to the Backfiller per main-actor slice so the UI still paints mid-drain.
    static let backfillDrainBatchSize = 12
    /// Inactivity reminder (#419) looks back over the last 4 h of gravity windows.
    static let inactivityLookbackSeconds = 4 * 3600

    /// Kotlin `Shared.DeviceFamily` → store-layer `WhoopProtocol.DeviceFamily`. The store /
    /// backfill split only distinguishes 4 vs 5/MG. Defaults to `.whoop4` when not connected —
    /// harmless, as every consumer runs behind a `state.connected` gate.
    var storeFamily: WhoopProtocol.DeviceFamily {
        connectedFamily == .whoop5 ? .whoop5 : .whoop4
    }

    // MARK: - Logging (legacy `log()` prefixed HH:mm:ss; the strap-log export relies on it)

    private static let logTimeFormatter: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"; return f
    }()

    func log(_ s: String) {
        state.append(log: "[\(Self.logTimeFormatter.string(from: Date()))] \(s)")
    }

    // MARK: - Store bootstrap

    /// Connect-time hook: build the store pair on first use. `collector == nil` doubles as the
    /// "not bootstrapped yet" gate, and `bootstrapStore()` re-guards, so racing calls are safe.
    func bootstrapStoreIfNeeded() {
        guard collector == nil else { return }
        Task { @MainActor in await self.bootstrapStore() }
    }

    func bootstrapStore() async {
        guard collector == nil else { return }
        // Surface store-open failures instead of swallowing them with `try?` (#222): a silent failure
        // here left `backfiller` nil forever and the only visible symptom was the downstream
        // "store not ready" tick, with no clue why. On iOS a background reconnect that opens the
        // data-protected store while the device is locked throws SQLITE_IOERR/CANTOPEN — logging the
        // code proves it; the periodic tick (see beginBackfill) re-attempts so it self-heals on unlock.
        let path: String
        do {
            path = try StorePaths.defaultDatabasePath()
        } catch {
            log("Backfill: bootstrap FAILED resolving DB path — \(error)")
            return
        }
        let store: WhoopStore
        do {
            store = try await WhoopStore(path: path)
        } catch {
            let ns = error as NSError
            log("Backfill: bootstrap FAILED opening store — \(ns.domain) code=\(ns.code): \(ns.localizedDescription)")
            return
        }
        // Route deviceId through the device registry: use the active device's id (migration v15 seeds
        // a single 'my-whoop' row as active, so this is still "my-whoop" today — zero behaviour change).
        // Guarded + best-effort: if the registry is empty/unreadable, deviceId stays as it was, so no
        // crash and no behaviour change. DeviceRegistryStore routes through the WhoopStore actor,
        // which serializes the read.
        if let activeId = try? await DeviceRegistryStore(store: store).activeDeviceId(),
           !activeId.isEmpty {
            self.deviceId = activeId
        }
        try? await store.upsertDevice(id: deviceId, mac: nil, name: "WHOOP 4.0")
        // Research toggle — OFF by default. When disabled the app is decoded-only and never
        // persists raw frames. Flip "enableRawCapture" in UserDefaults to capture raw again.
        let enableRawCapture = UserDefaults.standard.bool(forKey: "enableRawCapture")
        collector = Collector(store: store, deviceId: deviceId,
                              enableRawCapture: enableRawCapture)
        // The store can finish bootstrapping AFTER the Kotlin connect already ran (both wait on
        // the link), so apply the family/clock configuration here too — whichever runs last wins.
        configureCollectorFamily()
        backfiller = Backfiller(store: store, deviceId: deviceId,
                                ackTrim: { [weak self] trim, endData in
                                    self?.ackHistoricalChunk(trim: trim, endData: endData)
                                },
                                enableRawCapture: enableRawCapture,
                                log: { [weak self] s in self?.log(s) },
                                rejectedSink: { [weak self] frames, trim, family in
                                    self?.archiveRejectedFrames(frames, trim: trim, family: family) ?? true
                                },
                                onChunk: { [weak self] decoded, console in
                                    if decoded { self?.state.decodedChunksThisSession += 1 }
                                    if console { self?.state.consoleChunksThisSession += 1 }
                                },
                                // Connection & Sync test mode (Test Centre): the cheap gate + tagged sink the
                                // Backfiller checks before building any .connection diagnostic line. The gate is
                                // one UserDefaults bool; nothing is emitted (or built) when the mode is off.
                                connectionActive: { TestCentre.active(.connection) },
                                connectionLog: { [weak self] s in self?.state.append(log: s, domain: .connection) },
                                // UNIVERSAL clock-drift: bank the strap's historical layout so the export's
                                // universal clock-drift line is firmware-aware on every export. Unconditional.
                                firmwareLayout: { [weak self] v in self?.state.setStrapFirmwareLayout(v) })
        // Strand: no server uploader/sync — all data stays on-device.

        // Retro-decode: when the decoder gains a historical layout (e.g. WHOOP 4.0 v25), re-run every
        // archived undecodable frame through it and insert whatever now decodes — the only path by
        // which already-acked banked history backfills after an update. Run ONCE per app version (no
        // manual decoder-version constant to forget to bump); idempotent if it re-runs (rows dedupe
        // by ts) and the archive is small, so the once-per-update cost is negligible. (#152)
        // Note: the archive carries no deviceId, so replayed rows attribute to the current strap.
        let replayKey = "rejectArchiveReplayedAppVersion"
        if UserDefaults.standard.string(forKey: replayKey) != AppChangelog.currentVersion {
            do {
                let rows = try await rejectedHistoryArchive.replay(into: store, deviceId: deviceId)
                if rows > 0 { log("Backfill: retro-decoded \(rows) record(s) from the reject archive after an update.") }
                // Advance the gate ONLY on success — a failed insert must retry next launch, because
                // the archive holds the only surviving copy of these records. (#152)
                UserDefaults.standard.set(AppChangelog.currentVersion, forKey: replayKey)
            } catch {
                log("Backfill: reject-archive retro-decode deferred (store insert failed) — will retry next launch.")
            }
        }

        // Battery "~X days left" seed (#7): `LiveState.batterySamples` is fed ONLY by live BLE events, so
        // after a reconnect the runtime estimate restarted from an empty buffer and ignored the discharge
        // history already on disk (Android seeds from its persisted battery table over a 14-day window;
        // iOS/macOS did not = divergence). Read the persisted SoC series for the active device over the last
        // 14 days and seed the live buffer once the store is up. The setter de-dupes against any points
        // already banked from live events this session, so a seed that races the first live reading is safe.
        // nil-SoC rows are dropped here (the buffer is non-optional %); a read failure is non-fatal, the
        // estimate just cold-starts as before.
        let seedNow = Int(Date().timeIntervalSince1970)
        let fourteenDays = 14 * 24 * 3600
        if let rows = try? await store.batterySamples(
            deviceId: deviceId, from: seedNow - fourteenDays, to: seedNow, limit: 2000) {
            let seed = rows.compactMap { row -> (ts: Int, soc: Double)? in
                guard let soc = row.soc else { return nil }
                return (ts: row.ts, soc: soc)
            }
            state.seedBatterySamples(seed)
        }
    }

    func configureCollectorFamily() {
        collector?.family = storeFamily
        if storeFamily == .whoop5 {
            let now = Int(Date().timeIntervalSince1970)
            collector?.clockRef = ClockRef(device: now, wall: now)
        } else {
            // WHOOP 4: the legacy manager fed the GET_CLOCK correlation captured during its own
            // handshake. Kotlin owns the handshake now and doesn't surface the correlation yet,
            // so the ref stays nil and the Backfiller's identity-ref fallback decodes (flagging
            // `sessionUsedIdentityRef` in the clock diag line). T15e follow-up: surface
            // GET_CLOCK from the Kotlin facade and restore the true correlation.
            collector?.clockRef = nil
        }
    }

    /// WHOOP↔WHOOP switch: re-point new persistence at the newly-active registry row.
    public func setActiveDeviceId(_ id: String) {
        guard !id.isEmpty else { return }
        deviceId = id
        collector?.deviceId = id
        backfiller?.deviceId = id
    }

    /// Apply the raw-outbox retention policy (24h synced window / 50MB unsynced cap).
    /// Called when the app enters the background; no-op without a concrete store.
    public func pruneRaw() {
        Task { @MainActor in await collector?.prune() }
    }

    /// Light storage summary for the UI (decoded rows, raw batches, raw bytes). nil without a store.
    public func storageStats() async -> (decodedRows: Int, rawBatches: Int, rawBytes: Int)? {
        await collector?.storageStats()
    }

    // MARK: - Sync entry points

    /// The single gated entry point for every historical-offload kick. Applies the connection/state
    /// gate AND the BackfillPolicy rate-limiter for the trigger. On a go: records the attempt time
    /// (persisted) and starts the offload.
    func requestSync(_ trigger: BackfillTrigger) {
        guard BackfillHeuristics.shouldRunPeriodicBackfill(
            connected: state.connected, bonded: state.bonded, backfilling: backfilling) else { return }
        let now = Date().timeIntervalSince1970
        let last = UserDefaults.standard.object(forKey: Self.backfillLastAtKey) as? Double
        guard BackfillPolicy.shouldRun(trigger: trigger, now: now, lastBackfillAt: last,
                                       emptyStreak: emptySyncTracker.consecutiveEmptySyncs) else {
            log("Backfill: \(trigger) skipped (rate-limited; last \(last.map { Int(now - $0) } ?? -1)s ago)")
            return
        }
        if beginBackfill() {
            UserDefaults.standard.set(now, forKey: Self.backfillLastAtKey)
        }
    }

    /// User-tappable "Sync now" (#364): kick a historical offload IMMEDIATELY, bypassing the 15-min
    /// periodic floor. Routes through the SAME gated `requestSync` as every other trigger with the
    /// `.manual` tier — so it always passes the BackfillPolicy floor but still honours the
    /// connected+bonded+not-already-backfilling gate (a no-op, honestly, when there's no strap or a
    /// sync is already running). The caller (Health screen) only enables the control while connected, so
    /// a tap is meaningful; this guard is the belt-and-braces. Mirrors the Android `WhoopBleClient.syncNow`.
    public func syncNow() {
        guard state.connected, state.bonded else {
            log("Sync now: no strap connected — ignored.")
            return
        }
        if backfilling {
            log("Sync now: a sync is already in progress.")
            return
        }
        log("Sync now: manual sync requested by user.")
        requestSync(.manual)
    }

    /// Start (or restart) the periodic backfill timer. Each tick re-runs the type-47 historical
    /// offload while connected+bonded and not already backfilling — the primary metric sync.
    func armBackfillTimer() {
        backfillTimer?.cancel()
        let interval = Self.backfillIntervalSeconds
        let t = DispatchSource.makeTimerSource(queue: .main)
        t.schedule(deadline: .now() + .seconds(interval), repeating: .seconds(interval))
        // The handler fires on the main queue; hop through a MainActor Task so the actor-isolated
        // `requestSync` is reachable without an isolation escape hatch.
        t.setEventHandler { Task { @MainActor [weak self] in self?.requestSync(.periodic) } }
        t.resume()
        backfillTimer = t
    }

    /// Disconnect/close reset (legacy `didDisconnectPeripheral` bookkeeping): a disconnect
    /// mid-sync is NOT a sync failure — the next connect re-offloads — so flags drop without
    /// touching `lastSyncError`. The auto-continue budget is per-connection, so it resets here.
    func teardownBackfill(reason: String) {
        backfillTimer?.cancel()
        backfillTimer = nil
        backfillTimeout?.cancel()
        backfillTimeout = nil
        backfillFrameQueue.removeAll()
        consecutiveAutoContinues = 0
        if backfilling {
            backfilling = false
            state.backfilling = false
            log("Backfill: session ended — reason=\(reason)")
        }
    }

    // MARK: - Backfill session

    /// Start a historical-offload session: tell the store machine to begin, flip the routing
    /// flag, kick the strap, and arm the idle timeout.
    @discardableResult
    private func beginBackfill() -> Bool {
        // Never offload before the connect handshake has run: a racing foreground/restore trigger
        // firing SEND_HISTORICAL ahead of hello/SET_CLOCK was part of the storm that stopped serving.
        // ADAPTATION: Kotlin's connect() only returns (and only then flips `state.connected`) after
        // its hello/SET_CLOCK handshake, so `state.connected` IS the legacy `connectHandshakeDone`.
        guard state.connected else {
            log("Backfill: deferred — connect handshake not done yet")
            return false
        }
        guard let backfiller else {
            // Store not built yet (bootstrapStore failed or hasn't run). Do NOT force live HR — the
            // type-47 backfill is the metric source. RE-ATTEMPT the bootstrap here so a transient
            // first-open failure self-heals: on iOS the data-protected store is unreadable while the
            // phone is locked, so a background-reconnect bootstrap can fail and, with no retry, stay
            // dead forever (#222). Each periodic tick now retries; the first one that runs after the
            // device is unlocked rebuilds the store and backfill proceeds. bootstrapStore() guards on
            // `collector == nil`, so this is a no-op once the store is up.
            log("Backfill: store not ready — re-attempting bootstrap, will retry next tick")
            Task { @MainActor in await self.bootstrapStore() }
            return false
        }
        // Capture the family at begin() (not init): the connected family is reliably set before any
        // backfill starts, whereas bootstrapStore() can build the Backfiller before it is known.
        // #42/#364: consecutiveAutoContinues > 0 means this offload is re-kicked after an EARLIER session in
        // the same burst banked rows — tell the backfiller so its no-cursor END reads as "caught up", not
        // "no banked history / charge to 100%". A fresh offload (count 0) keeps the honest guidance.
        backfiller.begin(family: storeFamily, continuedAfterRows: consecutiveAutoContinues > 0)
        backfilling = true
        state.backfilling = true
        state.syncChunksThisSession = 0
        state.rejectedFramesThisSession = 0
        state.rejectedFramesUnarchived = 0
        state.decodedChunksThisSession = 0
        state.consoleChunksThisSession = 0
        state.r22FlagsAccepted = 0
        state.deepPacketsThisSession = 0
        // ADAPTATION: no `historicalAckLogCounter` reset — that was a puffin-write log throttle on
        // the legacy CoreBluetooth write path, which no longer exists.
        // Kick the strap. Kotlin plans the SEND_HISTORICAL payload — [0x00], NOT empty, verified
        // on-device (empty → 0 frames on a clean stable link with ~2k records pending); the strap
        // then streams HISTORY_START → type-47 records → HISTORY_END (acked) … → HISTORY_COMPLETE.
        // Fire-and-forget like the legacy write queue; the idle watchdog below covers a lost kick.
        Task { [client] in try? await client.sendHistoricalKick() }
        armBackfillTimeout()
        log("Backfill: session started — historical offload requested")
        return true
    }

    /// Feed a frame to the Backfiller preserving exact arrival order. Frames are appended
    /// synchronously (pump order) and drained sequentially in small slices, so START /
    /// data / END chunk assembly is never reordered while the UI still gets time to paint.
    /// ADAPTATION: the legacy delegate armed the idle watchdog at the notify callback before
    /// routing; the shim's frame pump has no other backfill hook, so the arm lives here. Only
    /// genuine offload frames re-arm it — the live type-43 flood must not (see isOffloadFrame).
    func routeBackfillFrame(_ frame: [UInt8]) {
        guard backfilling else { return }   // pump pre-gates too; belt-and-braces vs teardown races
        if Self.isOffloadFrame(frame, family: storeFamily) { armBackfillTimeout() }
        backfillFrameQueue.append(frame)
        guard !backfillDraining else { return }
        backfillDraining = true
        Task { @MainActor in await drainBackfillFrames() }
    }

    private func drainBackfillFrames() async {
        while !backfillFrameQueue.isEmpty {
            let count = min(Self.backfillDrainBatchSize, backfillFrameQueue.count)
            let batch = Array(backfillFrameQueue.prefix(count))
            backfillFrameQueue.removeFirst(count)

            for f in batch {
                await backfiller?.ingest(f)
                afterBackfillIngest()
                if !backfilling {
                    backfillFrameQueue.removeAll(keepingCapacity: true)
                    break
                }
            }

            if !backfillFrameQueue.isEmpty {
                await Task.yield()
            }
        }
        backfillDraining = false
    }

    /// Called after every Backfiller.ingest completes. If the Backfiller has consumed all
    /// historical data (isBackfilling drops to false), exit the backfill session cleanly.
    private func afterBackfillIngest() {
        guard backfilling, backfiller?.isBackfilling == false else { return }
        exitBackfilling(reason: "HISTORY_COMPLETE")
    }

    /// True when a frame is part of the historical offload (HISTORICAL_DATA=47, EVENT=48,
    /// METADATA=49 / puffin METADATA=56, CONSOLE_LOGS=50) rather than the live stream (REALTIME_DATA=40,
    /// REALTIME_RAW_DATA=43). The live type-43 raw flood streams continuously and unprompted on
    /// this firmware, so the backfill idle-watchdog must NOT be re-armed by it — only by genuine
    /// offload progress — otherwise the session can neither complete nor time out.
    /// `nonisolated`: pure byte inspection, callable from tests without the actor hop.
    nonisolated static func isOffloadFrame(_ frame: [UInt8], family: WhoopProtocol.DeviceFamily) -> Bool {
        // The type byte sits at the inner-record start: frame[4] on WHOOP 4.0, frame[8] on WHOOP 5/MG
        // (the puffin envelope is 4 bytes longer). Reading frame[4] for a puffin frame misclassifies
        // EVERY offload frame as live-flood and routes nothing to the Backfiller.
        let typeIndex = family == .whoop5 ? 8 : 4
        guard frame.count > typeIndex else { return false }
        switch frame[typeIndex] {
        case 47, 48, 49, 50, 56: return true   // HISTORICAL_DATA / EVENT / METADATA / CONSOLE_LOGS
        default: return false              // 40 REALTIME_DATA, 43 REALTIME_RAW_DATA (live flood)
        }
    }

    /// Re-arm the idle watchdog. Called on every offload frame during backfill so the timer resets
    /// as long as the strap keeps sending HISTORY; if the strap goes silent the timer fires and we
    /// exit the session (the durable strap_trim cursor means the next session resumes where we left off).
    private func armBackfillTimeout() {
        backfillTimeout?.cancel()
        let item = DispatchWorkItem {
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.backfiller?.timeoutFired()
                self.exitBackfilling(reason: "timeout")
            }
        }
        backfillTimeout = item
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(Self.backfillIdleTimeoutSeconds), execute: item)
    }

    /// Tear down the backfill session. Does NOT auto-start live HR: the periodic type-47 backfill
    /// is the primary metric source now, mirroring how WHOOP syncs. Live HR is opt-in only (the
    /// manual "Start HR" button in LiveView). Between backfills the Collector sees only the live
    /// type-43 flood, which extractStreams ignores — the data comes from the next periodic offload.
    private func exitBackfilling(reason: String) {
        guard backfilling else { return }
        backfilling = false
        state.backfilling = false
        // ADAPTATION: the legacy #174 `lastOffloadFrameAt` deep-packet cooldown is gone — its
        // consumer was the legacy frame classifier, and the Kotlin FrameRouter owns the
        // live/deep-packet split now, so there is no cooldown to extend here.
        backfillTimeout?.cancel()
        backfillTimeout = nil
        backfillFrameQueue.removeAll()
        log("Backfill: session ended — reason=\(reason)")
        // Inactivity reminder (#419): read-only hook on the natural offload completion (no cadence
        // change). Only on a true HISTORY_COMPLETE — a timeout/disconnect didn't bring a fresh window.
        if reason == "HISTORY_COMPLETE" { maybeBuzzInactivity() }
        // Success-side summary (#150 forensics): we logged failures (decoded-to-0) but never successes,
        // so a strap log couldn't tell a banking strap from a broken one. Emit the per-session persistence
        // tally whenever anything actually landed — the win-rate signal a log previously lacked.
        if let bf = backfiller,
           let summary = Backfiller.sessionSummaryLine(rows: bf.sessionRowsPersisted, motion: bf.sessionMotionRows, skinTemp: bf.sessionSkinTempRows, nights: bf.sessionNights) {
            log(summary)
            // #67: WHERE the rows landed + WHY (the clock ref that decoded them). A reset-RTC strap banks
            // last night into the past; this line makes the misdating self-evident in the strap log instead
            // of leaving "persisted N rows across 1 night(s)" looking like a clean sync.
            if let diag = Backfiller.sessionClockDiagLine(nightKeys: bf.sessionNightKeys,
                                                          device: bf.sessionClockDevice,
                                                          wall: bf.sessionClockWall,
                                                          usedIdentityRef: bf.sessionUsedIdentityRef) {
                log(diag)
            }
        }
        // Connection test mode: the offload OUTCOME the readout's lastOffloadResult id binds. Gated
        // zero-cost (the .connection bool is read before any string is built). Diagnostic only - it reads
        // the same per-session tallies the existing summary above does, changing no offload behaviour. A
        // timeout/idle-cap exit is a STALL; a HISTORY_COMPLETE with rows is a clean complete; with none it
        // is an empty (console-only) cycle.
        if TestCentre.active(.connection), let bf = backfiller {
            let rows = bf.sessionRowsPersisted
            let result: String
            if reason == "timeout" {
                result = "stalled (idle timeout, rows=\(rows) so far)"
            } else if reason == "HISTORY_COMPLETE" {
                result = rows > 0
                    ? "complete rows=\(rows) nights=\(bf.sessionNights)"
                    : "empty (console only, no sensor records)"
            } else {
                result = "\(reason) rows=\(rows)"
            }
            state.append(log: "offload result=\(result)", domain: .connection)
        }
        // #547 RE-POLLUTION: this session's ingest gate dropped bad-clock records, so the strap has a
        // wandering clock and may have banked similar garbage on an OLDER build whose gate was weaker. Arm
        // a heal re-run so the next analyze tick purges any such pollution — not gated behind the one-shot
        // done flag. Pure UserDefaults set (no engine handle here); IntelligenceEngine honours it next tick.
        if (backfiller?.sessionDroppedImplausible ?? 0) > 0 {
            IntelligenceEngine.requestTimestampReheal()
        }
        // #364 auto-continue spin-detector: did THIS session move the strap's trim cursor? Compare the
        // Backfiller's current high-water trim against where it stood when the previous session ended.
        // A frozen cursor (console-only / strap refusing to trim) ⇒ don't re-kick (it would spin forever).
        let currentTrim = backfiller?.lastAckedTrim
        let trimAdvanced = currentTrim != nil && currentTrim != lastSessionEndTrim
        lastSessionEndTrim = currentTrim
        // Honest sync outcome for a cloud-free user (mirrors Android exitBackfilling, ed6a31d):
        // HISTORY_COMPLETE stamps lastSyncedAt + clears any error; the idle-watchdog timeout surfaces
        // a non-silent error. A disconnect mid-sync bypasses this path (teardownBackfill resets
        // the flags directly) — that's not a sync failure, and the next connect re-offloads.
        if reason == "HISTORY_COMPLETE" {
            state.lastSyncedAt = Date().timeIntervalSince1970
            // #77 / #91: a sync that COMPLETED but discarded records must not read as a clean
            // "History synced" — the wording distinguishes bytes saved on this Mac from bytes the
            // full archive could not preserve, so "saved" is never claimed falsely.
            let archived = state.rejectedFramesThisSession
            let unarchived = state.rejectedFramesUnarchived
            // Classify this completed offload (pure, unit-tested in EmptyBankingClassifierTests).
            let banking = BackfillHeuristics.classifyCompletedOffload(
                decodedChunks: state.decodedChunksThisSession,
                archivedFrames: archived,
                unarchivedFrames: unarchived,
                consoleChunks: state.consoleChunksThisSession,
                rowsPersisted: backfiller?.sessionRowsPersisted ?? 0)
            let bankedSensorRecords = banking.bankedSensorRecords
            // #42: the empty tail of an auto-continue burst (consecutiveAutoContinues > 0) isn't a "banked
            // nothing" sync — an EARLIER session in the same burst handed over real rows and this pass just
            // confirms we're caught up. Don't surface the "charge to 100%" framing, and don't count it toward
            // the sustained-empty streak (the productive session already cleared it).
            let productiveBurstTail = consecutiveAutoContinues > 0
            let bankedNothing = banking.bankedNothing && !productiveBurstTail
            let sustainedEmpty = productiveBurstTail ? false : emptySyncTracker.recordCompletedSync(
                bankedSensorRecords: bankedSensorRecords, consoleOnly: banking.bankedNothing)
            // #57 debug: write-health for the export. Distinguish "rows actually landed" from "an offload
            // STALLED on a persist failure" — the latter (usually a restore without a restart) is otherwise
            // invisible in a report that just shows "0 synced".
            let du = UserDefaults.standard
            if (backfiller?.sessionRowsPersisted ?? 0) > 0 { du.set(Date().timeIntervalSince1970, forKey: "sync.lastWriteOkAt") }
            if backfiller?.persistStalled == true { du.set(Date().timeIntervalSince1970, forKey: "sync.lastWriteStalledAt") }
            if unarchived > 0 {
                state.lastSyncError = "Synced, but \(archived + unarchived) record(s) couldn't be decoded (unrecognised strap firmware layout), and the on-device archive is full - the \(unarchived) newest weren't preserved. Please share a strap log so the layout can be mapped."
            } else if archived > 0 {
                state.lastSyncError = "Synced, but \(archived) record(s) couldn't be decoded (unrecognised strap firmware layout). The raw bytes were saved on this Mac - please share a strap log so the layout can be mapped."
            } else if bankedNothing {
                // #77 / #214 family: the offload COMPLETED but the strap handed over no sensor records
                // at all — either console/diagnostic output across many chunks, OR a near-empty
                // metadata-only completion (zero rows persisted) — i.e. it isn't banking history to
                // flash (its RTC has lost sync). Only escalate to the actionable banner once emptiness
                // is SUSTAINED (#126): a single empty cycle on an otherwise-banking strap stays silent.
                let detail = state.consoleChunksThisSession >= 3
                    ? "console-only across \(state.consoleChunksThisSession) chunks"
                    : "metadata-only, 0 sensor rows persisted"
                log("Backfill: completed but the strap banked no sensor history (\(detail)); consecutive empty syncs = \(emptySyncTracker.consecutiveEmptySyncs).")
                state.lastSyncError = sustainedEmpty
                    ? "Synced, but your strap had no stored history to hand over - only its diagnostic output. This usually means its clock has lost sync, so it isn't saving data to flash. Fully charge it to 100%, then reconnect, and it should start banking again."
                    : nil
            } else {
                state.lastSyncError = nil
            }
            // #580: a 5/MG that reaches a real HISTORY_COMPLETE with banked sensor records proves its
            // history offload IS working — clear the experimental note + empty streak so the home state
            // stops saying "history sync experimental".
            if storeFamily == .whoop5, bankedSensorRecords {
                whoop5EmptyOffload.reset()
                state.historySyncExperimental = false
            }
            UserDefaults.standard.set(state.lastSyncedAt, forKey: "lastSyncedAt")
            // NOTE: the auto-continue streak is NOT reset here. A HISTORY_COMPLETE is no longer assumed to
            // mean "caught up" (#25): a strap whose firmware segments a deep offload into many small
            // HISTORY_COMPLETE slices would otherwise reset the streak on every slice and never engage the
            // 6-per-connection cap. The streak is cleared only once shouldAutoContinue proves we're actually
            // caught up — inside maybeAutoContinueBackfill's else path, fired below for BOTH exit reasons.
        } else if reason == "timeout" {
            // #580: distinguish a genuine WHOOP-4 "strap went quiet mid-sync" from a WHOOP 5/MG whose
            // firmware acks SEND_HISTORICAL_DATA but never emits a single type-0x2F offload frame. The
            // 5/MG case isn't a failure — live HR is streaming fine over 0x2A37, the history offload is
            // just experimental/empty on that firmware. "Banked" = this offload made ANY offload progress
            // (chunks acked, rows persisted, or deep packets seen); an empty 5/MG offload has none.
            let bankedThisOffload = state.syncChunksThisSession > 0
                || (backfiller?.sessionRowsPersisted ?? 0) > 0
                || state.deepPacketsThisSession > 0
            if storeFamily == .whoop5 {
                let crossed = whoop5EmptyOffload.recordOffload(bankedRecords: bankedThisOffload)
                if whoop5EmptyOffload.historyEmpty {
                    // Honest home state (#580): NOT a sync error — connected + live HR, history experimental.
                    state.historySyncExperimental = true
                    state.lastSyncError = nil
                    if crossed {
                        log("Backfill: WHOOP 5/MG offload empty \(whoop5EmptyOffload.consecutiveEmpty)× — history sync is experimental on 5.0; surfacing 'connected, history experimental' (not a sync error) and backing off the bounce loop.")
                    }
                } else {
                    // Either the first empty cycle (could be the strap waking flash — stay quiet, don't
                    // cry failure) OR a banking offload that just cleared the streak (recovery — drop the
                    // experimental note). Both want a clean, error-free state.
                    state.historySyncExperimental = false
                    state.lastSyncError = nil
                }
            } else {
                state.lastSyncError = "Sync interrupted - the strap went quiet. It will retry on the next sync."
            }
        }
        // ADAPTATION: no `checkStrapLiveness()` safety-net here. The stuck detector needs the
        // strap-reported newest banked ts (GET_DATA_RANGE), which the Kotlin facade doesn't
        // surface yet — with that signal permanently nil the detector can never fire, so the call
        // would be dead code. T15e follow-up: surface GET_DATA_RANGE and restore it.
        // #364 / #25: a session that ended on the 60s IDLE cap OR on a true HISTORY_COMPLETE while still
        // connected, with more backlog to fetch and the trim still advancing, immediately re-kicks another
        // offload instead of tearing down to wait the 15-min floor — so a deep oldest-first backlog drains
        // in back-to-back ~60s passes rather than one-per-15-min. #25: fire on HISTORY_COMPLETE too — some
        // straps segment a deep overnight offload into many small HISTORY_COMPLETE slices and would
        // otherwise stall between slices until the periodic floor. The pure shouldAutoContinue guards make
        // this safe: a genuinely caught-up strap (newest within behindGapSeconds of the frontier and 0 rows)
        // returns false and stops; that else path is also where the consecutive streak is cleared. Bounded
        // by the consecutive-cap and the spin-detector inside the pure predicate either way.
        if reason == "timeout" || reason == "HISTORY_COMPLETE" {
            maybeAutoContinueBackfill(trimAdvanced: trimAdvanced,
                                      rowsPersisted: backfiller?.sessionRowsPersisted ?? 0)
        }
    }

    /// #364 / #25: evaluate (and, if warranted, fire) an immediate back-to-back backfill after a 60s
    /// idle-cap exit OR a HISTORY_COMPLETE. The decision itself is the pure
    /// `BackfillContinuation.shouldAutoContinue` so it stays unit-testable; this method only gathers the
    /// inputs, bumps the counter (or clears it on the caught-up else path — see #25), and re-kicks via the
    /// SAME gated requestSync path (so it still respects connected/bonded and can't double-start). The
    /// `.autoContinue` trigger ⇒ the BackfillPolicy 15-min floor is bypassed for this expedited
    /// continuation (the cap is the guard). `trimAdvanced` is the spin-detector signal computed in
    /// exitBackfilling — passed in because exitBackfilling has already advanced `lastSessionEndTrim`
    /// past the comparison point by the time this Task runs.
    private func maybeAutoContinueBackfill(trimAdvanced: Bool, rowsPersisted: Int) {
        // Cheap pre-checks first (no Task if we already know we won't continue): still connected, under
        // the cap, and the trim moved. The frontier read only happens when those already hold.
        guard state.connected, state.bonded else { return }
        // ADAPTATION: the legacy `strapNewestTs` came from GET_DATA_RANGE, which the Kotlin facade
        // doesn't surface yet — permanently nil here, exactly like legacy sessions before that
        // command landed. `shouldAutoContinue` treats a nil newest as "unknown" (continue on the
        // trim/rows signals alone) and `isFutureDatedNewest(nil, …)` is false, so both consumers
        // degrade gracefully. T15e follow-up alongside the liveness watchdog.
        let newest: Int? = nil
        let count = consecutiveAutoContinues
        Task { @MainActor in
            let frontier = await collector?.latestHRSampleTs() ?? nil
            let wallNow = Int(Date().timeIntervalSince1970)   // #928: real wall clock, at decision time
            let stillConnected = state.connected && state.bonded
            guard BackfillContinuation.shouldAutoContinue(
                stillConnected: stillConnected,
                strapNewestTs: newest,
                ourFrontierTs: frontier,
                wallNowUnix: wallNow,
                rowsPersistedThisSession: rowsPersisted,
                lastTrimAdvanced: trimAdvanced,
                consecutiveCount: count) else {
                // #1012: name the stop honestly when the future-clock gate is what ended the chain —
                // without this line the log just goes quiet after one pass and a strap-log export can't
                // tell "caught up" from "future-dated range refused". Fires ONLY when 2b would otherwise
                // have continued (still connected, rows banked, trim advanced, under the cap), so a
                // frozen-trim / cap / disconnect stop is never misattributed to the clock.
                if stillConnected, rowsPersisted > 0, trimAdvanced,
                   count < BackfillContinuation.defaultMaxAutoContinues,
                   BackfillContinuation.isFutureDatedNewest(newest, wallNowUnix: wallNow) {
                    let aheadH = ((newest ?? wallNow) - wallNow) / 3600
                    log("Backfill: not auto-continuing (#1012) - the strap-reported newest banked record reads \(aheadH)h AHEAD of the wall clock, so the range is future-dated and the strap clock is likely wrong (#928). Stopping after one pass instead of chasing future-dated ranges; the periodic sync keeps draining across connects.")
                }
                // No re-kick. THIS is the real "we're done draining" signal (#25): clear the auto-continue
                // streak so the NEXT deep backlog (e.g. after the app's been off again) gets a fresh budget
                // of re-kicks. Reset here — NOT unconditionally on every HISTORY_COMPLETE — so a strap that
                // slices one offload into many completions can't keep resetting the cap and spin forever.
                // EXCEPTION: if we stopped because the per-connection CAP is hit, leave the streak at/over
                // the cap so it STAYS engaged for the rest of this connection (the 15-min floor takes over);
                // zeroing it here would immediately re-arm the cap and let a runaway strap spin again.
                if count < BackfillContinuation.defaultMaxAutoContinues {
                    consecutiveAutoContinues = 0
                }
                return
            }
            // Guard against a race: a real backfill may already have re-started (periodic/connect) in the
            // gap before this Task ran. requestSync's own gate (!backfilling) handles that, but skip the
            // log/counter churn if so.
            guard !backfilling else { return }
            consecutiveAutoContinues += 1
            log("Backfill: auto-continuing (#364/#451) — the trim advanced and the strap is still handing over real records (frontier \(frontier.map(String.init) ?? "?"), strap-reported newest \(newest.map(String.init) ?? "?")); re-kicking offload \(consecutiveAutoContinues)/\(BackfillContinuation.defaultMaxAutoContinues) without waiting the 15-min floor.")
            // .autoContinue bypasses the BackfillPolicy floor (the whole point — don't wait 15 min);
            // requestSync still re-checks connected/bonded/not-backfilling before kicking, and the
            // consecutive-cap above is the runaway guard.
            requestSync(.autoContinue)
        }
    }

    // MARK: - Chunk acking + reject archive

    /// ADAPTATION: Kotlin plans the HISTORICAL_DATA_RESULT `[0x01] + endData` ack (byte-identical
    /// to the legacy `.withResponse` write). Chained on `ackChain` so acks reach the strap in
    /// chunk order even though each one is an independent async facade call — the legacy
    /// CoreBluetooth write queue gave that ordering for free.
    func ackHistoricalChunk(trim: UInt32, endData: [UInt8]) {
        let previous = ackChain
        let bytes = endData.toKotlinByteArray()
        ackChain = Task { [client] in
            await previous?.value
            try? await client.ackHistoricalChunk(endData: bytes)
        }
        // Progress signal for the "Syncing strap history…" UI (#77). Same main-actor path as the
        // other state mutations (e.g. lastSyncedAt in exitBackfilling). The legacy
        // `historicalAckLogCounter` (a puffin-write log throttle) did not make the move — it
        // throttled a CoreBluetooth write log that no longer exists.
        state.syncChunksThisSession += 1
    }

    /// Durably archive undecodable record frames (append-only JSONL, fsynced) BEFORE the Backfiller
    /// acks the trim — the user's only remaining copy of an unmapped firmware's records once the
    /// strap frees them, and the corpus a later layout mapping re-ingests. Updates the session
    /// counters that drive the honest sync status. Returns false ONLY on a genuine write failure,
    /// which makes the Backfiller hold the cursor/ack so the strap re-sends the chunk (no data loss
    /// either way). Frames carry sensor payloads, not identifiers — no serials/MACs are archived.
    private func archiveRejectedFrames(_ frames: [[UInt8]], trim: UInt32, family: WhoopProtocol.DeviceFamily) -> Bool {
        switch rejectedHistoryArchive.archive(frames, trim: trim, family: family) {
        case .written(let count):
            state.rejectedFramesThisSession += count
            return true
        case .capReached(let count):
            // Cap reached: succeed WITHOUT writing (wedging the offload over a full archive would be
            // worse; ample sample bytes exist by now), counted separately so the sync status never
            // claims "saved" for bytes that were not.
            state.rejectedFramesUnarchived += count
            log("Backfill: rejected-frame archive is FULL — \(count) frame(s) NOT preserved (acking anyway so the offload can finish)")
            return true
        case .failed:
            log("Backfill: rejected-frame archive FAILED — holding ack so the strap re-sends")
            return false
        }
    }

    // MARK: - Inactivity reminder (#419)

    private func maybeBuzzInactivity() {
        guard InactivityPrefs.isEnabled() else { return }   // cheap pre-check before any DB read
        Task { @MainActor in
            let nowSec = Int(Date().timeIntervalSince1970)
            let from = nowSec - Self.inactivityLookbackSeconds
            let gravity = await collector?.recentGravity(from: from, to: nowSec) ?? []
            guard !gravity.isEmpty else { return }
            // Sleep & Rest test mode (Group E): bank the live gravity window for the readout's coverage
            // figure. Gated on the zero-cost active() Bool, so this is a no-op when the mode is off.
            if TestCentre.active(.sleep) { state.recordSleepLiveGravity(gravity) }

            let decision = SedentaryDetector.evaluate(
                gravity, state: InactivityPrefs.loadState(),
                config: InactivityPrefs.loadConfig(),
                worn: state.worn, nowSec: nowSec,
                tzOffsetSec: InactivityPrefs.tzOffsetSec(nowSec))
            // The engine always advances lastProcessedGravityTs when a window arrived, so persist the
            // de-dup state every run — a replayed window then can't re-buzz across a relaunch.
            InactivityPrefs.saveState(decision.nextState)

            if decision.shouldBuzz {
                // ADAPTATION: legacy `send(.runHapticsPattern, [2, loops, 0, 0, 0])` → the shim's
                // T15c-1 haptics bridge (same pattern id 2; Kotlin flow 5 plans the bytes and the
                // 5/MG maverick remap).
                runHapticPattern(patternId: 2, loops: Int(UInt8(clamping: decision.buzzLoops)))
                let mins = Int((decision.bout?.durationS ?? 0) / 60)
                log("Inactivity: nudged after a \(mins)-min sedentary stretch.")
                AppModel.postInactivity(minutes: mins)   // #577 — local notification (iOS-only, self-gated on notif.masterEnabled)
            }
        }
    }
}

// MARK: - [UInt8] → KotlinByteArray bridging

private extension Array where Element == UInt8 {
    /// Reverse of the main shim file's `KotlinByteArray.toUInt8Array()`: HISTORY_END acks carry
    /// the chunk's verbatim metadata bytes back through the Kotlin facade.
    func toKotlinByteArray() -> KotlinByteArray {
        let out = KotlinByteArray(size: Int32(count))
        for (i, byte) in enumerated() { out.set(index: Int32(i), value: Int8(bitPattern: byte)) }
        return out
    }
}
