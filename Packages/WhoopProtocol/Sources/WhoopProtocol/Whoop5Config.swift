import Foundation
import Shared

// MARK: - WHOOP 5.0 / MG "R22" feature-flag config (deep-stream unlock)
//
// WHOOP 5.0/MG straps withhold their deep biometric streams (the high-rate "R22" optical/HR/motion
// packets, type 0x2F) from a freshly-connected client. The official app switches them on by writing a
// short burst of persistent feature-flag config values right after the hello handshake — a sequence
// independently documented by two third parties:
//   • judes.club, "Cracking the WHOOP 5 Bluetooth Protocol" (decrypted HCI capture of the official app),
//     whose interactive frame-builder is the byte-level ground truth this file is validated against.
//   • Asherlc/dofek docs/whoop-ble-protocol.md (Android APK decompilation), corroborating the key names,
//     values and the SET_FF_VALUE (0x78) opcode.
//
// Each flag is ONE `SET_CONFIG` (0x78) command whose 40-byte payload is the flag NAME as ASCII, NUL-padded
// to 32 bytes, followed by a one-byte value (itself an ASCII digit: '1' = 0x31 or '2' = 0x32) at offset 32,
// then 7 bytes of zero. The command is built with the normal puffin envelope via `puffinCommandFrame`
// (cmd 0x78, the inner b3 byte 0x01 carried as the first payload byte, exactly like CLIENT_HELLO).
//
// This is reversible — it only changes which data the strap chooses to emit — but it is gated behind an
// explicit opt-in in the app, and on real hardware it can only be written from iOS/Android: macOS
// CoreBluetooth cannot complete the authenticated SMP bond the command characteristic requires.
//
// DELEGATED (Phase 2b): the opcodes, the flag table and the body/frame builders all come from the
// shared Kotlin `com.noop.protocol.Whoop5Config` (one config table + one byte-builder of record).
// The Swift `Flag` struct and this enum's public API are byte-for-byte unchanged;
// `Whoop5ConfigTests` is the parity net. `enableSequenceFrames` has no Kotlin twin and stays
// Swift, composing the delegated `frame(flag:seq:)`.
public enum Whoop5Config {

    /// SET_CONFIG / SET_FF_VALUE command opcode. Value of record: shared Kotlin `SET_CONFIG_CMD`.
    public static let setConfigCmd: UInt8 = UInt8(Shared.Whoop5Config.shared.SET_CONFIG_CMD)

    /// SET_DEVICE_CONFIG opcode (0x77) — writes ONE persistent device-config value, distinct from the
    /// feature-flag SET_CONFIG (0x78) sequence above. Used for the "Broadcast HR" flag
    /// (`whoop_live_hr_in_adv_ind_pkt`), which makes the strap advertise its heart rate as a standard
    /// 0x180D BLE sensor. Validated on real hardware (paired on a Garmin Edge 840). (#181)
    /// Value of record: shared Kotlin `SET_DEVICE_CONFIG_CMD`.
    public static let setDeviceConfigCmd: UInt8 = UInt8(Shared.Whoop5Config.shared.SET_DEVICE_CONFIG_CMD)

    /// One persistent feature flag and the value the official app writes for it.
    public struct Flag: Equatable, Sendable {
        public let name: String
        public let value: UInt8   // ASCII digit byte: 0x32 = '2', 0x31 = '1'
        public init(_ name: String, _ value: UInt8) { self.name = name; self.value = value }
    }

    /// The exact ordered enable sequence the official app sends, transcribed verbatim from judes.club's
    /// frame-builder `FLAGS` array (values are ASCII '1'/'2'). `enable_r22_packets` is what opens the
    /// type-0x2F biometric stream; the rest tune channel selection, wear detection and sleep behaviour.
    /// Table of record: the shared Kotlin `enableR22Sequence`, mapped 1:1 into the Swift `Flag` shape.
    public static let enableR22Sequence: [Flag] =
        Shared.Whoop5Config.shared.enableR22Sequence.map { Flag($0.name, UInt8($0.value)) }

    /// The 40-byte SET_CONFIG payload body: flag name as UTF-8/ASCII NUL-padded to 32 bytes, value byte
    /// at offset 32, then 7 zero bytes. (Mirrors judes.club `setConfigPayload(name, value)`.)
    /// Delegates to the shared Kotlin `Whoop5Config.payloadBody`.
    public static func payloadBody(name: String, value: UInt8) -> [UInt8] {
        Shared.Whoop5Config.shared.payloadBody(name: name, value: Int32(value)).toUInt8Array()
    }

    /// The 33-byte SET_DEVICE_CONFIG body: key name as ASCII NUL-padded to 32 bytes, then the value byte
    /// (an ASCII digit, '1' = 0x31 / '0' = 0x30) at offset 32 — NO trailing padding (unlike the 40-byte
    /// feature-flag body). The caller prepends the inner b3 byte (0x01) before sending, like CLIENT_HELLO.
    /// Validated on real hardware. (#181) Delegates to the shared Kotlin `Whoop5Config.deviceConfigBody`.
    public static func deviceConfigBody(name: String, value: UInt8) -> [UInt8] {
        Shared.Whoop5Config.shared.deviceConfigBody(name: name, value: Int32(value)).toUInt8Array()
    }

    /// The full puffin command-frame bytes for one feature-flag write, ready to send to the 5/MG
    /// command characteristic. The inner b3 byte (0x01, as SET_CONFIG-class commands require) is carried
    /// as the first payload byte ahead of the 40-byte body — matching the CLIENT_HELLO convention and
    /// byte-for-byte identical to the official app's captured writes.
    /// Delegates to the shared Kotlin `Whoop5Config.frame` (which builds through the shared
    /// `Framing.puffinCommandFrame`, the envelope of record).
    public static func frame(flag: Flag, seq: UInt8) -> [UInt8] {
        Shared.Whoop5Config.shared.frame(flag: .init(name: flag.name, value: Int32(flag.value)),
                                         seq: Int32(seq)).toUInt8Array()
    }

    /// Every frame in the enable sequence, sequence-numbered from `firstSeq`. The caller writes these in
    /// order, WITH RESPONSE, spacing them out (the official app pauses ~tens of ms between writes), and
    /// only while the strap is on-wrist — the R22 stream is on-wrist gated.
    ///
    /// Kept in Swift: there is no Kotlin twin for this convenience walk; it composes the delegated
    /// `frame(flag:seq:)`, so the emitted bytes still come from the shared builder.
    public static func enableSequenceFrames(firstSeq: UInt8 = 1) -> [[UInt8]] {
        enableR22Sequence.enumerated().map { idx, flag in
            frame(flag: flag, seq: UInt8((Int(firstSeq) + idx) & 0xFF))
        }
    }
}
