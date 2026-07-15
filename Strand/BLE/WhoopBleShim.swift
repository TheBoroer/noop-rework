import Foundation
import Shared

/// T15b: the ONLY app-side seam onto the shared Kotlin BLE stack (`com.noop.ble.WhoopBleClient`,
/// Kable-backed). Replaces the CoreBluetooth plumbing half of `BLEManager.swift`; callers are
/// rewired in T15c and the legacy manager dies in T15d.
///
/// SKIE note (supersedes the caveat in `Packages/WhoopProtocol/Sources/WhoopProtocol/Framing.swift`
/// and `Streams.swift`): this file DOES consume SKIE Swift-only API (`for await` over Kotlin
/// flows). That was historically avoided because the x86_64 iOS-simulator slice shipped without
/// SKIE's Swift enrichment. Verified 2026-07: the current `Shared.xcframework` has no iosX64 slice
/// at all (project.yml `EXCLUDED_ARCHS[sdk=iphonesimulator*]: x86_64` keeps Intel simulators out),
/// and the `macos-arm64_x86_64` slice's `x86_64-apple-macos.swiftinterface` carries the full SKIE
/// surface (`SkieSwiftFlow` et al, identical to arm64). Every slice the app builds against has the
/// Swift API, so flow collection from app code is safe.
@MainActor
public final class WhoopBleShim: ObservableObject {

    // MARK: Published mirror of the Kotlin client's flows

    /// Straps seen by the current scan, newest advertisement wins (mirror of Kotlin `discovered`).
    @Published public private(set) var discovered: [DiscoveredStrap] = []
    @Published public private(set) var isScanning: Bool = false
    @Published public private(set) var phase: Phase = .disconnected(reason: nil)

    /// Swift-native projection of Kotlin `DiscoveredWhoop` so SwiftUI rows don't touch Shared types.
    public struct DiscoveredStrap: Identifiable, Equatable {
        public let id: String       // platform peripheral identifier (UUID string on Apple)
        public let name: String     // WhoopAdvertisementFilter.displayName
        public let rssi: Int
        public let isWhoop5: Bool?  // nil when the advertisement didn't carry a family service UUID
    }

    /// Swift-native projection of the Kotlin sealed `WhoopBleClient.ConnectionState`.
    public enum Phase: Equatable {
        case disconnected(reason: String?)
        case connecting(identifier: String)
        case connected(identifier: String, isWhoop5: Bool)

        public var isConnected: Bool { if case .connected = self { return true }; return false }
    }

    // MARK: Frame egress

    /// Every reassembled frame off the notify characteristics, in arrival order. T15c points this
    /// at `FrameRouter`; until then frames still count via `LiveState.noteFrameRouted()`.
    public var onFrame: ((SessionFrame) -> Void)?

    // MARK: Wiring

    public let state: LiveState
    /// Kotlin facade. `internal` (not `private`): the +Backfill extension file drives the
    /// SEND_HISTORICAL kick + HISTORY_END acks through it (Swift `private` is file-scoped).
    let client: WhoopBleClient
    private var pumps: [Task<Void, Never>] = []
    /// Opt-in WHOOP 5/MG raw-frame capture (legacy `BLEManager.puffinRecorder`), fed by the
    /// frame pump off `SessionFrame.raw`.
    private lazy var puffinRecorder = PuffinFrameRecorder(state: state)

    // MARK: Store ownership (T15c-2 — moved from BLEManager; see WhoopBleShim+Backfill.swift)
    // Deliberately `internal`, not `private`: Swift `private` is file-scoped and the moved
    // machinery lives in the +Backfill extension file. Nothing outside Strand/BLE touches these.

