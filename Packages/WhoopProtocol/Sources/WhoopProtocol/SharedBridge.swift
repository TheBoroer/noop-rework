import Shared
import Foundation

/// Bridge marker proving the Kotlin shared framework links into this package.
/// Real delegation lands file by file; see the unification design doc.
enum SharedBridge {
    /// Kotlin: com.noop.update.UpdateCheck-adjacent pure helper is NOT exposed;
    /// use a trivially pure shared symbol to pin linkage.
    static func kotlinLinkProbe() -> String {
        // SharedSmoke lives in commonMain since Phase 1 Task 4.
        SharedSmoke.shared.MODULE
    }
}

// MARK: - Byte-array bridging (Swift <-> Kotlin)
//
// Kotlin `ByteArray` bridges to Swift as `KotlinByteArray` (SKIE / Kotlin-Native), not `Data`, so
// every wrapper that hands bytes across the seam converts through the one pair of helpers below.
// Framing works in `[UInt8]`, so the `[UInt8]` variants are primary (they avoid an intermediate
// `Data` copy on the offload hot path); the `Data` pair is the general form for the wider migration.
//
// The conversion is an element loop: KotlinByteArray exposes only get(index:)/set(index:value:), so
// there is no bulk memcpy across the interop boundary. Frames are tens-to-hundreds of bytes (a raw
// historical record tops out near 1.9 KB), so this is O(n) per call and bounded. If a large-buffer
// profile ever shows this dominating, a `toNSData()`-backed memcpy path is the escape hatch.

extension Array where Element == UInt8 {
    /// Copy this byte array into a fresh Kotlin `ByteArray` (bit-pattern preserved: UInt8 -> Int8).
    func toKotlinByteArray() -> KotlinByteArray {
        let arr = KotlinByteArray(size: Int32(count))
        withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            for i in 0..<count { arr.set(index: Int32(i), value: Int8(bitPattern: raw[i])) }
        }
        return arr
    }
}

extension Data {
    /// Copy this `Data` into a fresh Kotlin `ByteArray` (bit-pattern preserved: UInt8 -> Int8).
    func toKotlinByteArray() -> KotlinByteArray {
        let arr = KotlinByteArray(size: Int32(count))
        // Type the closure param: `Data.withUnsafeBytes` has a deprecated typed-pointer overload too.
        withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            for i in 0..<count { arr.set(index: Int32(i), value: Int8(bitPattern: raw[i])) }
        }
        return arr
    }
}

extension KotlinByteArray {
    /// Copy this Kotlin `ByteArray` into a Swift `[UInt8]` (bit-pattern preserved: Int8 -> UInt8).
    func toUInt8Array() -> [UInt8] {
        let n = Int(size)
        var out = [UInt8](repeating: 0, count: n)
        for i in 0..<n { out[i] = UInt8(bitPattern: get(index: Int32(i))) }
        return out
    }

    /// Copy this Kotlin `ByteArray` into `Data` (bit-pattern preserved: Int8 -> UInt8).
    func toData() -> Data {
        var data = Data(capacity: Int(size))
        for i in 0..<size { data.append(UInt8(bitPattern: get(index: i))) }
        return data
    }
}

// NOTE on the Kotlin `DeviceFamily`: it is NOT bridged here. The WhoopProtocol SPM package still
// compiles the x86_64 iOS-simulator slice (the app excludes it, the package does not inherit that),
// and for that slice only the Objective-C-bridged Shared symbols are visible, not SKIE's Swift-only
// enum `Shared.DeviceFamily`. So callers pass the family via implicit-member inference against the
// Objective-C parameter type and never name the Swift enum type (see `Reassembler` in Framing.swift,
// `skinTempCelsius` in Streams.swift, `rejectedHistoricalRecords` in HistoricalStreams.swift,
// `LiveSessionHaptics.pulses(for:)`). Task 6 stayed on this pattern: the `XxxKt` free-function
// containers and the non-enum Kotlin classes keep arch-agnostic Swift names in the ObjC header +
// apinotes, so no delegation needed a SKIE-Swift-only symbol and no packaging change was required.
