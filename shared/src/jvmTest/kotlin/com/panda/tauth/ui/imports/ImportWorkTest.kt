package com.panda.tauth.ui.imports

import com.panda.tauth.Outcome
import com.panda.tauth.vault.EntryAddError
import com.panda.tauth.vault.ImportOffer
import com.panda.tauth.vault.ImportRow
import com.panda.tauth.vault.VaultEntry
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.hotpEntry
import com.panda.tauth.vault.totpEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEXT = "otpauth://totp/GitHub:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

private val FRESH = ImportRow.Account(1, totpEntry(accountName = "alice"), isDuplicate = false)
private val DUPLICATE = ImportRow.Account(2, hotpEntry(), isDuplicate = true)
private val ROWS = listOf(FRESH, DUPLICATE)

private val OFFER = ImportOffer(ROWS, note = "part 1 of 2")

// Unconfined runs each resumption on the thread that causes it, so every assertion below sees a
// settled holder without joining anything.
class ImportWorkTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val work = ImportWork()

    private var written: List<VaultEntry>? = null

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    @Test
    fun `a file that was read opens a preview`() {
        work.open(scope, { Outcome.Success(TEXT) }) { Outcome.Success(OFFER) }

        assertTrue(work.isPreviewing)
    }

    @Test
    fun `a file that was read holds the rows it offered`() {
        work.open(scope, { Outcome.Success(TEXT) }) { Outcome.Success(OFFER) }

        assertEquals(ROWS, work.rows)
    }

    // Declining the picker is neither a failure to report nor a preview to open.
    @Test
    fun `a file the user declined opens no preview`() {
        work.open(scope, { Outcome.Success(null) }) { Outcome.Success(OFFER) }

        assertFalse(work.isPreviewing)
    }

    @Test
    fun `a file the user declined reports nothing`() {
        work.open(scope, { Outcome.Success(null) }) { Outcome.Success(OFFER) }

        assertNull(work.readError)
    }

    @Test
    fun `a file that could not be fetched reports what the shell said`() {
        work.open(scope, { Outcome.Failure(VaultError.Corrupt("not text")) }) { Outcome.Success(OFFER) }

        assertEquals(VaultError.Corrupt("not text"), work.readError)
    }

    @Test
    fun `a file that could not be fetched opens no preview`() {
        work.open(scope, { Outcome.Failure(VaultError.Corrupt("not text")) }) { Outcome.Success(OFFER) }

        assertFalse(work.isPreviewing)
    }

    @Test
    fun `a file the vault would not read reports what the read said`() {
        work.open(scope, { Outcome.Success(TEXT) }) { Outcome.Failure(VaultError.VaultClosed) }

        assertEquals(VaultError.VaultClosed, work.readError)
    }

    @Test
    fun `a file the vault would not read opens no preview`() {
        work.open(scope, { Outcome.Success(TEXT) }) { Outcome.Failure(VaultError.VaultClosed) }

        assertFalse(work.isPreviewing)
    }

    @Test
    fun `a duplicate is taken by its position`() {
        opened()

        work.toggle(2)

        assertEquals(setOf(2), work.addAnyway)
    }

    @Test
    fun `a duplicate taken twice is left out again`() {
        opened()

        work.toggle(2)
        work.toggle(2)

        assertEquals(emptySet(), work.addAnyway)
    }

    @Test
    fun `what is written is what the preview accepted`() {
        opened()

        work.add(scope, ::record)

        assertEquals(listOf(FRESH.entry), written)
    }

    @Test
    fun `a duplicate taken is written with the rest`() {
        opened()
        work.toggle(2)

        work.add(scope, ::record)

        assertEquals(listOf(FRESH.entry, DUPLICATE.entry), written)
    }

    // The rows carry every secret the file offered, so the preview ends with the write that took it.
    @Test
    fun `a write that landed drops the rows`() {
        opened()

        work.add(scope, ::record)

        assertFalse(work.isPreviewing)
    }

    @Test
    fun `a write that was refused keeps the preview up`() {
        opened()

        work.add(scope) { Outcome.Failure(VaultError.VaultClosed) }

        assertTrue(work.isPreviewing)
    }

    @Test
    fun `a write that was refused reports what it said`() {
        opened()

        work.add(scope) { Outcome.Failure(VaultError.VaultClosed) }

        assertEquals(VaultError.VaultClosed, work.addError)
    }

    @Test
    fun `a second read opens on no choices from the first`() {
        opened()
        work.toggle(2)

        work.open(scope, { Outcome.Success(TEXT) }) { Outcome.Success(OFFER) }

        assertEquals(emptySet(), work.addAnyway)
    }

    // Leaving the settings screen leaves what the last read reported, as every other destination
    // leaves what it reported.
    @Test
    fun `what a read reported is left behind when it is cleared`() {
        work.open(scope, { Outcome.Failure(VaultError.Corrupt("not text")) }) { Outcome.Success(OFFER) }

        work.clearReadError()

        assertNull(work.readError)
    }

    @Test
    fun `leaving the preview drops the rows`() {
        opened()

        work.clear()

        assertFalse(work.isPreviewing)
    }

    private fun opened() {
        work.open(scope, { Outcome.Success(TEXT) }) { Outcome.Success(OFFER) }
    }

    private suspend fun record(entries: List<VaultEntry>): Outcome<Unit, EntryAddError> {
        written = entries
        return Outcome.Success(Unit)
    }
}
