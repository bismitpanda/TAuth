package com.panda.tauth.totp

import kotlin.test.Test
import kotlin.test.assertEquals

// RFC 4226 §5.1's published seed, base32-encoded.
private const val SEED_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

// RFC 6238 errata 2866: Appendix B uses a distinct seed per algorithm, 32 bytes for SHA-256.
private val SHA256_SEED_BASE32 = Base32.encode("12345678901234567890123456789012".encodeToByteArray())

private const val EIGHT_DIGITS = 8

private fun totp(
    secret: String = SEED_BASE32,
    algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    digits: Int = EIGHT_DIGITS,
    period: Int = Totp.PERIOD_DEFAULT,
) = OtpAuthUri(
    type = OtpType.TOTP,
    accountName = "alice",
    secret = secret,
    algorithm = algorithm,
    digits = digits,
    period = period,
)

private fun hotp(counter: ULong, digits: Int = EIGHT_DIGITS) = OtpAuthUri(
    type = OtpType.HOTP,
    accountName = "bob",
    secret = SEED_BASE32,
    digits = digits,
    period = null,
    counter = counter,
)

// The preview has to be the code the server will compute, so each case below is a published vector
// rather than a value this implementation agrees with.
class PreviewCodeTest {
    @Test
    fun `a totp preview at 59 seconds carries the published SHA-1 vector`() {
        assertEquals("94287082", previewCode(totp(), epochSeconds = 59))
    }

    @Test
    fun `a totp preview at 1111111109 seconds carries the published SHA-1 vector`() {
        assertEquals("07081804", previewCode(totp(), epochSeconds = 1111111109))
    }

    @Test
    fun `a totp preview reads the algorithm the account names`() {
        val uri = totp(secret = SHA256_SEED_BASE32, algorithm = HashAlgorithm.SHA256)

        assertEquals("46119246", previewCode(uri, epochSeconds = 59))
    }

    @Test
    fun `a totp preview reads the digit count the account names`() {
        assertEquals("287082", previewCode(totp(digits = 6), epochSeconds = 59))
    }

    // At 89 seconds a 60-second period is in step one and the default 30-second period is in step two,
    // so a period the preview did not read gives a different code.
    @Test
    fun `a totp preview reads the period the account names`() {
        assertEquals("94287082", previewCode(totp(period = 60), epochSeconds = 89))
    }

    @Test
    fun `a hotp preview at counter zero carries the published vector`() {
        assertEquals("84755224", previewCode(hotp(counter = 0uL), epochSeconds = 0))
    }

    // The counter is the whole moving factor of a hotp account, so the preview has to move with it.
    @Test
    fun `a hotp preview at counter one carries the published vector`() {
        assertEquals("94287082", previewCode(hotp(counter = 1uL), epochSeconds = 0))
    }

    @Test
    fun `a hotp preview reads the digit count the account names`() {
        assertEquals("755224", previewCode(hotp(counter = 0uL, digits = 6), epochSeconds = 0))
    }

    // The clock plays no part in a hotp code, and a preview that moved with it would show one the
    // server never computes.
    @Test
    fun `a hotp preview does not move with the clock`() {
        assertEquals(
            previewCode(hotp(counter = 0uL), epochSeconds = 0),
            previewCode(hotp(counter = 0uL), epochSeconds = 1111111109),
        )
    }
}
