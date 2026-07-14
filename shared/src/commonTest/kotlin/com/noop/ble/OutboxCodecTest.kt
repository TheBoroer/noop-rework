package com.noop.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [OutboxCodec] contract tests, mirroring the Swift `RawOutbox` helpers' behavior
 * (`Packages/WhoopStore/Sources/WhoopStore/RawOutbox.swift`). The cross-impl fixture below pins
 * that a blob produced by the Swift/Apple encoder decodes byte-for-byte here (the reverse
 * direction — Swift decoding a Kotlin/JVM-produced blob — is pinned by
 * `RawOutboxCrossImplTests.swift`).
 */
class OutboxCodecTest {

    // ---- deterministic fixture frames, mirrored in the two blob generators and the Swift test ----
    // f1: 104 bytes (a v24-frame-sized record), f2: empty, f3: 300 bytes.
    private val f1 = ByteArray(104) { ((it * 31 + 7) and 0xFF).toByte() }
    private val f2 = ByteArray(0)
    private val f3 = ByteArray(300) { ((it * 7 + 3) and 0xFF).toByte() }
    private val fixtureFrames = listOf(f1, f2, f3)

    /**
     * Blob produced by Apple's `compression_encode_buffer(..., COMPRESSION_ZLIB)` over
     * `packFrames([f1, f2, f3])` — the exact call the Swift `zlibCompressWithLength` makes
     * (generated with a scratch Swift script using the identical pack layout; packed length 420).
     * NOTE: the JVM `Deflater(DEFAULT_COMPRESSION, nowrap = true)` happens to produce the
     * byte-identical blob for this input (both encoders are zlib-lineage DEFLATE), which is why one
     * pinned constant serves both cross-impl directions — but the tests only ever assert
     * cross-DECODABILITY, never encoder byte equality, because DEFLATE does not guarantee it.
     */
    private val swiftBlobHex =
        "a401000063666060c800627635d794e645071ffc97b38da99eb5f3c67731d390e2491b2fbce7d3f5c9ee5a79e239" +
            "9baa4b72d3c203f7ffc9da4457cddc71fd9ba84970d1c40de7dff1ea786775ae38fe8c55c539a971c1fe7b7f" +
            "65aca32a676cbff655c438a870c2fa736f79b4bd323b961f7bcaa2ec94d800b48a4187918181994b50425e4d" +
            "d7c4dac933203c2e35a7b8aab1a37fdadc25ab37ed3c70fcdcd53b8f5f7dfcf19f8d57445a49d3c0dcced527" +
            "382a3123bfacb6a57bd2cc05cbd76ddd73f8d4c51bf79fbdfdf29b8953405c4e55c7d8cad1c33f2c3625bba8" +
            "b2a1bd6fea9cc5ab36eed87fecec95db8f5e7ef8fe8f9547584a5143dfccd6c53b2832213dafb4a6b96be28c" +
            "f9cbd66ed97de8e485ebf79ebef9fc8b91835f4c5645dbc8d2c1dd2f342639abb0a2bead77caec452b376cdf" +
            "77f4cce55b0f5fbcfff697855b4852415dcfd4c6d92b30223e2db7a4baa973c2f4794bd76cde75f0c4f96b77" +
            "9fbcfef493819d4f544659cbd0c2decd37243a29b3a0bcaeb567f2ac852bd66fdb7be4f4a59b0f9ebffbfa87" +
            "14ff0300"

    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) {
            ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
        }

    private fun assertFramesEqual(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size, "frame count")
        for (i in expected.indices) {
            assertTrue(expected[i].contentEquals(actual[i]), "frame $i differs")
        }
    }

    // ---- pack/unpack ----

    @Test
    fun packUnpackRoundTrips_typicalEmptyAndLargeFrames() {
        assertFramesEqual(fixtureFrames, OutboxCodec.unpackFrames(OutboxCodec.packFrames(fixtureFrames)))
        // Empty list round-trips as the 4-byte zero-count header.
        val emptyPacked = OutboxCodec.packFrames(emptyList())
        assertEquals(4, emptyPacked.size)
        assertFramesEqual(emptyList(), OutboxCodec.unpackFrames(emptyPacked))
    }

    @Test
    fun packedLayoutIsCountThenLenPrefixedFrames_u32LE() {
        val packed = OutboxCodec.packFrames(listOf(byteArrayOf(0xAA.toByte(), 0x01)))
        assertTrue(
            packed.contentEquals(
                byteArrayOf(1, 0, 0, 0, /* len */ 2, 0, 0, 0, /* bytes */ 0xAA.toByte(), 0x01),
            ),
            "layout must stay byte-identical to Swift packFrames",
        )
    }

    @Test
    fun unpackFrames_lenientContractMatchesSwift() {
        // No readable count → empty list.
        assertFramesEqual(emptyList(), OutboxCodec.unpackFrames(ByteArray(0)))
        assertFramesEqual(emptyList(), OutboxCodec.unpackFrames(byteArrayOf(1, 0)))
        // Truncated second entry → the fully-readable prefix is returned (Swift `break`s mid-scan).
        val packed = OutboxCodec.packFrames(fixtureFrames)
        val truncated = packed.copyOf(packed.size - 1)
        val out = OutboxCodec.unpackFrames(truncated)
        assertEquals(2, out.size, "third frame is truncated, first two must survive")
        assertTrue(f1.contentEquals(out[0]) && f2.contentEquals(out[1]))
    }

    // ---- compress/decompress ----

    @Test
    fun fullCodecRoundTripPreservesRawFrameBytes() {
        val blob = OutboxCodec.zlibCompressWithLength(OutboxCodec.packFrames(fixtureFrames))
        assertFramesEqual(fixtureFrames, OutboxCodec.unpackFrames(OutboxCodec.zlibDecompressWithLength(blob)))
    }

    @Test
    fun decompressRejectsCorruptBlobs() {
        assertFailsWith<IllegalArgumentException>("shorter than the length prefix") {
            OutboxCodec.zlibDecompressWithLength(byteArrayOf(1, 0))
        }
        val good = OutboxCodec.zlibCompressWithLength(OutboxCodec.packFrames(fixtureFrames))
        // Truncated payload can no longer inflate to the declared length.
        assertFailsWith<IllegalArgumentException>("truncated payload") {
            OutboxCodec.zlibDecompressWithLength(good.copyOf(good.size / 2))
        }
        // Length prefix claiming MORE than the payload inflates to.
        val inflatedClaim = good.copyOf()
        inflatedClaim[0] = (inflatedClaim[0] + 1).toByte()
        assertFailsWith<IllegalArgumentException>("length prefix mismatch") {
            OutboxCodec.zlibDecompressWithLength(inflatedClaim)
        }
    }

    @Test
    fun zeroLengthPrefixDecodesToEmpty_matchingSwift() {
        // Swift returns Data() for n == 0 without touching the payload.
        assertEquals(0, OutboxCodec.zlibDecompressWithLength(byteArrayOf(0, 0, 0, 0)).size)
    }

    // ---- cross-impl fixture ----

    @Test
    fun decodesSwiftProducedBlob_byteForByte() {
        val payload = OutboxCodec.zlibDecompressWithLength(bytes(swiftBlobHex))
        assertEquals(420, payload.size, "packed length pinned by the generator")
        assertFramesEqual(fixtureFrames, OutboxCodec.unpackFrames(payload))
    }
}
