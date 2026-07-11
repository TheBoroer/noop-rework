package com.noop.protocol

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * [rejectedHistoricalRecords] — the genuinely-undecodable type-47 record frames the Backfiller must
 * archive BEFORE acking the trim (#77 / #91). The strap frees acked history, so anything this misses
 * (false negative) is permanently lost; anything it over-counts (false positive) wastes the archive
 * and the honest "could not decode" status.
 *
 * The two hard requirements from the bug: a CONSOLE (type-50) frame decodes to zero rows BY DESIGN
 * and must NOT be flagged, while a real record frame that fails to decode MUST be flagged.
 */
class RejectedHistoricalRecordsTest {

    // Real on-wrist WHOOP 4.0 v24 record (HR 109) — the same hardware frame HistoricalFallbackTest uses.
    private val realV24Hex =
        "aa6400a12f18054c1c0a023ed0266a5037805418016d022b0234020000000000006b07ff00" +
        "85593c1f65cebed7b3e63eb85a5f3f000080401f65cebed7b3e63eb85a5f3f500264025d03" +
        "640229014009010c020c00000000000f0001c4020000000000008fdeb278"

    private fun bytes(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    /** Recompute the body CRC32 (over inner bytes frame[4 until length]) and write it LE into the
     *  trailer so a frame mutated in the test still validates. */
    private fun repairCrc32(frame: ByteArray) {
        val length = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)
        val crc = Crc.crc32(frame.copyOfRange(4, length))
        frame[length] = (crc and 0xFF).toByte()
        frame[length + 1] = ((crc shr 8) and 0xFF).toByte()
        frame[length + 2] = ((crc shr 16) and 0xFF).toByte()
        frame[length + 3] = ((crc shr 24) and 0xFF).toByte()
    }

    @Test
    fun goodRecordIsNotRejected() {
        // Sanity / control: a record that decodes fine must never be flagged as rejected.
        val good = bytes(realV24Hex)
        assertEquals(emptyList<ByteArray>(), rejectedHistoricalRecords(listOf(good), DeviceFamily.WHOOP4))
    }

    @Test
    fun consoleTypeFrameIsExcluded() {
        // A type-50 (0x32) CONSOLE_LOGS frame: the strap's own diagnostics text. It decodes to zero
        // rows by design and must NOT count as a rejected record (frame[4] = 0x32, not 0x2F/47).
        val console = bytes(realV24Hex)
        console[4] = 0x32             // packet type byte → CONSOLE_LOGS (50)
        repairCrc32(console)         // keep the envelope valid so only the type guard excludes it
        assertEquals(emptyList<ByteArray>(), rejectedHistoricalRecords(listOf(console), DeviceFamily.WHOOP4), "type-50 console frame must not be counted as a lost record")
    }

    @Test
    fun crcFailedRecordIsRejected() {
        // A genuine type-47 record whose body is corrupted so its CRC fails — undecodable, and its
        // bytes would be silently dropped. It MUST be flagged for archiving.
        val bad = bytes(realV24Hex)
        bad[21] = (bad[21].toInt() xor 0xFF).toByte() // flip HR byte, leave the CRC trailer stale → CRC mismatch
        val rejected = rejectedHistoricalRecords(listOf(bad), DeviceFamily.WHOOP4)
        assertEquals(1, rejected.size)
        assertTrue(rejected[0].contentEquals(bad))
    }

    @Test
    fun unmappedNonPhysicalRecordIsRejected() {
        // A CRC-valid type-47 record on an UNMAPPED firmware version whose v24-fallback decode yields
        // non-physical data (gravity zeroed) — the plausibility gate drops it, so it is genuinely lost
        // and must be flagged. Exercises the decode-returns-null branch (not just the CRC branch).
        val bad = bytes(realV24Hex)
        bad[5] = 99.toByte()                  // unmapped version
        for (i in 40 until 52) bad[i] = 0     // zero gravity x/y/z → |g| = 0, fails the gate
        repairCrc32(bad)
        val rejected = rejectedHistoricalRecords(listOf(bad), DeviceFamily.WHOOP4)
        assertEquals(1, rejected.size)
        assertTrue(rejected[0].contentEquals(bad))
    }

    @Test
    fun v25RecordWithImplausibleGravityIsRejected() {
        // A CRC-valid v25 record whose gravity fails the ~1 g magnitude gate decodes to `unix` only:
        // no heart rate (v25 never stores one) and no gravity vector, so it banks ZERO rows and is
        // genuinely lost, so it MUST be flagged for archiving. Pins the Swift-parity predicate
        // (`unix == null || (heart_rate == null && gravity_x == null)`); before that alignment this
        // record was silently kept because decodeHistorical returned a non-null (timestamp-only) map.
        val bad = bytes(realV24Hex)
        bad[5] = 25                           // v25 layout (unix @11, gravity i16/16384 @73/75/77)
        for (i in 73 until 79) bad[i] = 0     // zero gravity → |g| = 0, fails the 0.5..1.5 gate
        repairCrc32(bad)
        val rejected = rejectedHistoricalRecords(listOf(bad), DeviceFamily.WHOOP4)
        assertEquals(1, rejected.size)
        assertTrue(rejected[0].contentEquals(bad))
    }

    @Test
    fun v25RecordWithPlausibleGravityIsNotRejected() {
        // Control for the case above: the same v25 record with a real ~1 g orientation vector decodes
        // gravity (the sleep-staging input), so it is real data and must NOT be flagged (#30).
        val good = bytes(realV24Hex)
        good[5] = 25
        good[73] = 0x00; good[74] = 0x40      // gx = 0x4000 = 16384 → 16384/16384 = 1.0 g
        for (i in 75 until 79) good[i] = 0    // gy = gz = 0 → |g| = 1.0, inside the gate
        repairCrc32(good)
        assertEquals(emptyList<ByteArray>(), rejectedHistoricalRecords(listOf(good), DeviceFamily.WHOOP4))
    }

    @Test
    fun mixedChunkFlagsOnlyTheUndecodableRecord() {
        // The case the old chunk-level isEmpty check missed: one good row hiding a loss. Only the bad
        // record (not the good one, not the console frame) must be returned.
        val good = bytes(realV24Hex)
        val console = bytes(realV24Hex).also { it[4] = 0x32; repairCrc32(it) }
        val bad = bytes(realV24Hex).also { it[21] = (it[21].toInt() xor 0xFF).toByte() } // CRC now stale → fails
        val rejected = rejectedHistoricalRecords(listOf(good, console, bad), DeviceFamily.WHOOP4)
        assertEquals(1, rejected.size)
        assertTrue(rejected[0].contentEquals(bad))
    }
}
