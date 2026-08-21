package com.panda.tauth.ui.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.ui.hotpRow
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.ui.totpRow
import com.panda.tauth.vault.EntryChangeError
import com.panda.tauth.vault.EntryEdit
import com.panda.tauth.vault.VaultError
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val SAVE = "Save changes"
private const val CANCEL = "Cancel"
private const val ADVANCED = "Advanced"
private const val SECRET_FIELD_LABEL = "Secret (base32)"

class EditAccountScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var saved: EntryEdit? = null
    private var cancels = 0

    @Test
    fun `the account name it opens with is the one stored`() {
        show(totpRow(accountName = "alice"))

        compose.onNodeWithText("alice").assertIsDisplayed()
    }

    @Test
    fun `the type is stated rather than offered`() {
        show(totpRow())

        compose.onNodeWithText("Type: TOTP").assertIsDisplayed()
    }

    // The secret is absent from the edit model, so no field on this screen can reach one. Read by the
    // label a secret field would carry rather than by a tag only the add screen emits.
    @Test
    fun `no secret field is on the screen`() {
        show(totpRow())

        compose.onNodeWithText(SECRET_FIELD_LABEL).assertDoesNotExist()
    }

    @Test
    fun `the screen says why the secret cannot be changed`() {
        show(totpRow())

        compose.onNodeWithText(SECRET_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a renamed account is handed over under its new name`() {
        show(totpRow())

        retype(EDIT_ACCOUNT_TAG, "erin")
        tap(SAVE)

        compose.runOnIdle { assertEquals("erin", saved?.accountName) }
    }

    @Test
    fun `a retyped issuer is handed over`() {
        show(totpRow())

        retype(EDIT_ISSUER_TAG, "Example")
        tap(SAVE)

        compose.runOnIdle { assertEquals("Example", saved?.issuer) }
    }

    // An issuer cleared away is an absent issuer. An empty one does not survive a URI, which is the
    // one spelling of absence the entry model admits.
    @Test
    fun `an issuer cleared away is handed over as absent`() {
        show(totpRow())

        compose.onNodeWithTag(EDIT_ISSUER_TAG).performScrollTo().performTextClearance()
        tap(SAVE)

        compose.runOnIdle { assertNull(saved?.issuer) }
    }

    // Half a number is not a number, and the screen holds the save until it is one rather than
    // sending the vault a period it would refuse.
    @Test
    fun `a half-typed period holds the save back`() {
        show(totpRow())

        tap(ADVANCED)
        compose.onNodeWithTag(EDIT_PERIOD_TAG).performScrollTo().performTextClearance()

        compose.onNodeWithText(SAVE).assertIsNotEnabled()
    }

    @Test
    fun `a half-typed period says what it is waiting for`() {
        show(totpRow())

        tap(ADVANCED)
        compose.onNodeWithTag(EDIT_PERIOD_TAG).performScrollTo().performTextClearance()

        compose.onNodeWithTag(EDIT_PROBLEM_TAG).assertExists()
    }

    // The rules on a name are the entry model's, and the model is what refuses them: the screen sends
    // what was typed and reports the refusal it gets back rather than holding a copy of the rules.
    @Test
    fun `an emptied account name is reported in the words the vault refused it`() {
        show(totpRow(), error = VaultError.InvalidEntry("account name must not be empty"))

        compose.onNodeWithText("The change was refused: account name must not be empty.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the advanced fields are hidden until they are asked for`() {
        show(totpRow())

        compose.onNodeWithTag(EDIT_DIGITS_TAG).assertDoesNotExist()
    }

    @Test
    fun `the advanced disclosure carries its warning`() {
        show(totpRow())

        tap(ADVANCED)

        compose.onNodeWithTag(EDIT_WARNING_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a retyped digit count is handed over`() {
        show(totpRow(digits = 6))

        tap(ADVANCED)
        retype(EDIT_DIGITS_TAG, "8")
        tap(SAVE)

        compose.runOnIdle { assertEquals(8, saved?.digits) }
    }

    @Test
    fun `a retyped period is handed over`() {
        show(totpRow(period = 30))

        tap(ADVANCED)
        retype(EDIT_PERIOD_TAG, "60")
        tap(SAVE)

        compose.runOnIdle { assertEquals(60, saved?.period) }
    }

    @Test
    fun `a chosen algorithm is handed over`() {
        show(totpRow(algorithm = HashAlgorithm.SHA1))

        tap(ADVANCED)
        tap("SHA256")
        tap(SAVE)

        compose.runOnIdle { assertEquals(HashAlgorithm.SHA256, saved?.algorithm) }
    }

    // A totp entry carries no counter, so no counter field is offered for one.
    @Test
    fun `a totp entry offers no counter field`() {
        show(totpRow())

        tap(ADVANCED)

        compose.onNodeWithTag(EDIT_COUNTER_TAG).assertDoesNotExist()
    }

    @Test
    fun `an hotp entry offers no period field`() {
        show(hotpRow())

        tap(ADVANCED)

        compose.onNodeWithTag(EDIT_PERIOD_TAG).assertDoesNotExist()
    }

    // Resynchronising a client that has run past the server's look-ahead window is what the counter
    // is editable for.
    @Test
    fun `an hotp counter set backwards is handed over`() {
        show(hotpRow(counter = 41uL))

        tap(ADVANCED)
        retype(EDIT_COUNTER_TAG, "12")
        tap(SAVE)

        compose.runOnIdle { assertEquals(12uL, saved?.counter) }
    }

    @Test
    fun `an hotp counter at the unsigned maximum is handed over`() {
        show(hotpRow(counter = 0uL))

        tap(ADVANCED)
        retype(EDIT_COUNTER_TAG, "18446744073709551615")
        tap(SAVE)

        compose.runOnIdle { assertEquals(ULong.MAX_VALUE, saved?.counter) }
    }

    // A save that never fired leaves every field of `saved` null, so each absence below is read off an
    // edit the same test has established was handed over.
    @Test
    fun `an hotp edit carries no period`() {
        show(hotpRow(counter = 41uL))

        tap(SAVE)

        compose.runOnIdle {
            assertEquals("bob", saved?.accountName)
            assertNull(saved?.period)
        }
    }

    @Test
    fun `a totp edit carries no counter`() {
        show(totpRow())

        tap(SAVE)

        compose.runOnIdle {
            assertEquals("alice", saved?.accountName)
            assertNull(saved?.counter)
        }
    }

    // The advanced fields open on the stored values, and a Save that goes straight through is what
    // reads them: every test that retypes a field first would pass on any mapping.
    @Test
    fun `an untouched counter is handed over as it was stored`() {
        show(hotpRow(counter = 41uL))

        tap(SAVE)

        compose.runOnIdle { assertEquals(41uL, saved?.counter) }
    }

    @Test
    fun `an untouched digit count is handed over as it was stored`() {
        show(totpRow(digits = 8))

        tap(SAVE)

        compose.runOnIdle { assertEquals(8, saved?.digits) }
    }

    @Test
    fun `an untouched algorithm is handed over as it was stored`() {
        show(totpRow(algorithm = HashAlgorithm.SHA512))

        tap(SAVE)

        compose.runOnIdle { assertEquals(HashAlgorithm.SHA512, saved?.algorithm) }
    }

    @Test
    fun `an untouched period is handed over as it was stored`() {
        show(totpRow(period = 60))

        tap(SAVE)

        compose.runOnIdle { assertEquals(60, saved?.period) }
    }

    @Test
    fun `an untouched issuer is handed over as it was stored`() {
        show(totpRow(issuer = "Ex Ample"))

        tap(SAVE)

        compose.runOnIdle { assertEquals("Ex Ample", saved?.issuer) }
    }

    @Test
    fun `cancelling hands over no change`() {
        show(totpRow())

        retype(EDIT_ACCOUNT_TAG, "erin")
        tap(CANCEL)

        compose.runOnIdle { assertNull(saved) }
    }

    @Test
    fun `cancelling reports the cancellation`() {
        show(totpRow())

        tap(CANCEL)

        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test
    fun `an account deleted underneath the screen is reported`() {
        show(totpRow(), error = VaultError.NoSuchEntry)

        compose.onNodeWithText("That account is no longer in the vault.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a refused write is reported`() {
        show(totpRow(), error = VaultError.LockedByAnotherProcess("vault.lock"))

        compose.onNodeWithText("Another TAuth process is holding the vault file.").performScrollTo().assertIsDisplayed()
    }

    // The remaining branches of the mapping this screen holds, which is every case a change to an entry
    // reports.
    @Test
    fun `a lock before the write is reported`() {
        show(totpRow(), error = VaultError.VaultClosed)

        compose.onNodeWithText("The vault locked before the change was saved.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a failed write shows the write message`() {
        show(totpRow(), error = VaultError.Io(RuntimeException("no space")))

        compose.onNodeWithText("The vault file could not be written.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a vault past the size the writer will produce shows its own message`() {
        show(totpRow(), error = VaultError.TooLarge(size = 2, limit = 1))

        compose.onNodeWithText("The vault is larger than the file format allows.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a version the reader does not know shows its own message`() {
        show(totpRow(), error = VaultError.UnsupportedVersion(found = 2, supported = 1))

        compose.onNodeWithText("This vault was made by a newer version of TAuth.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // The screen scrolls, and a control below the fold takes a click that lands nowhere unless it is
    // brought into view first.
    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun retype(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(text)
    }

    private fun show(entry: UnlockedEntry, error: EntryChangeError? = null) {
        compose.setContent {
            TauthTheme {
                EditAccountScreen(
                    entry = entry,
                    onSave = { saved = it },
                    onCancel = { cancels++ },
                    error = error,
                )
            }
        }
    }
}