    /// Device id new samples persist under ("my-whoop" until `bootstrapStore` reads the registry;
    /// re-pointed by `setActiveDeviceId` on a WHOOP↔WHOOP switch, exactly as the legacy engine).
    public internal(set) var deviceId: String
    /// Store-layer pair (Strand/Collect), built once by `bootstrapStore()`. `collector == nil`
    /// doubles as the "not bootstrapped yet" gate, mirroring `BLEManager.bootstrapStore`.
    var collector: Collector?
    var backfiller: Backfiller?
    /// Durable JSONL archive for undecodable record frames (#152 retro-decode corpus).
    let rejectedHistoryArchive = RawHistoryArchive()
    /// Backfill session flags/queue — 1:1 with the legacy manager's fields.
    var backfilling = false
    var backfillFrameQueue: [[UInt8]] = []
    var backfillDraining = false
    var backfillTimeout: DispatchWorkItem?
    /// 15-min periodic offload cadence (legacy `backfillTimer`): armed on connect, torn down on
    /// disconnect/close. The Kotlin connect already ran the hello/SET_CLOCK handshake, so the
    /// connected phase is the legacy "bonded" precondition.
    var backfillTimer: DispatchSourceTimer?
    /// Serialises HISTORY_END acks so they reach the strap in chunk order even though each ack is
    /// an independent async client write (legacy CoreBluetooth writes were inherently ordered).
    var ackChain: Task<Void, Never>?
    /// Auto-continue budget: a WHOOP with a deep backlog banks more than one offload per sync;
    /// legacy capped chained re-kicks at 6 per connection to bound a decode-loop regression.
    var consecutiveAutoContinues = 0
    /// WHOOP 5/MG straps ack a SEND_HISTORICAL kick even when the bank is empty; this tracker
    /// separates "empty because caught up" from "empty because refusing" (legacy #126 semantics).
    var whoop5EmptyOffload = Whoop5EmptyOffloadTracker()
    /// Sustained-empty classifier (#126): escalates the "charge to 100%" banner only once
    /// emptiness repeats — a single empty cycle on an otherwise-banking strap stays silent.
    var emptySyncTracker = EmptySyncTracker()
    /// #364 spin-detector memory: the Backfiller's high-water trim when the previous session
    /// ended. A frozen cursor across sessions means "don't re-kick, it would spin forever".
    var lastSessionEndTrim: UInt32?

    // MARK: Bond-loop salvage probe (T15c-3b, #78 hole-4; logic in WhoopBleShim+SalvageProbe.swift)
    // Deliberately `internal`, not `private`, for the same file-scoping reason as the store-ownership
    // fields above: the moved wiring lives in the +SalvageProbe extension file.

    /// #78 hole-4 pause latch mirror of legacy `BLEManager.autoReconnectPausedForBondLoop`. Nothing
    /// in production trips this yet: see the +SalvageProbe file doc comment for why (no persistent
    /// Kotlin-side reconnect coordinator has been ported). `tripBondLoopPause` / `clearBondLoopPause`
    /// are the hooks a future coordinator would call.
    var pausedForBondLoop = false
    /// Mirror of legacy `BLEManager.bondLoopPausedAt`: when the pause last tripped, or was last
    /// re-stamped by a salvage probe. Nil until the first trip.
    var bondLoopPausedAt: Date?
    /// Mirror of legacy `BLEManager.intentionalDisconnect`: true only while the most recent
    /// disconnect was user-initiated (forget device / model switch), so the salvage probe never
    /// fires for a strap the user deliberately let go of. Reset at the top of every fresh connect.
    var intentionalDisconnect = false
    /// `internal`, not `private`: installed/removed from the +SalvageProbe extension file.
    var foregroundSalvageObserver: NSObjectProtocol?

    public init(state: LiveState, client: WhoopBleClient, deviceId: String = "my-whoop") {
        self.state = state
        self.client = client
        self.deviceId = deviceId
        startPumps()
        installForegroundSalvageProbe()
    }

    /// One pump per Kotlin flow. Each is a plain `for await`; SKIE ends the sequence when the
    /// client's scope is cancelled, so the tasks wind down on `close()` without extra plumbing.
    private func startPumps() {
        pumps.append(Task { [weak self, client] in
            for await list in client.discovered {
                guard let self else { return }
                self.discovered = (list as? [DiscoveredWhoop] ?? []).map { d in
                    DiscoveredStrap(
                        id: d.identifier,
                        name: d.name,
                        rssi: Int(d.rssi),
                        isWhoop5: d.model.map { $0 == .whoop5 }
                    )
                }
            }
        })
        pumps.append(Task { [weak self, client] in
            for await flag in client.isScanning {
                self?.isScanning = flag.boolValue
            }
        })
        pumps.append(Task { [weak self, client] in
            for await st in client.connection {
                self?.apply(st)
            }
        })
        pumps.append(Task { [weak self, client] in
            for await frame in client.frames {
                guard let self else { return }
                self.state.noteFrameRouted()
                // Opt-in raw-frame capture (SettingsView / Test Centre). Pre-gated so the
                // KotlinByteArray conversion never runs while the toggle is off.
                if PuffinFrameRecorder.isCaptureEnabled {
                    self.puffinRecorder.capture(frame: frame.raw.toUInt8Array(),
                                                charUuid: frame.characteristicUuid)
                }
                // T15c-2: HISTORICAL_DATA frames are queued for the Backfiller (store side)
                // before the generic hook — same precedence the legacy manager had, where
                // backfill consumption never depended on an observer being attached. Pre-gated
                // on `backfilling` so the KotlinByteArray→[UInt8] copy never runs on the live
                // flood outside an offload session.
                if self.backfilling {
                    self.routeBackfillFrame(frame.raw.toUInt8Array())
                }
                self.onFrame?(frame)
            }
        })
    }

