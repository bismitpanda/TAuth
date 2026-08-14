package com.panda.tauth.totp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

private val SEED = "12345678901234567890".encodeToByteArray()

class OtpCoreTest {
    @Test
    fun `a moving factor of zero encodes as eight zero bytes`() {
        assertContentEquals(ByteArray(8), OtpCore.movingFactorBytes(0uL))
    }

    @Test
    fun `a moving factor of one encodes big-endian with the low byte last`() {
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1), OtpCore.movingFactorBytes(1uL))
    }

    @Test
    fun `the 64-bit maximum encodes as eight 0xFF bytes`() {
        assertContentEquals(ByteArray(8) { 0xFF.toByte() }, OtpCore.movingFactorBytes(ULong.MAX_VALUE))
    }

    @Test
    fun `a moving factor above the 32-bit range keeps its high bytes`() {
        assertContentEquals(
            byteArrayOf(0, 0, 0, 0x27, 0xBC.toByte(), 0x86.toByte(), 0xAA.toByte(), 0),
            OtpCore.movingFactorBytes(0x27BC86AA00uL),
        )
    }

    @Test
    fun `the 64-bit maximum produces a different code from the 32-bit maximum`() {
        assertNotEquals(
            OtpCore.code(SEED, UInt.MAX_VALUE.toULong(), HashAlgorithm.SHA1, 6),
            OtpCore.code(SEED, ULong.MAX_VALUE, HashAlgorithm.SHA1, 6),
        )
    }

    @Test
    fun `eight digits at RFC 6238 T=1 produce the published code`() {
        assertEquals("94287082", OtpCore.code(SEED, 1uL, HashAlgorithm.SHA1, 8))
    }

    @Test
    fun `six digits at RFC 6238 T=1 produce the tail of the published code`() {
        // Both sides are anchored to the RFC rather than to each other: comparing one output of
        // OtpCore against another would hold even with a wrong HMAC or a wrong offset.
        assertEquals("287082", OtpCore.code(SEED, 1uL, HashAlgorithm.SHA1, 6))
    }

    @Test
    fun `seven digits sit between the six- and eight-digit codes`() {
        assertEquals("4287082", OtpCore.code(SEED, 1uL, HashAlgorithm.SHA1, 7))
    }

    @Test
    fun `digits below the minimum are rejected`() {
        assertFailsWith<IllegalArgumentException> { OtpCore.code(SEED, 0uL, HashAlgorithm.SHA1, 5) }
    }

    @Test
    fun `digits above the maximum are rejected`() {
        assertFailsWith<IllegalArgumentException> { OtpCore.code(SEED, 0uL, HashAlgorithm.SHA1, 9) }
    }
}
