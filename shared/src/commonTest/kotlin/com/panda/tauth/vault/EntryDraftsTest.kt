package com.panda.tauth.vault

import com.panda.tauth.errorOrNull
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpType
import com.panda.tauth.valueOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private val TOTP_DRAFT = EntryDraft(
    type = OtpType.TOTP,
    issuer = "GitHub",
    accountName = "alice",
    secret = TEST_SECRET,
)

private val HOTP_DRAFT = EntryDraft(
    type = OtpType.HOTP,
    issuer = "Zendesk",
    accountName = "bob",
    secret = TEST_SECRET,
    period = "",
    counter = "41",
)

private val TOTP_EDIT = EntryEditDraft(
    issuer = "GitHub",
    accountName = "alice",
    algorithm = HashAlgorithm.SHA1,
    digits = "6",
    period = "30",
    counter = "",
)

class EntryDraftsTest {
    @Test
    fun `a filled totp form resolves to an account under its issuer`() {
        assertEquals("GitHub", TOTP_DRAFT.resolved().valueOrNull?.issuer)
    }

    @Test
    fun `a filled totp form carries the account name that was typed`() {
        assertEquals("alice", TOTP_DRAFT.resolved().valueOrNull?.accountName)
    }

    @Test
    fun `a filled totp form carries the secret that was typed`() {
        assertEquals(TEST_SECRET, TOTP_DRAFT.resolved().valueOrNull?.secret)
    }

    @Test
    fun `a filled totp form carries the type that was chosen`() {
        assertEquals(OtpType.TOTP, TOTP_DRAFT.resolved().valueOrNull?.type)
    }

    @Test
    fun `a filled hotp form carries the type that was chosen`() {
        assertEquals(OtpType.HOTP, HOTP_DRAFT.resolved().valueOrNull?.type)
    }

    @Test
    fun `a filled totp form carries the period that was typed`() {
        assertEquals(60, TOTP_DRAFT.copy(period = "60").resolved().valueOrNull?.period)
    }

    @Test
    fun `a filled totp form carries the digit count that was typed`() {
        assertEquals(8, TOTP_DRAFT.copy(digits = "8").resolved().valueOrNull?.digits)
    }

    @Test
    fun `a filled totp form carries the algorithm that was chosen`() {
        val resolved = TOTP_DRAFT.copy(algorithm = HashAlgorithm.SHA512).resolved()

        assertEquals(HashAlgorithm.SHA512, resolved.valueOrNull?.algorithm)
    }

    // An issuer left blank is an absent issuer. An empty one does not survive a URI, which is why the
    // entry model admits one spelling of absence and not two.
    @Test
    fun `a blank issuer resolves to no issuer`() {
        assertNull(TOTP_DRAFT.copy(issuer = "").resolved().valueOrNull?.issuer)
    }

    @Test
    fun `a totp form carries no counter`() {
        assertNull(TOTP_DRAFT.copy(counter = "9").resolved().valueOrNull?.counter)
    }

    @Test
    fun `a filled hotp form carries the counter that was typed`() {
        assertEquals(41uL, HOTP_DRAFT.resolved().valueOrNull?.counter)
    }

    @Test
    fun `an hotp form carries no period`() {
        assertNull(HOTP_DRAFT.copy(period = "30").resolved().valueOrNull?.period)
    }

    @Test
    fun `an hotp counter at the unsigned maximum resolves`() {
        val resolved = HOTP_DRAFT.copy(counter = "18446744073709551615").resolved()

        assertEquals(ULong.MAX_VALUE, resolved.valueOrNull?.counter)
    }

    @Test
    fun `a counter past the unsigned maximum is refused`() {
        val resolved = HOTP_DRAFT.copy(counter = "18446744073709551616").resolved()

        assertIs<VaultError.InvalidEntry>(resolved.errorOrNull)
    }

    @Test
    fun `a half-typed period is refused rather than read as a number`() {
        assertIs<VaultError.InvalidEntry>(TOTP_DRAFT.copy(period = "").resolved().errorOrNull)
    }

    @Test
    fun `a half-typed digit count is refused`() {
        assertIs<VaultError.InvalidEntry>(TOTP_DRAFT.copy(digits = "").resolved().errorOrNull)
    }

    @Test
    fun `an empty account name is refused`() {
        assertIs<VaultError.InvalidEntry>(TOTP_DRAFT.copy(accountName = "").resolved().errorOrNull)
    }

