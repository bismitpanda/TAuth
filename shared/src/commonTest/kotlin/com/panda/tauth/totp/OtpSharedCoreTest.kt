package com.panda.tauth.totp

import kotlin.test.Test
import kotlin.test.assertEquals

private val SHA1_SEED = "12345678901234567890".encodeToByteArray()
private val SHA256_SEED = "12345678901234567890123456789012".encodeToByteArray()
private val SHA512_SEED = "1234567890123456789012345678901234567890123456789012345678901234".encodeToByteArray()

private const val EIGHT_DIGITS = 8

// Reproducing the RFC 6238 vectors by feeding TOTP's moving factor through HOTP's entry point is
// the check that the two types share one implementation rather than two.
private fun throughHotp(seed: ByteArray, algorithm: HashAlgorithm, epochSeconds: Long): String =
    Hotp.generate(seed, Totp.counterAt(epochSeconds, Totp.PERIOD_DEFAULT).toULong(), algorithm, EIGHT_DIGITS)

class OtpSharedCoreTest {
    @Test
    fun `the HOTP path reproduces the SHA-1 vector at 59 seconds`() {
        assertEquals("94287082", throughHotp(SHA1_SEED, HashAlgorithm.SHA1, 59))
    }

    @Test
    fun `the HOTP path reproduces the SHA-1 vector at 1111111109`() {
        assertEquals("07081804", throughHotp(SHA1_SEED, HashAlgorithm.SHA1, 1111111109))
    }

    @Test
    fun `the HOTP path reproduces the SHA-1 vector at 1111111111`() {
        assertEquals("14050471", throughHotp(SHA1_SEED, HashAlgorithm.SHA1, 1111111111))
    }

    @Test
    fun `the HOTP path reproduces the SHA-1 vector at 1234567890`() {
        assertEquals("89005924", throughHotp(SHA1_SEED, HashAlgorithm.SHA1, 1234567890))
    }

    @Test
    fun `the HOTP path reproduces the SHA-1 vector at 2000000000`() {
        assertEquals("69279037", throughHotp(SHA1_SEED, HashAlgorithm.SHA1, 2000000000))
    }

    @Test
    fun `the HOTP path reproduces the SHA-1 vector at 20000000000`() {
        assertEquals("65353130", throughHotp(SHA1_SEED, HashAlgorithm.SHA1, 20000000000))
    }

    @Test
    fun `the HOTP path reproduces the SHA-256 vector at 59 seconds`() {
        assertEquals("46119246", throughHotp(SHA256_SEED, HashAlgorithm.SHA256, 59))
    }

    @Test
    fun `the HOTP path reproduces the SHA-256 vector at 1111111109`() {
        assertEquals("68084774", throughHotp(SHA256_SEED, HashAlgorithm.SHA256, 1111111109))
    }

    @Test
    fun `the HOTP path reproduces the SHA-256 vector at 1111111111`() {
        assertEquals("67062674", throughHotp(SHA256_SEED, HashAlgorithm.SHA256, 1111111111))
    }

    @Test
    fun `the HOTP path reproduces the SHA-256 vector at 1234567890`() {
        assertEquals("91819424", throughHotp(SHA256_SEED, HashAlgorithm.SHA256, 1234567890))
    }

    @Test
    fun `the HOTP path reproduces the SHA-256 vector at 2000000000`() {
        assertEquals("90698825", throughHotp(SHA256_SEED, HashAlgorithm.SHA256, 2000000000))
    }

    @Test
    fun `the HOTP path reproduces the SHA-256 vector at 20000000000`() {
        assertEquals("77737706", throughHotp(SHA256_SEED, HashAlgorithm.SHA256, 20000000000))
    }

    @Test
    fun `the HOTP path reproduces the SHA-512 vector at 59 seconds`() {
        assertEquals("90693936", throughHotp(SHA512_SEED, HashAlgorithm.SHA512, 59))
    }

    @Test
    fun `the HOTP path reproduces the SHA-512 vector at 1111111109`() {
        assertEquals("25091201", throughHotp(SHA512_SEED, HashAlgorithm.SHA512, 1111111109))
    }

    @Test
    fun `the HOTP path reproduces the SHA-512 vector at 1111111111`() {
        assertEquals("99943326", throughHotp(SHA512_SEED, HashAlgorithm.SHA512, 1111111111))
    }

    @Test
    fun `the HOTP path reproduces the SHA-512 vector at 1234567890`() {
        assertEquals("93441116", throughHotp(SHA512_SEED, HashAlgorithm.SHA512, 1234567890))
    }

    @Test
    fun `the HOTP path reproduces the SHA-512 vector at 2000000000`() {
        assertEquals("38618901", throughHotp(SHA512_SEED, HashAlgorithm.SHA512, 2000000000))
    }

    @Test
    fun `the HOTP path reproduces the SHA-512 vector at 20000000000`() {
        assertEquals("47863826", throughHotp(SHA512_SEED, HashAlgorithm.SHA512, 20000000000))
    }
}
