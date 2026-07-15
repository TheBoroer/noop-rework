import Foundation
import Shared
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// T15c-3b (#78 hole-4): the foreground bond-loop salvage probe, ported from
/// `BLEManager.installForegroundSalvageProbe` / `BLEManager.salvageProbeIfBondLoopPaused`
/// (BLEManager.swift:915-990, near where `bondLoopSalvageFloorSeconds` and
/// `shouldSalvageProbe` are declared). On every app foreground, if the pause latch is set,
/// evaluate the pure gate; if it fires, RE-STAMP the pause timestamp BEFORE attempting the
/// connect (so back-to-back foregrounds cannot chain probes), then make exactly one bounded
/// connect attempt.
///
/// Design note (full writeup in `.superpowers/sdd/t15c3b-report.md`): the GATE FUNCTION itself
/// (`shouldSalvageProbe`, `BOND_LOOP_SALVAGE_FLOOR_SECONDS`) calls straight through to its Kotlin
/// export (`Shared.BleSessionKt`, confirmed reachable via the generated framework header and the
/// SKIE Swift wrapper). The pause LATCH + TIMESTAMP, however, stay shim-local
/// (`pausedForBondLoop` / `bondLoopPausedAt` on `WhoopBleShim`) rather than reading live off a
/// Kotlin `ReconnectPolicy`: `WhoopBleClient.connect()` constructs a brand-new `BleSession` (and
/// so a brand-new `ReconnectPolicy` / `BondRefusalGiveUp`) on every call, so there is no
/// persistent Kotlin-side policy object today for a per-strap refusal streak to live on across
/// reconnects, and `WhoopBleClient` does not expose `reconnectPolicy` to Swift regardless
/// (`withSession` is explicitly Kotlin-only, see its doc comment). Building that persistent,
/// cross-reconnect coordinator is the auto-reconnect port `BLEManager` used to own; it was never
/// carried into Kotlin or the shim, and building it is a new feature, not a "thin pass-through"
/// or a behavior-preserving move, so it is out of scope here.
///
/// `BondRefusalGiveUp` on the Kotlin side DOES now carry a `pausedAtEpochSeconds` timestamp plus
/// a `restampPause()` hook (added this task, with `BleSessionPolicyTest` coverage), ready for
/// that future coordinator to drive. Until it exists, `tripBondLoopPause` / `clearBondLoopPause`
/// below are the shim's own entry points: nothing in production calls them yet, so the latch is
/// dormant (never trips on its own) and this whole file is inert in practice today. It is fully
/// wired and gate-tested, so hooking up a real coordinator later only needs to call
/// `tripBondLoopPause`, not touch this file.
extension WhoopBleShim {

    /// One bounded connect attempt's scan window (identifier must resurface in `discovered`
    /// before `connect(identifier:)` can be called: `WhoopBleClient.connect` fails fast for an
    /// identifier not seen by the current scan, there is no CoreBluetooth retrieve-by-UUID
    /// equivalent in the Kotlin facade yet).
    private static let salvageProbeScanTimeoutSeconds: TimeInterval = 10

    /// Trip the bond-loop pause latch. Hook for a future auto-reconnect coordinator (see the file
    /// doc comment): nothing calls this in production yet.
    public func tripBondLoopPause(now: Date = Date()) {
        pausedForBondLoop = true
        bondLoopPausedAt = now
    }

    /// Clear the bond-loop pause latch: a genuine bond landed, or the user explicitly reconnected.
    public func clearBondLoopPause() {
        pausedForBondLoop = false
        bondLoopPausedAt = nil
    }

    func installForegroundSalvageProbe() {
        #if os(iOS)
        let name = UIApplication.didBecomeActiveNotification
        #elseif os(macOS)
        let name = NSApplication.didBecomeActiveNotification
        #else
        return
        #endif
        foregroundSalvageObserver = NotificationCenter.default.addObserver(
            forName: name, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.salvageProbeIfBondLoopPaused() }
        }
    }

    /// Pure state check: does the salvage probe fire right now, and if so, re-stamp the pause
    /// timestamp immediately, BEFORE any connect attempt is made. Returns true iff the caller
    /// should go on to attempt one connect. Deliberately split from `attemptOneSalvageConnect()`
    /// (which touches the Kotlin client / CoreBluetooth) so the re-stamp contract is unit-testable
    /// without a CoreBluetooth seam: see `WhoopBleShimSalvageProbeTests`.
    func shouldFireSalvageProbe(now: Date = Date()) -> Bool {
        let secondsSince = bondLoopPausedAt.map { Int64(now.timeIntervalSince($0)) }
        let fires = Shared.shouldSalvageProbe(
            pausedForBondLoop: pausedForBondLoop,
            connected: state.connected,
            intentionalDisconnect: intentionalDisconnect,
            secondsSincePauseTripped: secondsSince.map { Shared.KotlinLong(value: $0) }
        )
        guard fires else { return false }
        // Re-stamp BEFORE the connect attempt (BLEManager.swift:965): back-to-back foregrounds
        // cannot chain probes even when this attempt does not land a genuine bond.
        bondLoopPausedAt = now
        return true
    }

    func salvageProbeIfBondLoopPaused(now: Date = Date()) {
        guard shouldFireSalvageProbe(now: now) else { return }
        state.append(log: "Bond-loop pause: one salvage probe (the strap may have been freed since the give-up); the give-up stays latched")
        attemptOneSalvageConnect()
    }

    /// One bounded connect attempt for the salvage probe. A no-op when nothing is pinned
    /// (`preferredIdentifier == nil`): the legacy probe reconnected the persisted/current model,
    /// which has no meaning without a specific strap to target here.
    private func attemptOneSalvageConnect() {
        guard let target = preferredIdentifier else {
            state.append(log: "Bond-loop salvage probe: no pinned strap to target, skipping.")
            return
        }
        Task { [weak self] in
            guard let self else { return }
            self.startScan(preferredIdentifier: target)
            let deadline = Date().addingTimeInterval(Self.salvageProbeScanTimeoutSeconds)
            while Date() < deadline, !self.discovered.contains(where: { $0.id == target }) {
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
            self.stopScan()
            guard self.discovered.contains(where: { $0.id == target }) else {
                self.state.append(log: "Bond-loop salvage probe: \(target) did not resurface within \(Int(Self.salvageProbeScanTimeoutSeconds))s, skipping this attempt.")
                return
            }
            try? await self.connect(identifier: target)
        }
    }
}
