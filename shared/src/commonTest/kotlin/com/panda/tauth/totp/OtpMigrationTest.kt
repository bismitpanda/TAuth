package com.panda.tauth.totp

import com.panda.tauth.Outcome
import com.panda.tauth.crypto.base64Encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun read(bytes: ByteArray): MigrationBatch =
    (readMigration(migrationUri(bytes)) as Outcome.Success<MigrationBatch>).value

private fun one(vararg accounts: ByteArray): MigrationAccount = read(migrationPayload(*accounts)).accounts.single()

class OtpMigrationTest {
    @Test
    fun `an export code is recognized by its scheme`() {
        assertTrue(isMigrationUri("otpauth-migration://offline?data=AA"))
    }

    @Test
    fun `an account uri is not an export code`() {
        assertFalse(isMigrationUri("otpauth://totp/GitHub:alice?secret=GEZDGNBVGY3TQOJQ"))
    }

    @Test
    fun `the seed the payload carried comes back as the base32 a vault entry stores`() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", one(migrationAccount()).secret)
    }

    @Test
    fun `an account carries the name the payload gave it`() {
        assertEquals("alice", one(migrationAccount()).accountName)
    }

    @Test
    fun `an account carries the issuer the payload gave it`() {
        assertEquals("GitHub", one(migrationAccount()).issuer)
    }

    @Test
    fun `a label naming its issuer is split where the issuer field is empty`() {
        val parsed = one(migrationAccount(name = "GitHub: alice", issuer = null))

        assertEquals("GitHub" to "alice", parsed.issuer to parsed.accountName)
    }

    @Test
    fun `the issuer field wins over the one the label names`() {
        assertEquals("Vercel", one(migrationAccount(name = "GitHub: alice", issuer = "Vercel")).issuer)
    }

    @Test
    fun `an account under no issuer at all carries none`() {
        assertNull(one(migrationAccount(issuer = null)).issuer)
    }

    @Test
    fun `an unspecified digest reads as sha1`() {
        assertEquals(HashAlgorithm.SHA1, one(migrationAccount()).algorithm)
    }

    @Test
    fun `a named digest reads as itself`() {
        assertEquals(HashAlgorithm.SHA256, one(migrationAccount(algorithm = MIGRATION_ALGORITHM_SHA256)).algorithm)
    }

    @Test
    fun `a digest TAuth does not generate leaves the account without one`() {
        assertNull(one(migrationAccount(algorithm = MIGRATION_ALGORITHM_MD5)).algorithm)
    }

    @Test
    fun `an unspecified digit count reads as six`() {
        assertEquals(OtpCore.DIGITS_DEFAULT, one(migrationAccount()).digits)
    }

    @Test
    fun `an eight digit account reads as eight`() {
        assertEquals(OtpCore.DIGITS_MAX, one(migrationAccount(digits = MIGRATION_DIGITS_EIGHT)).digits)
    }

    @Test
    fun `a totp account reads as totp`() {
        assertEquals(OtpType.TOTP, one(migrationAccount()).type)
    }

    @Test
    fun `a hotp account carries the counter the payload gave it`() {
        val parsed = one(migrationAccount(type = MIGRATION_TYPE_HOTP, counter = 42))

        assertEquals(OtpType.HOTP to 42uL, parsed.type to parsed.counter)
    }

    @Test
    fun `every account in one code is read`() {
        val three =
            migrationPayload(migrationAccount(), migrationAccount(name = "bob"), migrationAccount(name = "carol"))

        assertEquals(3, read(three).accounts.size)
    }

    @Test
    fun `a code naming no batch is the whole export`() {
        assertEquals(1 to 1, read(migrationPayload(migrationAccount())).let { it.part to it.parts })
    }

    // The index counts from zero and the part the user is told counts from one.
    @Test
    fun `a code names its place in a split export`() {
        val second = read(migrationPayload(migrationAccount(), size = 3, index = 1))

        assertEquals(2 to 3, second.part to second.parts)
    }

    @Test
    fun `a field this reader does not know is stepped over`() {
        val extra = migrationPayload(migrationAccount()) + migrationField(9, "something a later version writes")

        assertEquals("alice", read(extra).accounts.single().accountName)
    }

    @Test
    fun `text that is not an export code is refused`() {
        assertTrue(readMigration("otpauth-migration://offline") is Outcome.Failure)
    }

    @Test
    fun `export data that is not base64 is refused`() {
        assertTrue(readMigration(MIGRATION_SCHEME + "offline?data=!!!!") is Outcome.Failure)
    }

    @Test
    fun `a truncated payload is refused rather than read as far as it goes`() {
        val whole = migrationPayload(migrationAccount())

        assertTrue(readMigration(migrationUri(whole.copyOfRange(0, whole.size / 2))) is Outcome.Failure)
    }

    @Test
    fun `export data missing its padding is read`() {
        val unpadded = base64Encode(migrationPayload(migrationAccount())).trimEnd('=')

        assertTrue(readMigration(MIGRATION_SCHEME + "offline?data=" + percentEncode(unpadded)) is Outcome.Success)
    }
}
