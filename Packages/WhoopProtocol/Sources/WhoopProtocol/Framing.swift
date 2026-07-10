import Foundation
import Shared

// The frame checksums, the "puffin" command builder, and the fragment reassembler all delegate to the
// shared Kotlin protocol code (com.noop.protocol: `Crc`, `Framing`, `Reassembler`). The Swift API here
// is byte-for-byte unchanged so the ~74 consumer files and the framing test suite (the parity net)
// keep compiling and passing untouched. The pieces with no Kotlin twin stay pure Swift, and are marked
// as such: `FrameCheck` + `verifyFrame(_:)`/`verifyFrame(_:family:)` (Kotlin's verify + FrameCheck are
// private and shaped differently) and `frameFromPayload(_:...)` (no Kotlin function exists). Those
// still route their checksums through the shared `crc*` wrappers below, so there is one CRC of record.

/// CRC-8 (poly 0x07) over `bytes[from..<(to ?? count)]`. The optional range defaults to the whole
/// array, so existing callers are unchanged; passing a range lets the frame validator checksum a slice
/// in place rather than slicing out a fresh `Array(frame[...])` for every frame on the offload path.
///
/// Delegates to the shared Kotlin `Crc.crc8`; the two implementations are byte-identical table ports.
public func crc8(_ bytes: [UInt8], _ from: Int = 0, _ to: Int? = nil) -> UInt8 {
    let upper = to ?? bytes.count
    return UInt8(truncatingIfNeeded:
        Crc.shared.crc8(data: bytes.toKotlinByteArray(), from: Int32(from), to: Int32(upper)))
}

/// Standard zlib CRC-32 over `bytes[from..<(to ?? count)]`. The optional range defaults to the whole
/// array (existing callers unchanged); the validator passes a range to checksum the inner record or
/// payload in place, skipping the per-frame sub-array copy that added up over a multi-night offload.
///
/// Delegates to the shared Kotlin `Crc.crc32`.
public func crc32(_ bytes: [UInt8], _ from: Int = 0, _ to: Int? = nil) -> UInt32 {
    let upper = to ?? bytes.count
    return UInt32(truncatingIfNeeded:
        Crc.shared.crc32(data: bytes.toKotlinByteArray(), from: Int32(from), to: Int32(upper)))
}

/// CRC16-Modbus (poly 0xA001, init 0xFFFF, reflected) over `bytes[from..<(to ?? count)]`. Used for the
/// Whoop 5.0 frame header check. The optional range defaults to the whole array; the validator passes
/// a range so the 6-byte header check needs no `Array(frame[0..<6])` copy.
///
/// Delegates to the shared Kotlin `Crc.crc16Modbus`.
public func crc16Modbus(_ bytes: [UInt8], _ from: Int = 0, _ to: Int? = nil) -> UInt16 {
    let upper = to ?? bytes.count
    return UInt16(truncatingIfNeeded:
        Crc.shared.crc16Modbus(data: bytes.toKotlinByteArray(), from: Int32(from), to: Int32(upper)))
}

public struct FrameCheck: Equatable {
    public let ok: Bool
    public let length: Int?
    public let crc8OK: Bool?
    public let crc32OK: Bool?
    public init(ok: Bool, length: Int? = nil, crc8OK: Bool? = nil, crc32OK: Bool? = nil) {
        self.ok = ok
        self.length = length
        self.crc8OK = crc8OK
        self.crc32OK = crc32OK
    }
}

@inline(__always)
private func u16le(_ bytes: [UInt8], _ off: Int) -> Int {
    Int(bytes[off]) | (Int(bytes[off + 1]) << 8)
}

@inline(__always)
private func u32le(_ bytes: [UInt8], _ off: Int) -> UInt32 {
    UInt32(bytes[off]) | (UInt32(bytes[off + 1]) << 8)
        | (UInt32(bytes[off + 2]) << 16) | (UInt32(bytes[off + 3]) << 24)
}

