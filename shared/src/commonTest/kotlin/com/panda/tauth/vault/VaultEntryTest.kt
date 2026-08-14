package com.panda.tauth.vault

import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpType
import com.panda.tauth.totp.Totp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

internal const val TEST_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
internal val CREATED_AT = Instant.parse("2026-08-13T09:41:12Z")

internal fun totpEntry(
    id: String = "0192f4c1-0000-7000-8000-000000000001",
    orderIndex: Int = 0,
    accountName: String = "alice",
) = VaultEntry(
    id = id,
    type = OtpType.TOTP,
    accountName = accountName,
    secret = TEST_SECRET,
    createdAt = CREATED_AT,
    issuer = "GitHub",
    period = Totp.PERIOD_DEFAULT,
    orderIndex = orderIndex,
)

internal fun hotpEntry(
    id: String = "0192f4c1-0000-7000-8000-000000000002",
    counter: ULong = 0uL,
    orderIndex: Int = 0,
) = VaultEntry(
    id = id,
    type = OtpType.HOTP,
    accountName = "bob",
    secret = TEST_SECRET,
    createdAt = CREATED_AT,
    counter = counter,
    orderIndex = orderIndex,
)

class VaultEntryTest {
    @Test
    fun `a totp entry without a period is rejected`() {
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(period = null) }
    }

    @Test
    fun `a totp entry carrying a counter is rejected`() {
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(counter = 1uL) }
    }

    @Test
    fun `a hotp entry without a counter is rejected`() {
        assertFailsWith<IllegalArgumentException> { hotpEntry().copy(counter = null) }
    }

    @Test
    fun `a hotp entry carrying a period is rejected`() {
        assertFailsWith<IllegalArgumentException> { hotpEntry().copy(period = Totp.PERIOD_DEFAULT) }
    }

    @Test
    fun `a period of zero is rejected`() {
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(period = 0) }
    }

    @Test
    fun `an hourly period is accepted`() {
        // RFC 6238 sets no upper bound on the time step.
        assertEquals(3600, totpEntry().copy(period = 3600).period)
    }

    @Test
    fun `digits outside the accepted range are rejected`() {
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(digits = 9) }
    }

    @Test
    fun `a digits count of zero is rejected`() {
        // Truncation takes 10^digits, so zero digits is the empty code for every secret and every
        // step. The accepted range is 6 to 8, rejected rather than clamped at either end.
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(digits = 0) }
    }

    @Test
    fun `five digits are rejected`() {
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(digits = 5) }
    }

    @Test
    fun `six digits are accepted`() {
        // RFC 4226 §5.3 puts the floor at six digits.
        assertEquals(6, totpEntry().copy(digits = 6).digits)
    }

    @Test
    fun `eight digits are accepted`() {
        assertEquals(8, totpEntry().copy(digits = 8).digits)
    }

    @Test
    fun `an empty id is rejected`() {
        // The id is what a rename, a reorder and a delete address; an empty one addresses whichever
        // other entry also has none.
        assertFailsWith<IllegalArgumentException> { totpEntry(id = "") }
    }

    @Test
    fun `an empty account name is rejected`() {
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(accountName = "") }
    }

    @Test
    fun `an empty secret is rejected`() {
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(secret = "") }
    }

    @Test
    fun `a secret of only whitespace is rejected`() {
        // Base32 skips whitespace, so this decodes to a key of no bytes, which HMAC refuses at
        // code-generation time — a whole session after the entry was stored.
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(secret = " ") }
    }

    @Test
    fun `a secret outside the base32 alphabet is rejected`() {
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(secret = "NOT!BASE32") }
    }

    @Test
    fun `an account name holding the label separator is rejected`() {
        // The colon splits a URI label, so exporting this entry produces a URI that reads back as
        // account "alice" under an issuer "work" nobody entered.
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(accountName = "work:alice") }
    }

    @Test
    fun `an empty issuer is rejected rather than stored as a second spelling of absence`() {
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(issuer = "") }
    }

    @Test
    fun `an account name holding an unpaired surrogate is rejected`() {
        // A lone surrogate has no UTF-8 encoding, so the entry could never be exported as a URI.
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(accountName = "al\uD800ice") }
    }

    @Test
    fun `an issuer holding an unpaired surrogate is rejected`() {
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(issuer = "Git\uDC00Hub") }
    }

    @Test
    fun `a negative order index is rejected`() {
        assertFailsWith<IllegalArgumentException> { totpEntry().copy(orderIndex = -1) }
    }

    @Test
    fun `the secret does not appear in the toString`() {
        assertFalse(TEST_SECRET in totpEntry().toString())
    }

    @Test
    fun `the toString still names the account`() {
        assertTrue("alice" in totpEntry().toString())
    }

    @Test
    fun `a totp entry round-trips through JSON`() {
        val entry = totpEntry()
        assertEquals(entry, vaultJson.decodeFromString<VaultEntry>(vaultJson.encodeToString(entry)))
    }

    @Test
    fun `a hotp entry at the 64-bit counter maximum round-trips through JSON`() {
        val entry = hotpEntry(counter = ULong.MAX_VALUE)
        assertEquals(entry, vaultJson.decodeFromString<VaultEntry>(vaultJson.encodeToString(entry)))
    }

    @Test
    fun `a totp entry omits its null counter when written`() {
        assertFalse("counter" in vaultJson.encodeToString(totpEntry()))
    }

    @Test
    fun `a hotp entry omits its null period when written`() {
        assertFalse("period" in vaultJson.encodeToString(hotpEntry()))
    }

    @Test
    fun `deserialising a hotp entry with no counter fails rather than defaulting to zero`() {
        val json = """
            {"id":"a","type":"hotp","accountName":"bob","secret":"$TEST_SECRET",
             "createdAt":"2026-08-13T09:41:12Z"}
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> { vaultJson.decodeFromString<VaultEntry>(json) }
    }

    @Test
    fun `deserialising a totp entry with no period fails`() {
        val json = """
            {"id":"a","type":"totp","accountName":"alice","secret":"$TEST_SECRET",
             "createdAt":"2026-08-13T09:41:12Z"}
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> { vaultJson.decodeFromString<VaultEntry>(json) }
    }

    @Test
    fun `deserialising an entry whose account name holds an unpaired surrogate fails`() {
        // JSON carries \ud800 through as readily as any other escape, and the decoded string has no
        // UTF-8 encoding.
        val json = """
            {"id":"a","type":"totp","accountName":"al\ud800ice","secret":"$TEST_SECRET",
             "createdAt":"2026-08-13T09:41:12Z","period":30}
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> { vaultJson.decodeFromString<VaultEntry>(json) }
    }

    @Test
    fun `deserialising an entry whose account name holds the label separator fails`() {
        // The body is attacker-writable, and an entry the URI constructor would refuse reaches that
        // constructor at export time, where the failure is a throw rather than a returned error.
        val json = """
            {"id":"a","type":"totp","accountName":"work:alice","secret":"$TEST_SECRET",
             "createdAt":"2026-08-13T09:41:12Z","period":30}
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> { vaultJson.decodeFromString<VaultEntry>(json) }
    }

    @Test
    fun `deserialising an entry whose secret decodes to no key fails`() {
        val json = """
            {"id":"a","type":"totp","accountName":"alice","secret":" ",
             "createdAt":"2026-08-13T09:41:12Z","period":30}
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> { vaultJson.decodeFromString<VaultEntry>(json) }
    }

    @Test
    fun `an unknown entry key is ignored`() {
        val json = """
            {"id":"a","type":"totp","accountName":"alice","secret":"$TEST_SECRET",
             "createdAt":"2026-08-13T09:41:12Z","period":30,"colour":"red"}
        """.trimIndent()
        assertEquals("alice", vaultJson.decodeFromString<VaultEntry>(json).accountName)
    }

    @Test
    fun `the algorithm defaults to SHA-1 when absent`() {
        val json = """
            {"id":"a","type":"totp","accountName":"alice","secret":"$TEST_SECRET",
             "createdAt":"2026-08-13T09:41:12Z","period":30}
        """.trimIndent()
        assertEquals(HashAlgorithm.SHA1, vaultJson.decodeFromString<VaultEntry>(json).algorithm)
    }

    @Test
    fun `the type is written in lower case`() {
        assertTrue("\"type\":\"totp\"" in vaultJson.encodeToString(totpEntry()))
    }

    @Test
    fun `an id is a canonical UUID`() {
        // Ordering and uniqueness are Uuid.generateV7's contract, not this project's; what is worth
        // pinning is that the id reaches the body in canonical form rather than as some other shape.
        assertTrue(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(VaultEntry.newId()))
    }

    @Test
    fun `an id carries the version 7 nibble`() {
        assertEquals('7', VaultEntry.newId()[14])
    }
}
