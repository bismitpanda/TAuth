package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.valueOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

private val IMPORTED_AT = Instant.parse("2026-08-19T12:00:00Z")

private const val GITHUB_URI = "otpauth://totp/GitHub:alice?secret=$TEST_SECRET&issuer=GitHub"
private const val ZENDESK_URI = "otpauth://hotp/bob?secret=$TEST_SECRET&counter=41"

// The same account as GITHUB_URI with the base32 spelled the other way: padded and lowercase decode
// to the key the entry already holds, so the two are one account however they are written.
private const val GITHUB_URI_RESPELT =
    "otpauth://totp/GitHub:alice?secret=gezdgnbvgy3tqojqgezdgnbvgy3tqojq&issuer=GitHub"

// Ids the reader hands out, in the order it asks for them, so an assertion names which one it means.
private class Ids {
    private var next = 0

    fun newId(): String = "0192f4c1-0000-7000-8000-00000000000${next++}"
}

private fun read(
    text: String,
    existing: List<VaultEntry> = emptyList(),
): Outcome<List<ImportRow>, VaultError.Corrupt> = readAccounts(text, existing, IMPORTED_AT, Ids()::newId)

private fun rowsOf(text: String, existing: List<VaultEntry> = emptyList()): List<ImportRow> =
    checkNotNull(read(text, existing).valueOrNull)

private fun accountsOf(text: String, existing: List<VaultEntry> = emptyList()): List<ImportRow.Account> =
    rowsOf(text, existing).filterIsInstance<ImportRow.Account>()

class ImportRowTest {
    @Test
    fun `a list of uris reads one account a line`() {
        assertEquals(2, accountsOf("$GITHUB_URI\n$ZENDESK_URI\n").size)
    }

    @Test
    fun `a uri reads to the account it names`() {
        assertEquals("alice", accountsOf(GITHUB_URI).single().entry.accountName)
    }

    @Test
    fun `a uri carrying a counter reads to the counter it names`() {
        assertEquals(41uL, accountsOf(ZENDESK_URI).single().entry.counter)
    }

    // A URI has no field for a creation time, so the account is as old as the import that made it.
    @Test
    fun `an account off a uri is created at the moment it is imported`() {
        assertEquals(IMPORTED_AT, accountsOf(GITHUB_URI).single().entry.createdAt)
    }

    @Test
    fun `blank lines are passed over rather than refused`() {
        assertEquals(1, rowsOf("\n\n$GITHUB_URI\n\n").size)
    }

    @Test
    fun `a line that is not a uri is refused on its own`() {
        val rows = rowsOf("$GITHUB_URI\nnot a uri at all\n$ZENDESK_URI")

        assertEquals(listOf(1, 3), rows.filterIsInstance<ImportRow.Account>().map { it.position })
    }

    @Test
    fun `a refused line names where it sat in the file`() {
        val rows = rowsOf("$GITHUB_URI\nnot a uri at all\n")

        assertEquals(2, rows.filterIsInstance<ImportRow.Refused>().single().position)
    }

    // The line holds a credential, so the refusal states the rule the value broke and never the
    // value itself.
    @Test
    fun `a refused line does not quote what it refused`() {
        val rows = rowsOf("otpauth://totp/alice?secret=NOT-BASE-32!")

        assertTrue(TEST_SECRET !in rows.first().toString() && "NOT-BASE-32" !in rows.first().toString())
    }

    @Test
    fun `an empty file offers nothing`() {
        assertEquals(emptyList(), rowsOf(""))
    }

    @Test
    fun `an account the vault already holds is offered as a duplicate`() {
        val existing = totpEntry(accountName = "alice").copy(issuer = "GitHub")

        assertTrue(accountsOf(GITHUB_URI, listOf(existing)).single().isDuplicate)
    }

    @Test
    fun `an account the vault does not hold is not a duplicate`() {
        val existing = totpEntry(accountName = "carol").copy(issuer = "GitHub")

        assertTrue(!accountsOf(GITHUB_URI, listOf(existing)).single().isDuplicate)
    }

    // Padding and case are what differ between two spellings of one key, and neither makes a second
    // account of it.
    @Test
    fun `a secret spelled another way is the same account`() {
        val existing = totpEntry(accountName = "alice").copy(issuer = "GitHub")

        assertTrue(accountsOf(GITHUB_URI_RESPELT, listOf(existing)).single().isDuplicate)
    }

