package com.noop.ble

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

// Kotlin/Native ships no platform lib for Apple's Compression framework, so the Apple slice uses
// `platform.zlib` with windowBits = -15 → RAW DEFLATE (RFC 1951), the same stream format Apple's
// `COMPRESSION_ZLIB` (used by the Swift helpers in RawOutbox.swift) reads and writes. Compressed
// bytes may differ from Swift's encoder; parity is cross-DECODABILITY — see the compression note
// on [OutboxCodec].

@OptIn(ExperimentalForeignApi::class)
internal actual fun rawDeflate(input: ByteArray): ByteArray {
    require(input.isNotEmpty()) { "cannot deflate empty input" } // packFrames output is always >= 4 bytes
    // DEFLATE worst case is a small constant overhead per 64 KiB block; *2 + 64 is comfortably above.
    val capacity = input.size * 2 + 64
    val dst = ByteArray(capacity)
    val written = memScoped {
        val strm = alloc<z_stream>() // memScoped alloc zero-fills: zalloc/zfree/opaque == Z_NULL
        val init = deflateInit2_(
            strm.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, /* windowBits = raw DEFLATE */ -15,
            /* memLevel */ 8, Z_DEFAULT_STRATEGY, zlibVersion()?.toKString(), sizeOf<z_stream>().convert(),
        )
        require(init == Z_OK) { "deflateInit2 failed: $init" }
        try {
            dst.usePinned { d ->
                input.usePinned { s ->
                    strm.next_in = s.addressOf(0).reinterpret()
                    strm.avail_in = input.size.convert()
                    strm.next_out = d.addressOf(0).reinterpret()
                    strm.avail_out = capacity.convert()
                    val rc = deflate(strm.ptr, Z_FINISH)
                    require(rc == Z_STREAM_END) { "deflate failed: $rc" }
                    strm.total_out.toInt()
                }
            }
        } finally {
            deflateEnd(strm.ptr)
        }
    }
    require(written > 0) { "deflate produced no output" }
    return dst.copyOf(written)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun rawInflate(input: ByteArray, expectedSize: Int): ByteArray {
    // Caller's exact-size check rejects a mismatch, so both degenerate shapes just return empty.
    if (input.isEmpty() || expectedSize <= 0) return ByteArray(0)
    val dst = ByteArray(expectedSize)
    val written = memScoped {
        val strm = alloc<z_stream>()
        val init = inflateInit2_(strm.ptr, /* windowBits = raw DEFLATE */ -15, zlibVersion()?.toKString(), sizeOf<z_stream>().convert())
        require(init == Z_OK) { "inflateInit2 failed: $init" }
        try {
            dst.usePinned { d ->
                input.usePinned { s ->
                    strm.next_in = s.addressOf(0).reinterpret()
                    strm.avail_in = input.size.convert()
                    strm.next_out = d.addressOf(0).reinterpret()
                    strm.avail_out = expectedSize.convert()
                    // Corrupt/truncated input inflates to fewer bytes (or errors with 0 written);
                    // return what was written and let the caller's exact-size check flag it —
                    // mirrors the Swift helper's `written == n` guard.
                    inflate(strm.ptr, Z_FINISH)
                    strm.total_out.toInt()
                }
            }
        } finally {
            inflateEnd(strm.ptr)
        }
    }
    return if (written == expectedSize) dst else dst.copyOf(written.coerceAtLeast(0))
}
