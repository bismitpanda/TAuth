package com.panda.tauth.totp

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.vault.UriParseError
import com.panda.tauth.vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Base32 of the RFC 4226 seed "12345678901234567890".
private const val SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

// The RFC 4226 seed and half of it again, 30 bytes of ASCII digits, for the cases that need two
// secrets that are both valid and different.
private const val OTHER_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

// U+2028 LINE SEPARATOR, spelled by code point so it stays visible in this source.
private val LINE_SEPARATOR = Char(0x2028).toString()

private fun parsed(uri: String): OtpAuthUri {
    val outcome = OtpAuthUri.parse(uri)
    assertIs<Outcome.Success<OtpAuthUri>>(outcome)
    return outcome.value
}

private fun errorOf(uri: String): UriParseError? = OtpAuthUri.parse(uri).errorOrNull

// A `when` with no else over the view a parse declares, so widening that signature stops this file
// compiling.
private fun uriCase(error: UriParseError): String = when (error) {
    is VaultError.MalformedUri -> "shape"
    is VaultError.InvalidSecret -> "secret"
}

private fun roundTrip(original: OtpAuthUri) {
    assertEquals(original, parsed(original.build()))
}

class OtpAuthUriTest {
    @Test
    fun `takes the issuer from the label prefix when no parameter is present`() {
        val uri = parsed("otpauth://totp/GitHub:alice?secret=$SECRET")
        assertEquals("GitHub", uri.issuer)
    }

    @Test
    fun `takes the account name from after the label separator`() {
        val uri = parsed("otpauth://totp/GitHub:alice?secret=$SECRET")
        assertEquals("alice", uri.accountName)
    }

    @Test
    fun `takes the issuer from the parameter when the label carries none`() {
        val uri = parsed("otpauth://totp/alice?secret=$SECRET&issuer=GitHub")
        assertEquals("GitHub", uri.issuer)
    }

    @Test
    fun `a label without a separator is the whole account name`() {
        val uri = parsed("otpauth://totp/alice?secret=$SECRET&issuer=GitHub")
        assertEquals("alice", uri.accountName)
    }

    @Test
    fun `the issuer parameter wins over a conflicting label prefix`() {
        val uri = parsed("otpauth://totp/Gitlab:alice?secret=$SECRET&issuer=GitHub")
        assertEquals("GitHub", uri.issuer)
    }

    @Test
    fun `a percent-encoded colon separates the label`() {
        val uri = parsed("otpauth://totp/Big%20Corporation%3A%20alice%40bigco.com?secret=$SECRET")
        assertEquals("Big Corporation", uri.issuer)
    }

    @Test
    fun `whitespace after the label separator is trimmed from the account name`() {
        val uri = parsed("otpauth://totp/Big%20Corporation%3A%20alice%40bigco.com?secret=$SECRET")
        assertEquals("alice@bigco.com", uri.accountName)
    }

    @Test
    fun `percent-encoded spaces survive in the account name`() {
        val uri = parsed("otpauth://totp/Provider1:Alice%20Smith?secret=$SECRET")
        assertEquals("Alice Smith", uri.accountName)
    }

    @Test
    fun `a multi-byte percent-encoded sequence decodes as UTF-8`() {
        val uri = parsed("otpauth://totp/%C3%9Cbercorp:alice?secret=$SECRET")
        assertEquals("Übercorp", uri.issuer)
    }

