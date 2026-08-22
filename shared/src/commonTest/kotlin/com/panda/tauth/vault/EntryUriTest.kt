package com.panda.tauth.vault

import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.valueOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// The Key Uri Format leaves out a parameter equal to its default, so each expectation below is
// written out rather than rebuilt from the entry it describes.
private const val TOTP_DEFAULTS = "otpauth://totp/GitHub:alice?secret=$TEST_SECRET&issuer=GitHub"

private const val TOTP_VARIED =
    "otpauth://totp/Ex%20Ample:carol?secret=$TEST_SECRET&issuer=Ex%20Ample&algorithm=SHA512&digits=8&period=60"

private const val HOTP_COUNTED = "otpauth://hotp/bob?secret=$TEST_SECRET&counter=41"

private const val HOTP_MAXIMUM = "otpauth://hotp/bob?secret=$TEST_SECRET&counter=18446744073709551615"

class EntryUriTest {
    @Test
    fun `a totp entry at the defaults names its issuer and account`() {
        assertEquals(TOTP_DEFAULTS, totpEntry().toOtpAuthUri().build())
    }

    @Test
    fun `a totp entry carries the algorithm, digits and period it names`() {
        val entry = totpEntry(accountName = "carol").copy(
            issuer = "Ex Ample",
            algorithm = HashAlgorithm.SHA512,
            digits = 8,
            period = 60,
        )

        assertEquals(TOTP_VARIED, entry.toOtpAuthUri().build())
    }

    @Test
    fun `a hotp entry carries its counter`() {
        assertEquals(HOTP_COUNTED, hotpEntry(counter = 41uL).toOtpAuthUri().build())
    }

    // The counter is an unsigned 64-bit value throughout, and one past the signed ceiling is where a
    // conversion to Long would report a negative number.
    @Test
    fun `a hotp counter at the unsigned maximum survives the build`() {
        assertEquals(HOTP_MAXIMUM, hotpEntry(counter = ULong.MAX_VALUE).toOtpAuthUri().build())
    }

    @Test
    fun `an entry with no issuer builds a label of the account name alone`() {
        val entry = totpEntry().copy(issuer = null)

        assertEquals("otpauth://totp/alice?secret=$TEST_SECRET", entry.toOtpAuthUri().build())
    }

    // The vault holds the secret as it was imported: re-encoding the bytes it decodes to would give
    // the same key a different spelling, losing the original's case, padding and whitespace.
    @Test
    fun `a secret stored in lower case builds the URI in lower case`() {
        val entry = totpEntry().copy(secret = TEST_SECRET.lowercase())

        assertEquals(
            "otpauth://totp/GitHub:alice?secret=${TEST_SECRET.lowercase()}&issuer=GitHub",
            entry.toOtpAuthUri().build(),
        )
    }

    // The secret is the whole reason this conversion is gated, and a URI missing it would enrol
    // nothing while looking like a successful export.
    @Test
    fun `the built URI carries the entry's secret`() {
        val parsed = OtpAuthUri.parse(totpEntry().toOtpAuthUri().build()).valueOrNull

        assertEquals(TEST_SECRET, parsed?.secret)
    }

    @Test
    fun `no rendering of the URI carries the secret`() {
        assertFalse(TEST_SECRET in totpEntry().toOtpAuthUri().toString())
    }
}