    @Test
    fun `a file carrying one account twice offers the second as a duplicate`() {
        val accounts = accountsOf("$GITHUB_URI\n$GITHUB_URI\n")

        assertEquals(listOf(false, true), accounts.map { it.isDuplicate })
    }

    @Test
    fun `a document reads every account it carries`() {
        assertEquals(2, accountsOf(exportOf(totpEntry(), hotpEntry(orderIndex = 1))).size)
    }

    @Test
    fun `a document keeps the creation time it carries`() {
        assertEquals(CREATED_AT, accountsOf(exportOf(totpEntry())).single().entry.createdAt)
    }

    // The id belongs to the vault the account is arriving in: one carried from elsewhere may already
    // name an entry here.
    @Test
    fun `a document account arrives under an id of this vault's making`() {
        val imported = accountsOf(exportOf(totpEntry())).single().entry

        assertTrue(imported.id != totpEntry().id)
    }

    @Test
    fun `a document account the vault already holds is offered as a duplicate`() {
        assertTrue(accountsOf(exportOf(totpEntry()), listOf(totpEntry())).single().isDuplicate)
    }

    @Test
    fun `an account the document holds in a shape the model refuses is refused on its own`() {
        val document = """{"v":1,"entries":[{"id":"x","type":"totp","accountName":"","secret":"$TEST_SECRET",""" +
            """"createdAt":"2026-08-13T09:41:12Z","period":30}]}"""

        assertIs<ImportRow.Refused>(rowsOf(document).single())
    }

    @Test
    fun `a refused document account states the rule it broke`() {
        val document = """{"v":1,"entries":[{"id":"x","type":"totp","accountName":"","secret":"$TEST_SECRET",""" +
            """"createdAt":"2026-08-13T09:41:12Z","period":30}]}"""

        assertEquals("account name must not be empty", (rowsOf(document).single() as ImportRow.Refused).detail)
    }

    @Test
    fun `an account the document holds in no shape at all is refused on its own`() {
        assertIs<ImportRow.Refused>(rowsOf("""{"v":1,"entries":["not an account"]}""").single())
    }

    @Test
    fun `a file that opens as a document and is not one is unreadable`() {
        assertIs<VaultError.Corrupt>(read("{ this is not json").errorOrNull)
    }

    // The message of a parse failure quotes the document it stopped in, and this one holds every
    // secret in the vault.
    @Test
    fun `an unreadable file does not quote what it was reading`() {
        val error = checkNotNull(read("""{"entries":[{"secret":"$TEST_SECRET"”""").errorOrNull)

        assertTrue(TEST_SECRET !in error.detail)
    }

    @Test
    fun `every account a file offers is accepted by default`() {
        assertEquals(2, rowsOf("$GITHUB_URI\n$ZENDESK_URI\n").accepted(emptySet()).size)
    }

    @Test
    fun `an account the vault holds is left out until it is chosen`() {
        val existing = totpEntry(accountName = "alice").copy(issuer = "GitHub")

        assertEquals(emptyList(), rowsOf(GITHUB_URI, listOf(existing)).accepted(emptySet()))
    }

    @Test
    fun `an account the vault holds is added once its position is chosen`() {
        val existing = totpEntry(accountName = "alice").copy(issuer = "GitHub")

        assertEquals(1, rowsOf(GITHUB_URI, listOf(existing)).accepted(setOf(1)).size)
    }

    @Test
    fun `choosing a position no duplicate sits at adds nothing extra`() {
        assertEquals(1, rowsOf(GITHUB_URI).accepted(setOf(7)).size)
    }

    @Test
    fun `a line the reader refused is no account whatever is chosen`() {
        assertEquals(emptyList(), rowsOf("not a uri at all").accepted(setOf(1)))
    }

    @Test
    fun `a document carrying no entries at all is unreadable`() {
        assertIs<VaultError.Corrupt>(read("""{"v":1}""").errorOrNull)
    }
}

private fun exportOf(vararg entries: VaultEntry): String =
    VaultBody(entries = entries.toList()).exported(ExportFormat.JSON)