    @Test
    fun `an account name carrying a colon is refused`() {
        assertIs<VaultError.InvalidEntry>(TOTP_DRAFT.copy(accountName = "work:alice").resolved().errorOrNull)
    }

    @Test
    fun `a secret that is not base32 is refused`() {
        assertIs<VaultError.InvalidEntry>(TOTP_DRAFT.copy(secret = "not base32!").resolved().errorOrNull)
    }

    @Test
    fun `a digit count outside the permitted range is refused`() {
        assertIs<VaultError.InvalidEntry>(TOTP_DRAFT.copy(digits = "9").resolved().errorOrNull)
    }

    // The refusal reaches a screen and a log line, so it names the rule the value broke rather than
    // the value, which for a secret is the credential itself.
    @Test
    fun `a refused secret is not quoted back in the refusal`() {
        val error = assertIs<VaultError.InvalidEntry>(TOTP_DRAFT.copy(secret = "AAAA!").resolved().errorOrNull)

        assertEquals(false, "AAAA!" in error.detail)
    }

    @Test
    fun `an add form refusal is typed at the only case resolving one reports`() {
        val refusal: VaultError.InvalidEntry = checkNotNull(TOTP_DRAFT.copy(period = "").resolved().errorOrNull)

        assertEquals("the period must be a whole number of seconds", refusal.detail)
    }

    @Test
    fun `a secret check refusal is typed at the only case the check reports`() {
        val refusal: VaultError.InvalidSecret = checkNotNull(TOTP_DRAFT.copy(secret = "not base32!").secretProblem())

        assertEquals("invalid base32 character", refusal.detail)
    }

    @Test
    fun `an edit form refusal is typed at the only case resolving one reports`() {
        val refusal: VaultError.InvalidEntry = checkNotNull(TOTP_EDIT.resolved(OtpType.HOTP).errorOrNull)

        assertEquals("the counter must be a whole number", refusal.detail)
    }

    @Test
    fun `an edit form carries the account name that was typed`() {
        assertEquals("erin", TOTP_EDIT.copy(accountName = "erin").resolved(OtpType.TOTP).valueOrNull?.accountName)
    }

    @Test
    fun `an edit form carries the issuer that was typed`() {
        assertEquals("Example", TOTP_EDIT.copy(issuer = "Example").resolved(OtpType.TOTP).valueOrNull?.issuer)
    }

    @Test
    fun `an edit form with a blank issuer resolves to no issuer`() {
        assertNull(TOTP_EDIT.copy(issuer = "").resolved(OtpType.TOTP).valueOrNull?.issuer)
    }

    @Test
    fun `an edit form carries the period that was typed`() {
        assertEquals(60, TOTP_EDIT.copy(period = "60").resolved(OtpType.TOTP).valueOrNull?.period)
    }

    @Test
    fun `an edit form carries the digit count that was typed`() {
        assertEquals(8, TOTP_EDIT.copy(digits = "8").resolved(OtpType.TOTP).valueOrNull?.digits)
    }

    @Test
    fun `an edit form carries the algorithm that was chosen`() {
        val resolved = TOTP_EDIT.copy(algorithm = HashAlgorithm.SHA256).resolved(OtpType.TOTP)

        assertEquals(HashAlgorithm.SHA256, resolved.valueOrNull?.algorithm)
    }

    // The type is the entry's rather than the form's, so an edit against an hotp entry reads the
    // counter and leaves the period alone whatever the form still holds in it.
    @Test
    fun `an edit against an hotp entry carries the counter`() {
        val resolved = TOTP_EDIT.copy(counter = "512").resolved(OtpType.HOTP)

        assertEquals(512uL, resolved.valueOrNull?.counter)
    }

    @Test
    fun `an edit against an hotp entry carries no period`() {
        assertNull(TOTP_EDIT.copy(counter = "512").resolved(OtpType.HOTP).valueOrNull?.period)
    }

    @Test
    fun `an edit against a totp entry carries no counter`() {
        assertNull(TOTP_EDIT.copy(counter = "512").resolved(OtpType.TOTP).valueOrNull?.counter)
    }

    @Test
    fun `an edit with a half-typed counter against an hotp entry is refused`() {
        assertIs<VaultError.InvalidEntry>(TOTP_EDIT.resolved(OtpType.HOTP).errorOrNull)
    }

    // The draft is printed by anything that logs a screen's state, and the secret is a complete
    // credential.
    @Test
    fun `no rendering of a draft carries the secret`() {
        assertEquals(false, TEST_SECRET in TOTP_DRAFT.toString())
    }
}