/// Validate a complete frame envelope and both CRCs.
/// Frame: [0xAA][len u16 LE][crc8(len)][...inner...][crc32 u32 LE], total = len + 4.
///
/// Kept in Swift: the shared Kotlin `verifyWhoop4`/`verifyWhoop5` and its `FrameCheck` are `private`,
/// and the public Kotlin `parseFrame`'s `ParsedFrame.ok` means "envelope well-formed" (true even when
/// a CRC fails), which is not this function's `ok` (crc8 AND crc32). The checksums still go to Kotlin.
public func verifyFrame(_ frame: [UInt8]) -> FrameCheck {
    if frame.count < 8 || frame[0] != 0xAA {
        return FrameCheck(ok: false)
    }
    let length = u16le(frame, 1)
    // Ranged CRCs checksum the frame in place, with no per-frame sub-array allocation.
    let crc8OK = crc8(frame, 1, 3) == frame[3]
    var crc32OK: Bool? = nil
    // length must cover at least the envelope's inner bytes (mirrors framing.py).
    if 7 <= length && length + 4 <= frame.count {
        // inner record = frame[4..<length]
        crc32OK = crc32(frame, 4, length) == u32le(frame, length)
    }
    let ok = crc8OK && (crc32OK ?? false)
    return FrameCheck(ok: ok, length: length, crc8OK: crc8OK, crc32OK: crc32OK)
}

/// Family-aware frame validation.
///
/// `whoop4` behaves EXACTLY like the no-family `verifyFrame(_:)` above (back-compat). `whoop5`
/// uses the Whoop 5.0 envelope reverse-engineered from Goose:
///
///   [0]   SOF 0xAA
///   [1]   format byte (0x01)
///   [2-3] declaredLength u16 LE  (= payload length + 4)
///   [4-5] header bytes
///   [6-7] CRC16-Modbus over frame[0..<6], u16 LE
///   [8..] payload (length = declaredLength - 4)
///   tail  CRC32 (zlib, LE) over the payload, 4 bytes
///   total = declaredLength + 8
///
/// For whoop5 the `crc8OK` field of the result carries the CRC16 header outcome (so callers get a
/// single uniform "header CRC ok?" signal regardless of family).
public func verifyFrame(_ frame: [UInt8], family: DeviceFamily) -> FrameCheck {
    switch family {
    case .whoop4:
        return verifyFrame(frame)
    case .whoop5:
        return verifyFrameWhoop5(frame)
    }
}

private func verifyFrameWhoop5(_ frame: [UInt8]) -> FrameCheck {
    // Minimum whoop5 frame: 8 header bytes (incl. CRC16) + 4 CRC32 trailer = 12.
    if frame.count < 12 || frame[0] != 0xAA {
        return FrameCheck(ok: false)
    }
    let declaredLength = u16le(frame, 2)
    // declaredLength counts payload + the 4-byte CRC32 trailer (mirrors Goose v5Frames/v5Payload).
    guard declaredLength >= 4 else {
        return FrameCheck(ok: false, length: declaredLength)
    }
    let total = declaredLength + 8

    // Header CRC16-Modbus over the first 6 bytes, stored LE at frame[6..8]. Ranged, no copy.
    let wantHeaderCRC = crc16Modbus(frame, 0, 6)
    let gotHeaderCRC = UInt16(frame[6]) | (UInt16(frame[7]) << 8)
    let headerCRCOK = wantHeaderCRC == gotHeaderCRC

    var crc32OK: Bool? = nil
    if frame.count >= total {
        // payload spans [8, total-4); CRC32 trailer is the final 4 bytes of the frame.
        let payloadEnd = total - 4
        // payload = frame[8..<payloadEnd], checksummed in place.
        let want = crc32(frame, 8, payloadEnd)
        let got = u32le(frame, payloadEnd)
        crc32OK = want == got
    }

    let ok = headerCRCOK && (crc32OK ?? false)
    // Report the header outcome through crc8OK so callers have a single header-CRC signal.
    return FrameCheck(ok: ok, length: declaredLength, crc8OK: headerCRCOK, crc32OK: crc32OK)
}

