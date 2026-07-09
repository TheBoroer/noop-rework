package com.noop.oura

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two [FramingTest] cases that depend on [OuraAuth] (nonce extraction / auth-status decode).
 * Extracted out of FramingTest.kt (commonTest) because OuraAuth is androidMain-only (javax.crypto,
 * PHASE2: hoist), so these stay JVM-only while the rest of FramingTest.kt also runs on iOS.
 */
class OuraAuthFramingParityTest {
    private fun bytes(s: String) = OuraTestHex.bytes(s)

    @Test
    fun testSecureFrameNonceResponse() {
        // Wire: 2f 10 2c <nonce:15>. Outer: op 0x2F, len 0x10 (16), body = 2c + 15 nonce bytes.
        val wire = bytes("2f102c0102030405060708090a0b0c0d0e0f")
        val outer = OuraFraming.parseOuterFrame(wire)!!
        assertEquals(0x2F, outer.op)
        val secure = OuraFraming.parseSecureFrame(outer)!!
        assertEquals(0x2C, secure.subop)
        assertArrayEquals(bytes("0102030405060708090a0b0c0d0e0f"), secure.subBody)
        // And the auth layer pulls the 15-byte nonce straight out.
        assertArrayEquals(bytes("0102030405060708090a0b0c0d0e0f"), OuraAuth.nonce(secure))
    }

    @Test
    fun testSecureFrameAuthStatus() {
        // 2f 02 2e 00 -> success.
        val wire = bytes("2f022e00")
        val outer = OuraFraming.parseOuterFrame(wire)!!
        val secure = OuraFraming.parseSecureFrame(outer)!!
        assertEquals(0x2E, secure.subop)
        assertEquals(OuraAuthStatus.SUCCESS, OuraAuth.authStatus(secure))
    }
}
