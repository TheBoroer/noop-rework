package com.noop.ble

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

// Raw DEFLATE (nowrap = true): matches Apple COMPRESSION_ZLIB, which emits RFC 1951 with no zlib
// header/adler32 — see the compression note on [OutboxCodec].

internal actual fun rawDeflate(input: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ true)
    try {
        deflater.setInput(input)
        deflater.finish()
        val out = ArrayList<ByteArray>()
        var total = 0
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n <= 0 && deflater.finished()) break
            out.add(buf.copyOf(n))
            total += n
        }
        val result = ByteArray(total)
        var off = 0
        for (chunk in out) {
            chunk.copyInto(result, off)
            off += chunk.size
        }
        return result
    } finally {
        deflater.end()
    }
}

internal actual fun rawInflate(input: ByteArray, expectedSize: Int): ByteArray {
    val inflater = Inflater(/* nowrap = */ true)
    try {
        inflater.setInput(input)
        val out = ByteArray(expectedSize)
        var off = 0
        while (off < expectedSize && !inflater.finished()) {
            val n = try {
                inflater.inflate(out, off, expectedSize - off)
            } catch (e: DataFormatException) {
                throw IllegalArgumentException("corrupt DEFLATE payload: ${e.message}", e)
            }
            if (n == 0 && (inflater.finished() || inflater.needsInput())) break
            off += n
        }
        // A healthy blob inflates to exactly expectedSize with the stream finished; return the
        // truncated prefix otherwise so the caller's size check flags corruption.
        return if (off == expectedSize) out else out.copyOf(off)
    } finally {
        inflater.end()
    }
}
