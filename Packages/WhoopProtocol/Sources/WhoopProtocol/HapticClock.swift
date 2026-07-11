import Foundation
import Shared

/// Haptic Clock (#460): turn a wall-clock time into a deterministic list of wrist buzzes so a user
/// can read the time off the strap without looking at a screen — a long pulse counts tens, a short
/// pulse counts units, in the order hour-tens, hour-units, minute-tens, minute-units.
///
/// This is a PURE, platform-agnostic encoder: time-in, pulse-list-out, no I/O and no BLE. The trigger
/// (`BLEManager.buzzTimeNow()` on Apple, `WhoopBleClient.buzzTimeNow()` on Android) walks the list and
/// fires each pulse through the EXISTING maverick notification buzz; only the *schedule* of buzzes is
/// new, the buzz itself is the hardware-confirmed one.
///
/// DELEGATED (Phase 2b): the encoder and its timing table live in the shared Kotlin
/// `com.noop.protocol.HapticClock` (one schedule of record for all platforms). The Swift `Pulse`
/// struct and this enum's public API are unchanged; `HapticClockTests` is the parity net (e.g.
/// 3:25 pins the exact same list on both platforms).
///
/// Reading the buzzes:
///   • LONG pulse  = one "ten"   in the current digit group
///   • SHORT pulse = one "unit"  in the current digit group
///   • a short gap separates pulses; a long gap separates the four digit groups (HH-tens, HH-units,
///     MM-tens, MM-units); an extra-long gap separates the hour block from the minute block.
///   • a digit of 0 emits NO pulse — the group is signalled only by the surrounding group gaps, so
///     e.g. 10:05 is [long](ten-hour) · gap · (no hour-units) · BLOCK · (no min-tens) · gap · short×5.
public enum HapticClock {

    /// One buzz instruction: buzz the wrist for `durationMs`, then stay silent for `gapMs`.
    /// `Equatable` so tests can assert the exact list; plain ints keep it portable to Kotlin.
    public struct Pulse: Equatable {
        public let durationMs: Int
        public let gapMs: Int
        public init(durationMs: Int, gapMs: Int) {
            self.durationMs = durationMs
            self.gapMs = gapMs
        }

        /// Whether this is a "tens" pulse (long buzz) versus a "units" pulse (short). Lets a trigger
        /// weight the buzz (heavier vs lighter) without reaching into the encoder's private timing
        /// table — the constants stay module-internal. Kotlin twin: `Pulse.isLong`.
        public var isLong: Bool { durationMs >= HapticClock.longMs }
    }

    // Pulse + gap timing (ms). Values of record: the shared Kotlin `HapticClock` constants
    // (LONG_MS/SHORT_MS/INTRA_GAP_MS/GROUP_GAP_MS/BLOCK_GAP_MS), read once here so the Swift-side
    // consumers (`Pulse.isLong`, `LiveSessionHaptics`) and the Kotlin encoder can never drift.
    // #981: the buzz itself is a fixed hardware pattern, so durationMs+gapMs is only the start-to-start
    // SPACING between buzzes; the widened gaps give clear silence between taps and digit groups.
    static let longMs = Int(Shared.HapticClock.shared.LONG_MS)         // a "tens" pulse
    static let shortMs = Int(Shared.HapticClock.shared.SHORT_MS)       // a "units" pulse
    static let intraGapMs = Int(Shared.HapticClock.shared.INTRA_GAP_MS) // silence between two pulses inside one digit group
    static let groupGapMs = Int(Shared.HapticClock.shared.GROUP_GAP_MS) // silence between adjacent digit groups
    static let blockGapMs = Int(Shared.HapticClock.shared.BLOCK_GAP_MS) // silence between the hour block and the minute block

    /// Encode `hour`:`minute` into the buzz schedule.
    /// - Parameters:
    ///   - hour: hour of day, 0...23 (24-hour input — the app already stores wall time this way).
    ///   - minute: minute of hour, 0...59.
    ///   - is24h: if `false`, the hour is mapped to 12-hour clock form (12,1...11) before encoding,
    ///            so the wrist count matches a 12-hour face. AM/PM is NOT signalled (the user knows
    ///            roughly what part of day it is); only the dial reading is buzzed out.
    /// - Returns: the ordered pulse list. Empty only for the degenerate all-zero 24h midnight 0:00,
    ///   which has no pulses to emit; callers should treat an empty list as "nothing to buzz".
    ///
    /// Delegates to the shared Kotlin `HapticClock.pulses` (clamping, digit grouping, gap widening
    /// and the trailing-gap trim all live there).
    public static func pulses(hour: Int, minute: Int, is24h: Bool) -> [Pulse] {
        Shared.HapticClock.shared.pulses(hour: Int32(hour), minute: Int32(minute), is24h: is24h)
            .map { Pulse(durationMs: Int($0.durationMs), gapMs: Int($0.gapMs)) }
    }

    /// 24-hour hour → 12-hour dial reading (0→12, 13→1 … 23→11). Noon stays 12.
    /// Delegates to the shared Kotlin `HapticClock.twelveHour`.
    static func twelveHour(_ h24: Int) -> Int {
        Int(Shared.HapticClock.shared.twelveHour(h24: Int32(h24)))
    }
}
