package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.valueOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Every field an edit can reach on a totp entry, each holding a value the fixture does not, so what
// the invariance cases below assert is left alone is what an edit that moves everything leaves alone.
private val RENAME = EntryEdit(
    accountName = "carol",
    issuer = "Example",
    algorithm = HashAlgorithm.SHA512,
    digits = 8,
    period = 60,
)

// The same for a hotp entry, whose reachable moving factor is the counter rather than the period.
private val RENAME_HOTP = EntryEdit(
    accountName = "carol",
    issuer = "Example",
    algorithm = HashAlgorithm.SHA512,
    digits = 8,
    counter = 900uL,
)

private fun edit(entry: VaultEntry, edit: EntryEdit): VaultEntry = checkNotNull(entry.edited(edit).valueOrNull)

class EntryEditTest {
    @Test
    fun `an edit changes the account name`() {
        assertEquals("carol", edit(totpEntry(), RENAME).accountName)
    }

    @Test
    fun `an edit changes the issuer`() {
        assertEquals("Example", edit(totpEntry(), RENAME).issuer)
    }

    @Test
    fun `an edit changes the algorithm`() {
        assertEquals(HashAlgorithm.SHA512, edit(totpEntry(), RENAME).algorithm)
    }

    @Test
    fun `an edit changes the digit count`() {
        assertEquals(8, edit(totpEntry(), RENAME).digits)
    }

    @Test
    fun `an edit changes the period`() {
        assertEquals(60, edit(totpEntry(), RENAME).period)
    }

    @Test
    fun `an edit sets the counter a resynchronisation needs`() {
        assertEquals(900uL, edit(hotpEntry(counter = 3uL), RENAME_HOTP).counter)
    }

    @Test
    fun `an edit leaves the secret the entry carries`() {
        assertEquals(TEST_SECRET, edit(totpEntry(), RENAME).secret)
    }

    @Test
    fun `an edit leaves the entry's id`() {
        assertEquals(totpEntry().id, edit(totpEntry(), RENAME).id)
    }

    @Test
    fun `an edit leaves the creation time`() {
        assertEquals(CREATED_AT, edit(totpEntry(), RENAME).createdAt)
    }

    @Test
    fun `an edit leaves the order index`() {
        assertEquals(7, edit(totpEntry(orderIndex = 7), RENAME).orderIndex)
    }

    @Test
    fun `an edit leaves the type`() {
        assertEquals(hotpEntry().type, edit(hotpEntry(), RENAME_HOTP).type)
    }

    @Test
    fun `an edit to a digit count outside the range is refused`() {
        val outcome = totpEntry().edited(RENAME.copy(digits = 9))

        assertIs<VaultError.InvalidEntry>(outcome.errorOrNull)
    }

    @Test
    fun `an edit that gives a totp entry a counter is refused`() {
        val outcome = totpEntry().edited(RENAME.copy(counter = 4uL))

        assertIs<VaultError.InvalidEntry>(outcome.errorOrNull)
    }

    @Test
    fun `an edit that drops a hotp entry's counter is refused`() {
        val outcome = hotpEntry().edited(EntryEdit(accountName = "bob"))

        assertIs<VaultError.InvalidEntry>(outcome.errorOrNull)
    }

    @Test
    fun `an edit that gives a hotp entry a period is refused`() {
        val outcome = hotpEntry().edited(EntryEdit(accountName = "bob", counter = 1uL, period = 30))

        assertIs<VaultError.InvalidEntry>(outcome.errorOrNull)
    }

    @Test
    fun `an edit that empties the account name is refused`() {
        val outcome = totpEntry().edited(RENAME.copy(accountName = ""))

        assertIs<VaultError.InvalidEntry>(outcome.errorOrNull)
    }

    @Test
    fun `an edit that puts a colon in the account name is refused`() {
        val outcome = totpEntry().edited(RENAME.copy(accountName = "GitHub:alice"))

        assertIs<VaultError.InvalidEntry>(outcome.errorOrNull)
    }

    @Test
    fun `an edit that empties the issuer is refused`() {
        val outcome = totpEntry().edited(RENAME.copy(issuer = ""))

        assertIs<VaultError.InvalidEntry>(outcome.errorOrNull)
    }

    @Test
    fun `an edit that drops the issuer is accepted`() {
        val outcome = totpEntry().edited(RENAME.copy(issuer = null))

        assertIs<Outcome.Success<VaultEntry>>(outcome)
    }

    @Test
    fun `a refused edit states the rule rather than the value`() {
        val outcome = totpEntry().edited(RENAME.copy(digits = 9))

        assertEquals("digits must be 6..8", (outcome.errorOrNull as? VaultError.InvalidEntry)?.detail)
    }

    @Test
    fun `an edit refusal is typed at the only case an edit reports`() {
        val refused = totpEntry().edited(RENAME.copy(accountName = ""))
        val refusal: VaultError.InvalidEntry = checkNotNull(refused.errorOrNull)

        assertEquals("account name must not be empty", refusal.detail)
    }
}