/// Reconstruct a complete frame from a bare payload (data == frame[7:]).
/// Some captures store only the data portion; rebuild the envelope with a correct zlib
/// crc32 and a placeholder crc8 byte (0x00). Mirrors framing.py frame_from_payload.
///
/// Kept in Swift: there is no Kotlin twin for this capture-repair helper. Its crc32 delegates.
public func frameFromPayload(_ data: [UInt8], type: UInt8, seq: UInt8 = 0, cmd: UInt8 = 0) -> [UInt8] {
    let inner: [UInt8] = [type, seq, cmd] + data
    let length = inner.count + 4
    var frame: [UInt8] = [0xAA, UInt8(length & 0xFF), UInt8((length >> 8) & 0xFF), 0x00]
    frame.append(contentsOf: inner)
    let c = crc32(inner)
    frame.append(UInt8(c & 0xFF))
    frame.append(UInt8((c >> 8) & 0xFF))
    frame.append(UInt8((c >> 16) & 0xFF))
    frame.append(UInt8((c >> 24) & 0xFF))
    return frame
}

/// EXPERIMENTAL: build a WHOOP 5.0/MG ("puffin") command frame in the CRC16 envelope (docs/PROTOCOL.md
/// section 2.2). The inner record is `[type][seq][cmd] + payload`; `declLen = innerLen + 4` (the CRC32
/// tail); the CRC16-Modbus covers the first six header bytes. `type` defaults to 35 (COMMAND) and
/// `header` to `[0x00, 0x01]`, mirroring the structure of the only puffin frame we know a real strap
/// accepts (the static CLIENT_HELLO). The returned frame round-trips through
/// `verifyFrame(_:family:.whoop5)`. Whether a 5/MG strap acts on a given command is exactly what
/// experimentation discovers, so the app gates any sending behind an opt-in switch and only writes to
/// the puffin command characteristic.
///
/// Delegates to the shared Kotlin `Framing.puffinCommandFrame`, which owns the pad4 alignment (the
/// inner record is padded to a 4-byte boundary before length/CRC, required for the 12-byte haptics
/// payload). The byte output is pinned by the maverick/alarm golden-hex tests on both platforms.
public func puffinCommandFrame(cmd: UInt8, seq: UInt8, payload: [UInt8] = [0x00],
                               type: UInt8 = 35, header: [UInt8] = [0x00, 0x01]) -> [UInt8] {
    Framing.shared.puffinCommandFrame(
        cmd: Int32(cmd),
        seq: Int32(seq),
        payload: payload.toKotlinByteArray(),
        type: Int32(type),
        header: header.toKotlinByteArray()
    ).toUInt8Array()
}

/// Accumulate BLE notification fragments into complete frames.
/// A complete frame is `length + 4` bytes where length = u16 LE at buf[1..3] (whoop4), or
/// `declaredLength + 8` where declaredLength is u16 LE at buf[2..4] (whoop5).
///
/// This is a thin, STATEFUL wrapper: it owns one shared Kotlin `Reassembler` for its lifetime and
/// forwards every `feed` to it, so the reassembly window (partial frames, the read cursor, resync on a
/// garbage SOF) lives on the Kotlin side and persists across calls exactly as the old Swift buffer did.
/// The emitted frames are byte-identical and in the same order; `ReassemblerTests` /
/// `DeviceFamilyFramingTests` are the parity net. Mirrors the shared Kotlin `Reassembler`.
public final class Reassembler {
    private let kotlin: Shared.Reassembler

    /// `family` selects the frame-length convention (whoop4 vs whoop5); defaults to `.whoop4` so
    /// existing callers and tests are byte-for-byte unchanged.
    ///
    /// The family is handed to the Kotlin enum by implicit member (`.whoop4`/`.whoop5`), inferred from
    /// the shared `Reassembler.init(family:)` parameter, so no SKIE Swift type is named. That keeps the
    /// x86_64 iOS-simulator slice compiling, where only the Objective-C-bridged Shared symbols exist.
    public init(family: DeviceFamily = .whoop4) {
        switch family {
        case .whoop4: kotlin = Shared.Reassembler(family: .whoop4)
        case .whoop5: kotlin = Shared.Reassembler(family: .whoop5)
        }
    }

    public func feed(_ fragment: [UInt8]) -> [[UInt8]] {
        kotlin.feed(fragment: fragment.toKotlinByteArray()).map { $0.toUInt8Array() }
    }
}
