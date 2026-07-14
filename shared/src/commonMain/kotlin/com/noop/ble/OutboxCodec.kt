package com.noop.ble

/**
 * Kotlin twin of the Swift outbox frame codec (`Packages/WhoopStore/Sources/WhoopStore/RawOutbox.swift`
 * `packFrames` / `unpackFrames` / `zlibCompressWithLength` / `zlibDecompressWithLength`). Phase 2c-2
 * Task 12: required because the Kotlin Backfiller (Task 13) becomes an outbox WRITER, so a blob it
 * writes must decode byte-for-byte on the Swift side and vice versa. [com.noop.data.OutboxStore]
 * itself stays codec-agnostic (the blob is opaque there); this is the codec both writers share.
 *
 * Wire format (see RawOutbox.swift "frame (de)serialization"):
 *   packFrames:  [count u32 LE] { [len u32 LE][bytes] } x count
 *   blob:        [uncompressed-length u32 LE][raw DEFLATE payload]
 *
 * COMPRESSION NOTE: Apple's `COMPRESSION_ZLIB` (used by the Swift helpers via
 * `compression_encode_buffer`) is — despite the name — a RAW DEFLATE stream (RFC 1951, no zlib
 * header/adler32 trailer; documented in <compression.h>). The Kotlin actuals therefore must produce/
 * consume raw DEFLATE too: `java.util.zip.Deflater(level, nowrap = true)` on Android and
 * `platform.zlib` with windowBits = -15 on iOS/macOS (Kotlin/Native has no platform lib for Apple's
 * Compression framework). The compressed BYTES may legitimately differ between encoders
 * (zlib vs Apple's implementation — DEFLATE permits many encodings of the same data); parity is
 * defined as cross-DECODABILITY, pinned by the cross-impl fixture tests (`OutboxCodecTest` decodes a
 * Swift-produced blob; `RawOutboxCodecCrossImplTests` decodes a Kotlin-produced blob).
 */
object OutboxCodec {

    /** `[count u32 LE]{[len u32 LE][bytes]} x count` — byte-identical to Swift `packFrames`. */
    fun packFrames(frames: List<ByteArray>): ByteArray {
        var size = 4
        for (f in frames) size += 4 + f.size
        val buf = ByteArray(size)
        var off = 0
        fun putU32(v: Int) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
            buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
            buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
            off += 4
        }
        putU32(frames.size)
        for (f in frames) {
            putU32(f.size)
            f.copyInto(buf, off)
            off += f.size
        }
        return buf
    }

    /** Inverse of [packFrames]. Same lenient contract as Swift `unpackFrames`: no readable count →
     *  empty list; a truncated entry stops the scan (frames decoded so far are returned). */
    fun unpackFrames(data: ByteArray): List<ByteArray> {
        var off = 0
        fun readU32(): Int? {
            if (off + 4 > data.size) return null
            val v = (data[off].toInt() and 0xFF) or
                ((data[off + 1].toInt() and 0xFF) shl 8) or
                ((data[off + 2].toInt() and 0xFF) shl 16) or
                ((data[off + 3].toInt() and 0xFF) shl 24)
            off += 4
            return v
        }
        val count = readU32() ?: return emptyList()
        val out = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val len = readU32() ?: break
            if (len < 0 || off + len > data.size) break
            out.add(data.copyOfRange(off, off + len))
            off += len
        }
        return out
    }

    /** Compress [input] (raw DEFLATE) and prepend its uncompressed length as u32 LE — the blob shape
     *  Swift `zlibCompressWithLength` writes. */
    fun zlibCompressWithLength(input: ByteArray): ByteArray {
        val compressed = rawDeflate(input)
        val blob = ByteArray(4 + compressed.size)
        val n = input.size
        blob[0] = (n and 0xFF).toByte()
        blob[1] = ((n ushr 8) and 0xFF).toByte()
        blob[2] = ((n ushr 16) and 0xFF).toByte()
        blob[3] = ((n ushr 24) and 0xFF).toByte()
        compressed.copyInto(blob, 4)
        return blob
    }

    /** Decompress a blob produced by [zlibCompressWithLength] (either implementation). Mirrors the
     *  Swift contract: a blob shorter than its length prefix, or whose payload does not inflate to
     *  exactly the declared length, is corrupt → [IllegalArgumentException] (the Swift helper throws
     *  `CocoaError`). `n == 0` returns empty. */
    fun zlibDecompressWithLength(input: ByteArray): ByteArray {
        require(input.size >= 4) { "blob shorter than its u32 length prefix (${input.size} bytes)" }
        val n = (input[0].toInt() and 0xFF) or
            ((input[1].toInt() and 0xFF) shl 8) or
            ((input[2].toInt() and 0xFF) shl 16) or
            ((input[3].toInt() and 0xFF) shl 24)
        if (n == 0) return ByteArray(0)
        require(n > 0) { "corrupt length prefix ($n)" }
        val out = rawInflate(input.copyOfRange(4, input.size), n)
        require(out.size == n) { "blob inflated to ${out.size} bytes, declared $n — corrupt" }
        return out
    }
}

/** Raw DEFLATE (RFC 1951, NO zlib header/trailer — see [OutboxCodec] compression note). */
internal expect fun rawDeflate(input: ByteArray): ByteArray

/** Inverse of [rawDeflate]. [expectedSize] is the declared uncompressed size (exact output size on
 *  a healthy blob); implementations may throw or return a differently-sized array on corrupt input —
 *  the caller treats any size mismatch as corruption. */
internal expect fun rawInflate(input: ByteArray, expectedSize: Int): ByteArray
