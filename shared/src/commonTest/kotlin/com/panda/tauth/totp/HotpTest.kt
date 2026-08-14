package com.panda.tauth.totp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// RFC 4226 Appendix D. The seed is the ASCII string "12345678901234567890".
private val RFC4226_SEED = "12345678901234567890".encodeToByteArray()

private fun codeAt(counter: ULong): String = Hotp.generate(RFC4226_SEED, counter, HashAlgorithm.SHA1, 6)

class HotpTest {
    @Test
    fun `RFC 4226 counter 0 produces 755224`() {
        assertEquals("755224", codeAt(0uL))
    }

    @Test
    fun `RFC 4226 counter 1 produces 287082`() {
        assertEquals("287082", codeAt(1uL))
    }

    @Test
    fun `RFC 4226 counter 2 produces 359152`() {
        assertEquals("359152", codeAt(2uL))
    }

    @Test
    fun `RFC 4226 counter 3 produces 969429`() {
        assertEquals("969429", codeAt(3uL))
    }

    @Test
    fun `RFC 4226 counter 4 produces 338314`() {
        assertEquals("338314", codeAt(4uL))
    }

    @Test
    fun `RFC 4226 counter 5 produces 254676`() {
        assertEquals("254676", codeAt(5uL))
    }

    @Test
    fun `RFC 4226 counter 6 produces 287922`() {
        assertEquals("287922", codeAt(6uL))
    }

    @Test
    fun `RFC 4226 counter 7 produces 162583`() {
        assertEquals("162583", codeAt(7uL))
    }

    @Test
    fun `RFC 4226 counter 8 produces 399871`() {
        assertEquals("399871", codeAt(8uL))
    }

    @Test
    fun `RFC 4226 counter 9 produces 520489`() {
        assertEquals("520489", codeAt(9uL))
    }

    @Test
    fun `the 64-bit maximum counter is not narrowed to 32 bits`() {
        // No published vector exists past counter 9, so the assertion is the property rather than a
        // value: a truncating entry point would collapse these onto each other. The exact byte
        // encoding is pinned against 0xFF eight times in OtpCoreTest.
        assertNotEquals(codeAt(0xFFFFFFFFuL), codeAt(ULong.MAX_VALUE))
    }

    @Test
    fun `the 64-bit maximum counter is not narrowed to a signed 63 bits`() {
        assertNotEquals(codeAt(Long.MAX_VALUE.toULong()), codeAt(ULong.MAX_VALUE))
    }
}
