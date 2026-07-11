import Foundation
import Shared

/// Live Sessions (silent guardian) — the two, and only two, wrist signals the coach ever sends. Pure,
/// platform-agnostic: signal-in, pulse-list-out, no I/O and no BLE. The trigger (`BLEManager.buzz` on Apple,
/// `WhoopBleClient` on Android) walks the list and fires each pulse through the EXISTING hardware buzz,
/// weighting it by `Pulse.isLong` (a long pulse = the heavier 2-loop buzz, a short pulse = the lighter
/// 1-loop) — the same mechanism the Haptic Clock and Breath Pacer already use.
///
/// The vocabulary is deliberately tiny and distinguishable by FEEL alone, mid-effort, no screen:
///   • `push`    — two LIGHT taps  → "give a bit more"
///   • `easeOff` — three HEAVY taps → "ease off, today can't pay for this"
///
/// DELEGATED (Phase 2b): the pulse lists come from the shared Kotlin
/// `com.noop.protocol.LiveSessionHaptics` (one vocabulary of record). The Swift `Signal` enum and this
/// enum's public API are unchanged; `LiveSessionHapticsTests` is the parity net. The Kotlin signal is
/// handed over by implicit member (`.push`/`.easeOff`), inferred from the shared `pulses(signal:)`
/// parameter, so no SKIE Swift type is named (keeps the x86_64 iOS-simulator slice compiling, where
/// only the Objective-C-bridged Shared symbols exist). Design contract:
/// docs/superpowers/specs/2026-07-04-live-sessions-design.md.
public enum LiveSessionHaptics {

    /// The coach's entire signalling vocabulary. Silence (no signal) is the third, most common, state and
    /// carries no pulses at all — it is never sent, only felt as the absence of these two.
    public enum Signal: Equatable, Sendable {
        case push      // too easy for today
        case easeOff   // too hard for today
    }

    /// The ordered buzz list for a signal. The shared Kotlin encoder reuses `HapticClock`'s timing
    /// table (the same one the delegated Swift `HapticClock` reads), so the two features stay in
    /// lock-step: `shortMs`/`longMs` durations, `intraGapMs` spacing, final gap trimmed to 0.
    public static func pulses(for signal: Signal) -> [HapticClock.Pulse] {
        let kotlin: [Shared.HapticClock.Pulse]
        switch signal {
        case .push: kotlin = Shared.LiveSessionHaptics.shared.pulses(signal: .push)
        case .easeOff: kotlin = Shared.LiveSessionHaptics.shared.pulses(signal: .easeOff)
        }
        return kotlin.map { HapticClock.Pulse(durationMs: Int($0.durationMs), gapMs: Int($0.gapMs)) }
    }
}
