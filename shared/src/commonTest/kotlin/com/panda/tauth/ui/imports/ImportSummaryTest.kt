package com.panda.tauth.ui.imports

import com.panda.tauth.vault.ImportRow
import com.panda.tauth.vault.totpEntry
import kotlin.test.Test
import kotlin.test.assertEquals

private fun account(position: Int, isDuplicate: Boolean = false) =
    ImportRow.Account(position, totpEntry(accountName = "alice$position"), isDuplicate)

private fun refused(position: Int) = ImportRow.Refused(position, "not an account this reads")

class ImportSummaryTest {
    @Test
    fun `a file with nothing to decide states only what will be added`() {
        val rows = listOf(account(1), account(2), account(3))

        assertEquals("3 accounts will be added.", importSummary(rows, emptySet()))
    }

    @Test
    fun `one account is counted in the singular`() {
        assertEquals("1 account will be added.", importSummary(listOf(account(1)), emptySet()))
    }

    @Test
    fun `a duplicate left out states the total it is being taken from`() {
        val rows = listOf(account(1), account(2, isDuplicate = true))

        assertEquals("1 of 2 accounts will be added. 1 already here.", importSummary(rows, emptySet()))
    }

    @Test
    fun `a duplicate taken drops the total again`() {
        val rows = listOf(account(1), account(2, isDuplicate = true))

        assertEquals("2 accounts will be added. 1 already here.", importSummary(rows, setOf(2)))
    }

    @Test
    fun `a refused row is counted where there is one`() {
        val rows = listOf(account(1), refused(2))

        assertEquals("1 account will be added. 1 could not be read.", importSummary(rows, emptySet()))
    }

    @Test
    fun `both kinds of note are stated together`() {
        val rows = listOf(account(1), account(2, isDuplicate = true), refused(3))

        assertEquals(
            "1 of 2 accounts will be added. 1 already here, 1 could not be read.",
            importSummary(rows, emptySet()),
        )
    }

    @Test
    fun `a file the vault already holds entirely says nothing arrives`() {
        val rows = listOf(account(1, isDuplicate = true), account(2, isDuplicate = true))

        assertEquals("No accounts will be added. 2 already here.", importSummary(rows, emptySet()))
    }

    @Test
    fun `a file whose every row was refused says nothing arrives`() {
        val rows = listOf(refused(1), refused(2))

        assertEquals("No accounts will be added. 2 could not be read.", importSummary(rows, emptySet()))
    }

    @Test
    fun `an empty file says nothing arrives`() {
        assertEquals("No accounts will be added.", importSummary(emptyList(), emptySet()))
    }
}