    private func apply(_ st: WhoopBleClient.ConnectionState) {
        switch st {
        case let c as WhoopBleClient.ConnectionStateConnecting:
            phase = .connecting(identifier: c.identifier)
        case let c as WhoopBleClient.ConnectionStateConnected:
            phase = .connected(identifier: c.identifier, isWhoop5: c.family == .whoop5)
            state.connected = true
            // Kotlin's connect() returns only after the hello/SET_CLOCK handshake succeeded,
            // so reaching .connected IS the legacy "bonded" milestone. Deliberately sticky
            // across disconnects (cleared by removeDevice/prepareForModelSwitch only, #78).
            state.bonded = true
            state.append(log: "ble: connected \(c.identifier)")
            // T15c-2: store + backfill ownership (WhoopBleShim+Backfill.swift). Kotlin's
            // connect already ran the hello/SET_CLOCK handshake, so "connected" here IS the
            // legacy "bonded" precondition: build the store pair on first use, arm the 15-min
            // offload cadence, and kick one immediate sync (legacy `didBond` behaviour).
            bootstrapStoreIfNeeded()
            armBackfillTimer()
            syncNow()
        case let c as WhoopBleClient.ConnectionStateDisconnected:
            phase = .disconnected(reason: c.reason)
            state.connected = false
            state.streamingLiveHR = false
            state.append(log: "ble: disconnected\(c.reason.map { " (\($0))" } ?? "")")
            teardownBackfill(reason: "disconnect")
        default:
            break // sealed in Kotlin; unreachable unless the shared enum grows a case
        }
    }

    // MARK: Commands (thin async passthroughs; policy stays in Kotlin)

    public func startScan(preferredIdentifier: String? = nil) {
        client.startScan(
            models: [Shared.WhoopModel.whoop4, Shared.WhoopModel.whoop5],
            preferredIdentifier: preferredIdentifier
        )
    }

    public func stopScan() { client.stopScan() }

    public func connect(identifier: String) async throws {
        // Legacy `connectCore` clears `intentionalDisconnect` at the top of every fresh connect
        // attempt (BLEManager.swift:749): a new attempt is never itself intentional-disconnect.
        intentionalDisconnect = false
        try await client.connect(identifier: identifier)
    }

    public func disconnect() async {
        try? await client.disconnect()
    }

    public func startRealtime() async throws { try await client.startRealtime() }
    public func stopRealtime() async throws { try await client.stopRealtime() }

    public func sendCommands(_ planned: [PlannedSend]) async throws {
        try await client.sendCommands(planned: planned)
    }

    // MARK: Strap commands (plans built by Kotlin `CommandPlanner`, sent via flow 5)

    /// Family of the currently connected strap, or nil when disconnected. Plan builders are
    /// family-specific (WHOOP 4 vs 5 opcodes differ), so every command wrapper gates on this.
    var connectedFamily: Shared.DeviceFamily? {
        if case let .connected(_, isWhoop5) = phase { return isWhoop5 ? .whoop5 : .whoop4 }
        return nil
    }

    public func armStrapAlarm(at date: Date) async throws {
        guard let family = connectedFamily else { return }
        try await sendCommands(CommandPlanner.shared.planArmAlarm(
            family: family,
            wakeEpochMs: Int64(date.timeIntervalSince1970 * 1000),
            nowUnixSeconds: Int64(Date().timeIntervalSince1970)
        ))
    }

