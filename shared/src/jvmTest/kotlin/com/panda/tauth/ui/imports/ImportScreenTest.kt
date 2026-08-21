package com.panda.tauth.ui.imports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.vault.EntryAddError
import com.panda.tauth.vault.ImportRow
import com.panda.tauth.vault.ImportSource
import com.panda.tauth.vault.TEST_SECRET
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.hotpEntry
import com.panda.tauth.vault.totpEntry
import org.junit.Rule
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

private const val CONFIRM = "Add these accounts"
private const val CANCEL = "Cancel"

private val FRESH = ImportRow.Account(1, totpEntry(accountName = "alice"), isDuplicate = false)
private val DUPLICATE = ImportRow.Account(2, hotpEntry(), isDuplicate = true)
private val REFUSED = ImportRow.Refused(3, "truncated base32 group")

private val ROWS = listOf(FRESH, DUPLICATE, REFUSED)

class ImportScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val toggled = mutableListOf<Int>()
    private var imports = 0
    private var cancels = 0

    @Test
    fun `the summary counts what will be added`() {
        show()

        compose.onNodeWithTag(IMPORT_SUMMARY_TAG)
            .assertTextEquals("1 of 2 accounts will be added. 1 already here, 1 could not be read.")
    }

    @Test
    fun `the summary follows a duplicate being taken`() {
        show(addAnyway = setOf(2))

        compose.onNodeWithTag(IMPORT_SUMMARY_TAG)
            .assertTextEquals("2 accounts will be added. 1 already here, 1 could not be read.")
    }

    @Test
    fun `an account is named by its issuer and account name`() {
        show()

        compose.onNodeWithText("GitHub: alice").assertIsDisplayed()
    }

    // The row carries the secret the file offered, and nothing about it belongs on a screen.
    @Test
    fun `no row carries the secret it stands for`() {
        show()

        compose.onNodeWithText(TEST_SECRET, substring = true).assertDoesNotExist()
    }

    @Test
    fun `an account the vault already holds offers the choice to take it`() {
        show()

        compose.onNodeWithTag(importChoiceTag(2)).assertIsDisplayed()
    }

    @Test
    fun `an account the vault does not hold offers no choice`() {
        show()

        compose.onNodeWithTag(importChoiceTag(1)).assertDoesNotExist()
    }

    @Test
    fun `a duplicate opens on skipping`() {
        show()

        compose.onNodeWithTag(importChoiceTag(2)).assertIsOff()
    }

    @Test
    fun `a duplicate that was taken reads as taken`() {
        show(addAnyway = setOf(2))

        compose.onNodeWithTag(importChoiceTag(2)).assertIsOn()
    }

    @Test
    fun `choosing a duplicate reports its position`() {
        show()

        compose.onNodeWithTag(importChoiceTag(2)).performClick()

        compose.runOnIdle { assertEquals(listOf(2), toggled) }
    }

    // The line is a credential, so where it sat and the rule it broke are what the preview carries.
    @Test
    fun `a refused line names where it sat and the rule it broke`() {
        show()

        compose.onNodeWithText("Line 3: truncated base32 group").assertIsDisplayed()
    }

    @Test
    fun `a refused account off a document is named by its place rather than by a line`() {
        show(source = ImportSource.DOCUMENT)

        compose.onNodeWithText("Account 3: truncated base32 group").assertIsDisplayed()
    }

    @Test
    fun `a refused account off an export code is named by its place rather than by a line`() {
        show(source = ImportSource.EXPORT_CODE)

        compose.onNodeWithText("Account 3: truncated base32 group").assertIsDisplayed()
    }

    @Test
    fun `what a source said about itself is on screen`() {
        show(note = "This export is split across 2 codes. This is part 1: scan the others too.")

        compose.onNodeWithTag(IMPORT_NOTE_TAG)
            .assertTextEquals("This export is split across 2 codes. This is part 1: scan the others too.")
    }

    @Test
    fun `a source that said nothing about itself draws no note`() {
        show()

        compose.onNodeWithTag(IMPORT_NOTE_TAG).assertDoesNotExist()
    }

    @Test
    fun `importing reports the request`() {
        show()

        compose.onNodeWithText(CONFIRM).performClick()

        compose.runOnIdle { assertEquals(1, imports) }
    }

    @Test
    fun `cancelling reports the dismissal`() {
        show()

        compose.onNodeWithText(CANCEL).performClick()

        compose.runOnIdle { assertEquals(1, cancels) }
    }

    // A file whose every account the vault already holds has nothing to add until one is chosen.
    @Test
    fun `nothing to add holds the import back`() {
        show(rows = listOf(DUPLICATE, REFUSED))

        compose.onNodeWithText(CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `a duplicate taken lets the import go ahead`() {
        show(rows = listOf(DUPLICATE, REFUSED), addAnyway = setOf(2))

        compose.onNodeWithText(CONFIRM).assertIsEnabled()
    }

    @Test
    fun `a write in flight holds the controls`() {
        show(isBusy = true)

        compose.onNodeWithText(CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `a vault that locked before the write says none were added`() {
        show(error = VaultError.VaultClosed)

        compose.onNodeWithTag(IMPORT_PROBLEM_TAG)
            .assertTextEquals("The vault locked before the accounts were added, so none of them were.")
    }

    @Test
    fun `a write another process refused says none were added`() {
        show(error = VaultError.LockedByAnotherProcess("vault.lock"))

        compose.onNodeWithTag(IMPORT_PROBLEM_TAG)
            .assertTextEquals("Another TAuth holds the vault file, so none were added.")
    }

    @Test
    fun `a refused value states the rule it broke`() {
        show(error = VaultError.InvalidEntry("digits must be 6..8"))

        compose.onNodeWithTag(IMPORT_PROBLEM_TAG)
            .assertTextEquals("One of these accounts cannot be stored: digits must be 6..8.")
    }

    @Test
    fun `a failed write says none were added`() {
        show(error = VaultError.Io(IOException("no space left on device")))

        compose.onNodeWithTag(IMPORT_PROBLEM_TAG)
            .assertTextEquals("The vault could not be written, so none of these accounts were added.")
    }

    private fun show(
        rows: List<ImportRow> = ROWS,
        addAnyway: Set<Int> = emptySet(),
        isBusy: Boolean = false,
        error: EntryAddError? = null,
        source: ImportSource = ImportSource.URI_LIST,
        note: String? = null,
    ) {
        compose.setContent {
            TauthTheme {
                ImportScreen(
                    rows = rows,
                    source = source,
                    note = note,
                    addAnyway = addAnyway,
                    isBusy = isBusy,
                    error = error,
                    onToggleDuplicate = { toggled += it },
                    onImport = { imports++ },
                    onCancel = { cancels++ },
                )
            }
        }
    }
}
