package com.panda.tauth.totp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

// RFC 6238 Appendix B. Errata 2866 (verified) corrects the specification text, which claims a single
// shared secret: the reference implementation uses a distinct seed per algorithm.
private val SHA1_SEED = "12345678901234567890".encodeToByteArray()
private val SHA256_SEED = "12345678901234567890123456789012".encodeToByteArray()
private val SHA512_SEED = "1234567890123456789012345678901234567890123456789012345678901234".encodeToByteArray()

private const val EIGHT_DIGITS = 8

private fun sha1(epochSeconds: Long) =
    Totp.generate(SHA1_SEED, epochSeconds, HashAlgorithm.SHA1, EIGHT_DIGITS, Totp.PERIOD_DEFAULT)

private fun sha256(epochSeconds: Long) =
    Totp.generate(SHA256_SEED, epochSeconds, HashAlgorithm.SHA256, EIGHT_DIGITS, Totp.PERIOD_DEFAULT)

private fun sha512(epochSeconds: Long) =
    Totp.generate(SHA512_SEED, epochSeconds, HashAlgorithm.SHA512, EIGHT_DIGITS, Totp.PERIOD_DEFAULT)

class TotpTest {
    @Test
    fun `RFC 6238 SHA-1 at 59 seconds produces 94287082`() {
        assertEquals("94287082", sha1(59))
    }

    @Test
    fun `RFC 6238 SHA-1 at 1111111109 produces 07081804`() {
        assertEquals("07081804", sha1(1111111109))
    }

    @Test
    fun `RFC 6238 SHA-1 at 1111111111 produces 14050471`() {
        assertEquals("14050471", sha1(1111111111))
    }

    @Test
    fun `RFC 6238 SHA-1 at 1234567890 produces 89005924`() {
        assertEquals("89005924", sha1(1234567890))
    }

    @Test
    fun `RFC 6238 SHA-1 at 2000000000 produces 69279037`() {
        assertEquals("69279037", sha1(2000000000))
    }

    @Test
    fun `RFC 6238 SHA-1 at 20000000000 produces 65353130`() {
        assertEquals("65353130", sha1(20000000000))
    }

    @Test
    fun `RFC 6238 SHA-256 at 59 seconds produces 46119246`() {
        assertEquals("46119246", sha256(59))
    }

    @Test
    fun `RFC 6238 SHA-256 at 1111111109 produces 68084774`() {
        assertEquals("68084774", sha256(1111111109))
    }

    @Test
    fun `RFC 6238 SHA-256 at 1111111111 produces 67062674`() {
        assertEquals("67062674", sha256(1111111111))
    }

    @Test
    fun `RFC 6238 SHA-256 at 1234567890 produces 91819424`() {
        assertEquals("91819424", sha256(1234567890))
    }

    @Test
    fun `RFC 6238 SHA-256 at 2000000000 produces 90698825`() {
        assertEquals("90698825", sha256(2000000000))
    }

    @Test
    fun `RFC 6238 SHA-256 at 20000000000 produces 77737706`() {
        assertEquals("77737706", sha256(20000000000))
    }

    @Test
    fun `RFC 6238 SHA-512 at 59 seconds produces 90693936`() {
        assertEquals("90693936", sha512(59))
    }

    @Test
    fun `RFC 6238 SHA-512 at 1111111109 produces 25091201`() {
        assertEquals("25091201", sha512(1111111109))
    }

    @Test
    fun `RFC 6238 SHA-512 at 1111111111 produces 99943326`() {
        assertEquals("99943326", sha512(1111111111))
    }

    @Test
    fun `RFC 6238 SHA-512 at 1234567890 produces 93441116`() {
        assertEquals("93441116", sha512(1234567890))
    }

    @Test
    fun `RFC 6238 SHA-512 at 2000000000 produces 38618901`() {
        assertEquals("38618901", sha512(2000000000))
    }

    @Test
    fun `RFC 6238 SHA-512 at 20000000000 produces 47863826`() {
        assertEquals("47863826", sha512(20000000000))
    }

    @Test
    fun `the six-digit default is the tail of the RFC eight-digit value`() {
        assertEquals("287082", Totp.generate(SHA1_SEED, 59, HashAlgorithm.SHA1, 6, Totp.PERIOD_DEFAULT))
    }

    @Test
    fun `T is 1 at 59 seconds with a 30-second period`() {
        assertEquals(1L, Totp.counterAt(59, Totp.PERIOD_DEFAULT))
    }

    @Test
    fun `T exceeds the 32-bit range at 20000000000 seconds`() {
        // 0x27BC86AA. A 32-bit T truncates this, and the 20000000000 vectors fail.
        assertEquals(0x27BC86AAL, Totp.counterAt(20000000000, Totp.PERIOD_DEFAULT))
    }

    @Test
    fun `T is 0 in the first period`() {
        assertEquals(0L, Totp.counterAt(29, 30))
    }

    @Test
    fun `T advances on the period boundary`() {
        assertEquals(1L, Totp.counterAt(30, 30))
    }

    @Test
    fun `the last second of a period yields the same code as the first`() {
        assertEquals(sha1(30), sha1(59))
    }

    @Test
    fun `the first second of the next period yields a different code`() {
        assertNotEquals(sha1(59), sha1(60))
    }

    @Test
    fun `T floors towards negative infinity before the epoch`() {
        assertEquals(-1L, Totp.counterAt(-1, Totp.PERIOD_DEFAULT))
    }

    @Test
    fun `a code on a period boundary has its whole period left`() {
        // RFC 6238 Appendix B: T is 0000000000000002 from 60 seconds, so the period runs 60 to 89.
        assertEquals(30, Totp.secondsRemaining(60, Totp.PERIOD_DEFAULT))
    }

    @Test
    fun `a code in the last second of its period has one second left`() {
        assertEquals(1, Totp.secondsRemaining(59, Totp.PERIOD_DEFAULT))
    }

    @Test
    fun `the seconds left follow the period they are counted against`() {
        assertEquals(1, Totp.secondsRemaining(59, 60))
    }

    @Test
    fun `an hourly period leaves an hour on its boundary`() {
        assertEquals(3600, Totp.secondsRemaining(3600, 3600))
    }

    @Test
    fun `the seconds left before the epoch count towards the epoch`() {
        // The period holding -1 ends at 0, which is one second away.
        assertEquals(1, Totp.secondsRemaining(-1, Totp.PERIOD_DEFAULT))
    }

    @Test
    fun `a period of zero has no seconds to count`() {
        assertFailsWith<IllegalArgumentException> { Totp.secondsRemaining(0, 0) }
    }

    @Test
    fun `a period of zero is rejected`() {
        assertFailsWith<IllegalArgumentException> { Totp.counterAt(0, 0) }
    }

    @Test
    fun `a period of one second is accepted`() {
        assertEquals(59L, Totp.counterAt(59, 1))
    }

    @Test
    fun `an hourly period is accepted`() {
        // RFC 6238 §4.1 sets no upper bound on the time step.
        assertEquals(1L, Totp.counterAt(3600, 3600))
    }
}