    public func disableStrapAlarm() async throws {
        guard let family = connectedFamily else { return }
        try await sendCommands([CommandPlanner.shared.planDisableAlarm(family: family)])
    }

    public func buzzStrapOnce() async throws {
        guard let family = connectedFamily else { return }
        try await sendCommands(CommandPlanner.shared.planBuzz(family: family))
    }

    /// Haptic Clock (#460): buzz the current wall-clock time out on the strap (legacy
    /// `BLEManager.buzzTimeNow`, 2538-2559). The pure Kotlin `HapticClock` encodes now into an
    /// ordered pulse list; we walk it on a Task, sending one `planHapticPulse` per pulse (LONG =
    /// 2 stacked loops, SHORT = 1 — the 5/MG maverick remap happens inside Kotlin flow 5) and
    /// sleeping each pulse's `durationMs + gapMs` spacing, matching the legacy `asyncAfter`
    /// schedule. Fire-and-forget by design, like the legacy call.
    public func buzzTimeNow(is24h: Bool = false, at date: Date = Date()) {
        let comps = Calendar.current.dateComponents([.hour, .minute], from: date)
        let pulses = HapticClock.shared.pulses(
            hour: Int32(comps.hour ?? 0), minute: Int32(comps.minute ?? 0), is24h: is24h)
        guard !pulses.isEmpty else {
            state.append(log: "Haptic Clock: nothing to buzz (00:00 in 24h form).")
            return
        }
        state.append(log: "Haptic Clock: buzzing \(pulses.count) pulses for the current time (\(is24h ? "24h" : "12h")).")
        Task { [weak self] in
            for pulse in pulses {
                let plan = CommandPlanner.shared.planHapticPulse(loops: pulse.isLong ? 2 : 1)
                try? await self?.sendCommands([plan])
                try? await Task.sleep(nanoseconds: UInt64(pulse.durationMs + pulse.gapMs) * 1_000_000)
            }
        }
    }

    public func refreshBattery() async throws {
        guard let family = connectedFamily,
              let plan = CommandPlanner.shared.planReadBattery(family: family) else { return }
        try await sendCommands([plan])
    }

    public func renameStrap(_ rawName: String) async throws {
        guard let family = connectedFamily,
              let plan = CommandPlanner.shared.planRename(family: family, rawName: rawName) else { return }
        try await sendCommands([plan])
    }

    /// Legacy `BLEManager.externalLog` seam — non-BLE subsystems (HR broadcast, imports) append
    /// into the same session log callers already watch.
    public func externalLog(_ line: String) { state.append(log: line) }

    // MARK: Strap identity / targeting (legacy pin + wizard hooks, Kable-simplified)

    /// Strap `connect()`/`startScan()` should target; nil = single-WHOOP first-found default.
    /// The legacy #52 bond-refusal streak and readoption handoff died with CoreBluetooth —
    /// Kotlin `BleSession` owns bond retries now, so a pin here is JUST targeting.
    public private(set) var preferredIdentifier: String?

    public func setPreferredPeripheral(_ identifier: String?) {
        preferredIdentifier = identifier
    }

    // `setActiveDeviceId(_:)` (WHOOP↔WHOOP active-strap switch) already lives in
    // WhoopBleShim+Backfill.swift, next to the `deviceId`/`collector`/`backfiller` it re-points.

    /// Release a strap so it can enter pairing mode elsewhere (#78): drop targeting, stop scanning,
    /// and — when the removed strap is the live/connecting one — drop the link and the sticky bond
    /// flags. The legacy give-up/pause reset has no shim equivalent (bond pacing lives in Kotlin).
    public func forgetDevice(_ identifier: String?) async {
        let isCurrent: Bool
        switch phase {
        case let .connected(id, _), let .connecting(id):
            isCurrent = identifier == nil || id == identifier
        case .disconnected:
            isCurrent = identifier == nil
        }
        if identifier == nil || preferredIdentifier == identifier { preferredIdentifier = nil }
        stopScan()
        if isCurrent {
            intentionalDisconnect = true
            await disconnect()
            state.bonded = false
            state.encryptedBond = false
            state.pairingHint = nil
        }
        state.append(log: "Device removed — released the strap: dropped targeting + link. Put it in pairing mode (blue LEDs) to re-pair. (#78)")
    }