    @Test
    fun `a truncated escape sequence in the label is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice%2?secret=$SECRET"))
    }

    @Test
    fun `a non-hexadecimal escape sequence in the label is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/ali%zzce?secret=$SECRET"))
    }

    @Test
    fun `a missing secret is an invalid secret`() {
        assertIs<VaultError.InvalidSecret>(errorOf("otpauth://totp/alice?issuer=GitHub"))
    }

    @Test
    fun `an empty secret is an invalid secret`() {
        assertIs<VaultError.InvalidSecret>(errorOf("otpauth://totp/alice?secret="))
    }

    @Test
    fun `an undecodable secret is an invalid secret`() {
        assertIs<VaultError.InvalidSecret>(errorOf("otpauth://totp/alice?secret=NOT!BASE32"))
    }

    @Test
    fun `an unknown type is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://steam/alice?secret=$SECRET"))
    }

    @Test
    fun `a URI with another scheme is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("https://totp/alice?secret=$SECRET"))
    }

    @Test
    fun `an empty label is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/?secret=$SECRET"))
    }

    @Test
    fun `the scheme and type are matched case-insensitively`() {
        assertEquals(OtpType.TOTP, parsed("OTPAUTH://TOTP/alice?secret=$SECRET").type)
    }

    @Test
    fun `the algorithm defaults to SHA-1`() {
        assertEquals(HashAlgorithm.SHA1, parsed("otpauth://totp/alice?secret=$SECRET").algorithm)
    }

    @Test
    fun `the algorithm is matched case-insensitively`() {
        assertEquals(HashAlgorithm.SHA256, parsed("otpauth://totp/alice?secret=$SECRET&algorithm=sha256").algorithm)
    }

    @Test
    fun `an unknown algorithm is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice?secret=$SECRET&algorithm=MD5"))
    }

    @Test
    fun `digits default to six`() {
        assertEquals(6, parsed("otpauth://totp/alice?secret=$SECRET").digits)
    }

    @Test
    fun `eight digits are accepted`() {
        assertEquals(8, parsed("otpauth://totp/alice?secret=$SECRET&digits=8").digits)
    }

    @Test
    fun `digits below six are rejected rather than clamped`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice?secret=$SECRET&digits=5"))
    }

    @Test
    fun `digits above eight are rejected rather than clamped`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice?secret=$SECRET&digits=9"))
    }

    @Test
    fun `a non-numeric digits value is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice?secret=$SECRET&digits=six"))
    }

    @Test
    fun `the period defaults to thirty seconds`() {
        assertEquals(30, parsed("otpauth://totp/alice?secret=$SECRET").period)
    }

    @Test
    fun `a period below the minimum is rejected`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice?secret=$SECRET&period=0"))
    }

    @Test
    fun `a totp URI carrying a counter is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice?secret=$SECRET&counter=1"))
    }

    @Test
    fun `a hotp URI without a counter is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://hotp/alice?secret=$SECRET"))
    }

    @Test
    fun `a hotp URI carries no period`() {
        assertNull(parsed("otpauth://hotp/alice?secret=$SECRET&counter=1&period=60").period)
    }

    @Test
    fun `a period on a hotp URI is ignored rather than rejected`() {
        assertEquals(1uL, parsed("otpauth://hotp/alice?secret=$SECRET&counter=1&period=60").counter)
    }

    @Test
    fun `a counter of zero is accepted`() {
        assertEquals(0uL, parsed("otpauth://hotp/alice?secret=$SECRET&counter=0").counter)
    }

    @Test
    fun `a counter at the 64-bit maximum is accepted`() {
        assertEquals(
            ULong.MAX_VALUE,
            parsed("otpauth://hotp/alice?secret=$SECRET&counter=18446744073709551615").counter,
        )
    }

    @Test
    fun `a counter beyond the 64-bit maximum is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://hotp/alice?secret=$SECRET&counter=18446744073709551616"))
    }

    @Test
    fun `a negative counter is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://hotp/alice?secret=$SECRET&counter=-1"))
    }

    @Test
    fun `unknown parameters are ignored`() {
        assertEquals("alice", parsed("otpauth://totp/alice?secret=$SECRET&image=https%3A%2F%2Fx.example").accountName)
    }

    @Test
    fun `a repeated parameter is malformed`() {
        // The two values disagree about the credential itself, and taking either one imports an
        // account that generates codes no server accepts with nothing on screen to say why.
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice?secret=$SECRET&secret=$OTHER_SECRET"))
    }

    @Test
    fun `a parameter repeated in another case is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice?secret=$SECRET&SECRET=$OTHER_SECRET"))
    }

    @Test
    fun `a repeated parameter that is not the secret is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice?secret=$SECRET&digits=6&digits=8"))
    }

    @Test
    fun `a parameter name is matched case-insensitively`() {
        // A URI pasted from a mail client or a wiki reaches the field with whatever case it was
        // written in, and the format does not fix one.
        assertEquals("alice", parsed("otpauth://totp/alice?SECRET=$SECRET").accountName)
    }

    @Test
    fun `surrounding whitespace does not prevent parsing`() {
        // What a paste from a chat window or a wrapped mail carries.
        assertEquals("alice", parsed("  otpauth://totp/alice?secret=$SECRET\n").accountName)
    }

    @Test
    fun `a fragment is ignored`() {
        assertEquals("alice", parsed("otpauth://totp/alice?secret=$SECRET#note").accountName)
    }

    @Test
    fun `the secret does not appear in the toString`() {
        assertFalse(SECRET in parsed("otpauth://totp/alice?secret=$SECRET").toString())
    }

    @Test
    fun `the toString still names the account`() {
        assertTrue("alice" in parsed("otpauth://totp/alice?secret=$SECRET").toString())
    }

    @Test
    fun `a default-valued totp URI omits algorithm, digits and period`() {
        val built = OtpAuthUri(OtpType.TOTP, "alice", SECRET, issuer = "GitHub").build()
        assertEquals("otpauth://totp/GitHub:alice?secret=$SECRET&issuer=GitHub", built)
    }

    @Test
    fun `a hotp URI always carries its counter`() {
        val built = OtpAuthUri(OtpType.HOTP, "alice", SECRET, period = null, counter = 7uL).build()
        assertEquals("otpauth://hotp/alice?secret=$SECRET&counter=7", built)
    }

    @Test
    fun `a label holding an unpaired surrogate is malformed`() {
        // Accepting it stores an account that cannot be exported; the failure would surface later,
        // at QR-display time, with nothing the user could do about it.
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/al\uD800ice?secret=$SECRET"))
    }

    @Test
    fun `a label with two colons is malformed rather than a thrown exception`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/A:B:C?secret=$SECRET"))
    }

    @Test
    fun `a label beginning with the separator is malformed`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/:alice?secret=$SECRET"))
    }

    @Test
    fun `an issuer parameter may contain a colon`() {
        // The colon restriction binds the label prefix, not the parameter.
        assertEquals("A:B", parsed("otpauth://totp/alice?secret=$SECRET&issuer=A%3AB").issuer)
    }

    @Test
    fun `an issuer containing a colon round-trips through the parameter alone`() {
        roundTrip(OtpAuthUri(OtpType.TOTP, "alice", SECRET, issuer = "Acme: Security"))
    }

    @Test
    fun `an account name beginning with a space round-trips`() {
        roundTrip(OtpAuthUri(OtpType.TOTP, " alice", SECRET, issuer = "Acme"))
    }

    @Test
    fun `an issuer ending in a space survives the label prefix`() {
        assertEquals("Iss ", parsed("otpauth://totp/Iss%20:alice?secret=$SECRET&issuer=Iss%20").issuer)
    }

    @Test
    fun `only literal spaces are absorbed after the separator`() {
        assertEquals("\talice", parsed("otpauth://totp/Iss:%09alice?secret=$SECRET").accountName)
    }

    @Test
    fun `a non-ASCII digit is not accepted as a counter`() {
        // U+0665 ARABIC-INDIC FIVE resolves through Character.digit but is not the ABNF's DIGIT.
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://hotp/alice?secret=$SECRET&counter=%D9%A5"))
    }

    @Test
    fun `a signed period is rejected`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice?secret=$SECRET&period=%2B30"))
    }

    @Test
    fun `an hourly period is accepted`() {
        assertEquals(3600, parsed("otpauth://totp/alice?secret=$SECRET&period=3600").period)
    }

    @Test
    fun `seven digits are accepted`() {
        // RFC 4226 and draft-linuxgemini-otpauth-uri both allow 6..8.
        assertEquals(7, parsed("otpauth://totp/alice?secret=$SECRET&digits=7").digits)
    }

    @Test
    fun `an account name containing a colon is rejected at construction`() {
        // The colon is the label separator, so building anyway produces a URI that re-parses to a
        // different account with an issuer that was never there.
        assertFailsWith<IllegalArgumentException> { OtpAuthUri(OtpType.TOTP, "alice:bob", SECRET) }
    }

    @Test
    fun `an account name that is only a space round-trips`() {
        // The ABNF does not constrain accountname, so this is legal if unusual; what matters is
        // that it survives a build and a parse rather than being silently renamed.
        roundTrip(OtpAuthUri(OtpType.TOTP, " ", SECRET))
    }

    @Test
    fun `an empty issuer is rejected rather than silently dropped`() {
        assertFailsWith<IllegalArgumentException> { OtpAuthUri(OtpType.TOTP, "alice", SECRET, issuer = "") }
    }

    @Test
    fun `a surrounding-space account name round-trips`() {
        roundTrip(OtpAuthUri(OtpType.TOTP, "alice b", SECRET))
    }

    @Test
    fun `a label that is only a space is that account name`() {
        assertEquals(" ", parsed("otpauth://totp/%20?secret=$SECRET").accountName)
    }

    @Test
    fun `an algorithm written with a long s is malformed`() {
        // U+017F percent-encoded. Unicode case folding maps it onto ASCII 'S', which would import
        // this account as SHA-256 from a URI no SHA-256 producer wrote.
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/alice?secret=$SECRET&algorithm=%C5%BFHA256"))
    }

    @Test
    fun `a raw space in the label prefix is part of the issuer`() {
        // What issuers emit and scanners hand back verbatim.
        assertEquals("ACME Corp", parsed("otpauth://totp/ACME Corp:alice@acme.com?secret=$SECRET").issuer)
    }

    @Test
    fun `a raw space in the label is part of the account name`() {
        assertEquals("alice smith", parsed("otpauth://totp/ACME Corp:alice smith?secret=$SECRET").accountName)
    }

    @Test
    fun `a raw space in the query is malformed even when the label holds one`() {
        assertIs<VaultError.MalformedUri>(errorOf("otpauth://totp/ACME Corp:alice?secret=$SECRET&issuer=A B"))
    }

    @Test
    fun `a trailing space is shed rather than kept by the last parameter`() {
        assertEquals("Iss", parsed("otpauth://totp/alice?secret=$SECRET&issuer=Iss ").issuer)
    }

    @Test
    fun `a Unicode line separator does not wrap the URI`() {
        // Shedding it would let a character no producer emits stand in for the surrounding
        // whitespace of a paste.
        assertIs<VaultError.MalformedUri>(errorOf(LINE_SEPARATOR + "otpauth://totp/alice?secret=$SECRET"))
    }

    @Test
    fun `an empty secret is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { OtpAuthUri(OtpType.TOTP, "alice", "") }
    }

    @Test
    fun `a secret of one base32 symbol is rejected at construction`() {
        // A trailing group of one symbol carries five bits and ends no encoding, so it is a group
        // that lost characters on the way here.
        assertFailsWith<IllegalArgumentException> { OtpAuthUri(OtpType.TOTP, "alice", "A") }
    }

    @Test
    fun `a secret of whitespace alone is rejected at construction`() {
        // Whitespace is skipped, so this is a whole number of groups carrying no symbol at all: it
        // clears every rule about shape and decodes to none of the bytes an HMAC key needs.
        assertFailsWith<IllegalArgumentException> { OtpAuthUri(OtpType.TOTP, "alice", " \t") }
    }

    @Test
    fun `a secret outside the base32 alphabet is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { OtpAuthUri(OtpType.TOTP, "alice", "1") }
    }

    @Test
    fun `an account name holding an unpaired surrogate is rejected at construction`() {
        // A lone surrogate has no UTF-8 encoding, so build() could not percent-encode it.
        assertFailsWith<IllegalArgumentException> { OtpAuthUri(OtpType.TOTP, "al\uD800ice", SECRET) }
    }

    @Test
    fun `an issuer holding an unpaired surrogate is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { OtpAuthUri(OtpType.TOTP, "alice", SECRET, issuer = "A\uDC00cme") }
    }

    @Test
    fun `round-trips a minimal totp entry`() {
        roundTrip(OtpAuthUri(OtpType.TOTP, "alice", SECRET))
    }

    @Test
    fun `round-trips a totp entry with an issuer`() {
        roundTrip(OtpAuthUri(OtpType.TOTP, "alice@example.com", SECRET, issuer = "GitHub"))
    }

    @Test
    fun `round-trips a totp entry with SHA-256 and eight digits`() {
        roundTrip(
            OtpAuthUri(
                type = OtpType.TOTP,
                accountName = "alice",
                secret = SECRET,
                issuer = "GitHub",
                algorithm = HashAlgorithm.SHA256,
                digits = 8,
            ),
        )
    }

    @Test
    fun `round-trips a totp entry with SHA-512 and a non-default period`() {
        roundTrip(
            OtpAuthUri(
                type = OtpType.TOTP,
                accountName = "alice",
                secret = SECRET,
                issuer = "GitHub",
                algorithm = HashAlgorithm.SHA512,
                digits = 7,
                period = 60,
            ),
        )
    }

    @Test
    fun `round-trips a totp entry whose issuer and account name need escaping`() {
        roundTrip(OtpAuthUri(OtpType.TOTP, "alice smith@bigco.com", SECRET, issuer = "Big Corporation"))
    }

    @Test
    fun `round-trips a totp entry with non-ASCII names`() {
        roundTrip(OtpAuthUri(OtpType.TOTP, "álice", SECRET, issuer = "Übercorp ✓"))
    }

    @Test
    fun `round-trips a minimal hotp entry`() {
        roundTrip(OtpAuthUri(OtpType.HOTP, "alice", SECRET, period = null, counter = 0uL))
    }

    @Test
    fun `round-trips a hotp entry at the 64-bit counter maximum`() {
        roundTrip(
            OtpAuthUri(
                type = OtpType.HOTP,
                accountName = "alice",
                secret = SECRET,
                issuer = "Acme",
                algorithm = HashAlgorithm.SHA512,
                digits = 8,
                period = null,
                counter = ULong.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `a string that is not an otpauth URI reports through the view a parse declares`() {
        assertEquals("shape", uriCase(checkNotNull(errorOf("https://example.com/totp/alice"))))
    }

    @Test
    fun `a URI carrying no secret reports through the view a parse declares`() {
        assertEquals("secret", uriCase(checkNotNull(errorOf("otpauth://totp/alice"))))
    }
}