    /// User picked a different strap model: drop the link and clear the **sticky** bond state so the
    /// newly-picked model bonds fresh (the WHOOP-4→5 switch bug — `bonded` outliving its strap).
    public func prepareForModelSwitch() async {
        intentionalDisconnect = true
        await disconnect()
        state.bonded = false
        state.encryptedBond = false
    }

    /// Idle before an Add-a-WHOOP scan — but ONLY when not already connected to the same family.
    /// Tearing down a live, bonded same-family link makes a 5/MG refuse the encrypted re-bond and
    /// loop forever (#74), so that case keeps the connection and just presents nearby straps.
    public func prepareForPresentScan(family: Shared.DeviceFamily) async {
        if case .connected = phase, connectedFamily == family {
            state.append(log: "Add-a-WHOOP scan: keeping the live connection (#74) — presenting nearby straps without dropping it")
            return
        }
        await prepareForModelSwitch()
    }

    // MARK: Legacy settings/command compat (T15c)

    /// Legacy `BLEManager.setKeepRealtimeForData(_:)` — the RAW continuous-capture preference;
    /// window-gating (overnight-only) is applied Kotlin-side by `RealtimePolicy`, fed the current
    /// overnight flag at every call exactly like the legacy re-derive-at-arm-site behaviour.
    public func setKeepRealtimeForData(_ keep: Bool) {
        Task { [client] in
            try? await client.setKeepRealtimeForData(
                keep: keep,
                overnightOnly: PuffinExperiment.continuousHrvOvernightOnlyEnabled)
        }
    }

    /// Legacy `BLEManager.setBroadcastHr(_:)` (#181): the family/connect/bond guards and log
    /// lines live in the Kotlin facade; this is a fire-and-forget bridge for the settings toggles.
    public func setBroadcastHr(_ on: Bool) {
        Task { [client] in try? await client.setBroadcastHr(on: on) }
    }

    /// Legacy `BLEManager.enableWhoop5DeepData()`: the R22 enable sequence + gating live in the
    /// Kotlin facade (`planDeepDataEnable`).
    public func enableWhoop5DeepData() {
        Task { [client] in try? await client.enableWhoop5DeepData() }
    }

    /// Legacy `BLEManager.send(.runHapticsPattern, …)` callers (AppModel.buzz — coach cues,
    /// biofeedback, notification-pattern picker). patternId 0–6 on Harvard; the 5/MG maverick
    /// remap happens inside Kotlin flow 5. Fire-and-forget: unbonded/unconnected is a facade no-op.
    public func runHapticPattern(patternId: Int, loops: Int) {
        Task { [weak self] in
            try? await self?.sendCommands(
                [CommandPlanner.shared.planHapticPattern(patternId: Int32(patternId),
                                                         loops: Int32(loops))])
        }
    }

    /// Legacy `BLEManager.send(.stopHaptics, [0x00])` (#769): reversible clear for a wedged
    /// WHOOP 4.0 pattern; deliberately NOT allow-listed for 5/MG (logged no-op), same as legacy.
    public func stopHaptics() {
        Task { [weak self] in
            try? await self?.sendCommands([CommandPlanner.shared.planStopHaptics()])
        }
    }

    /// Legacy `BLEManager.flushPuffinCaptures()`: persist any buffered capture frames now
    /// (Settings / Test Centre buttons call this before surfacing the capture directory).
    public func flushPuffinCaptures() { puffinRecorder.flush() }

    /// Tear down flow pumps and the Kotlin client scope. Idempotent.
    public func close() {
        teardownBackfill(reason: "close")
        Task { [collector] in await collector?.flush() }
        pumps.forEach { $0.cancel() }
        pumps.removeAll()
        if let foregroundSalvageObserver {
            NotificationCenter.default.removeObserver(foregroundSalvageObserver)
            self.foregroundSalvageObserver = nil
        }
        client.close()
    }
}

// MARK: - KotlinByteArray bridging

private extension KotlinByteArray {
    /// SKIE bridges `ByteArray` as `KotlinByteArray`; the puffin recorder wants `[UInt8]`.
    /// Only invoked while capture is enabled (pre-gated at the frame pump).
    func toUInt8Array() -> [UInt8] {
        var out = [UInt8](repeating: 0, count: Int(size))
        for i in 0..<Int(size) { out[i] = UInt8(bitPattern: get(index: Int32(i))) }
        return out
    }
}
