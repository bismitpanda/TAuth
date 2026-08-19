package com.panda.tauth.ui.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.panda.tauth.Outcome
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.OtpType
import com.panda.tauth.ui.SEED_BASE32
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.vault.EntryAddError
import com.panda.tauth.vault.VaultError
import org.junit.Rule
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The screen's own wording, repeated here as literals so a changed label fails the test that names it
// rather than following it.
private const val SAVE = "Save account"
private const val CANCEL = "Cancel"
private const val MANUAL_PATH = "Enter details"
private const val ADVANCED = "Advanced"

private const val TOTP_URI = "otpauth://totp/GitHub:alice?secret=$SEED_BASE32&issuer=GitHub"
private const val HOTP_URI = "otpauth://hotp/bob?secret=$SEED_BASE32&counter=0"

// RFC 6238 Appendix B: at 59 seconds with a 30-second period the SHA-1 code over this seed is
// 94287082 truncated to six digits.
private const val PREVIEW_AT = 59L
private const val TOTP_PREVIEW = "287 082"

// RFC 4226 Appendix D counter 0 over the same seed, truncated to six digits.
private const val HOTP_PREVIEW = "755 224"

private const val BAD_SECRET_MESSAGE = "The secret is not usable: invalid base32 character."

class AddAccountScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var saved: OtpAuthUri? = null
    private var cancels = 0

    @Test
    fun `nothing is previewed before anything is entered`() {
        show()

        compose.onNodeWithTag(PREVIEW_CODE_TAG).assertDoesNotExist()
    }

    @Test
    fun `the save button is disabled before anything is entered`() {
        show()

        compose.onNodeWithText(SAVE).assertIsNotEnabled()
    }

    @Test
    fun `a pasted URI previews its issuer`() {
        show()

        paste(TOTP_URI)

        compose.onNodeWithText("GitHub").assertIsDisplayed()
    }

    @Test
    fun `a pasted URI previews its account name`() {
        show()

        paste(TOTP_URI)

        compose.onNodeWithText("alice").assertIsDisplayed()
    }

    // The preview stands for the account the server holds, so it carries a code the server computes.
    @Test
    fun `a pasted totp URI previews a live sample code`() {
        show()

        paste(TOTP_URI)

        compose.onNodeWithTag(PREVIEW_CODE_TAG).assertTextEquals(TOTP_PREVIEW)
    }

    @Test
    fun `a pasted hotp URI previews its starting counter`() {
        show()

        paste(HOTP_URI)

        compose.onNodeWithText("Starting counter 0").assertIsDisplayed()
    }

    // Nothing is stored to work this out, so confirming an hotp account does not spend the counter
    // value the server is still waiting for.
    @Test
    fun `a pasted hotp URI previews the code its starting counter produces`() {
        show()

        paste(HOTP_URI)

        compose.onNodeWithTag(PREVIEW_CODE_TAG).assertTextEquals(HOTP_PREVIEW)
    }

    @Test
    fun `a malformed URI is reported rather than previewed`() {
        show()

        paste("https://example.com")

        compose.onNodeWithTag(PREVIEW_PROBLEM_TAG).assertIsDisplayed()
    }

    @Test
    fun `a malformed URI leaves the save button disabled`() {
        show()

        paste("https://example.com")

        compose.onNodeWithText(SAVE).assertIsNotEnabled()
    }

    @Test
    fun `saving a pasted URI hands over the account it resolved to`() {
        show()

        paste(TOTP_URI)
        compose.onNodeWithText(SAVE).performClick()

        compose.runOnIdle { assertEquals("alice", saved?.accountName) }
    }

    @Test
    fun `saving a pasted URI hands over its secret`() {
        show()

        paste(TOTP_URI)
        compose.onNodeWithText(SAVE).performClick()

        compose.runOnIdle { assertEquals(SEED_BASE32, saved?.secret) }
    }

    @Test
    fun `cancelling hands over no account`() {
        show()

        paste(TOTP_URI)
        compose.onNodeWithText(CANCEL).performClick()

        compose.runOnIdle { assertNull(saved) }
    }

    @Test
    fun `cancelling reports the cancellation`() {
        show()

        compose.onNodeWithText(CANCEL).performClick()

        compose.runOnIdle { assertEquals(1, cancels) }
    }

    // The typed form and the pasted URI reach the same preview, which is what keeps the two paths from
    // resolving an account differently.
    @Test
    fun `a typed account previews the same sample code as the URI for it`() {
        show()

        enterManually()

        compose.onNodeWithTag(PREVIEW_CODE_TAG).assertTextEquals(TOTP_PREVIEW)
    }

    @Test
    fun `saving a typed account hands over the account name that was typed`() {
        show()

        enterManually()
        tap(SAVE)

        compose.runOnIdle { assertEquals("alice", saved?.accountName) }
    }

    @Test
    fun `saving a typed account hands over the issuer that was typed`() {
        show()

        enterManually()
        tap(SAVE)

        compose.runOnIdle { assertEquals("GitHub", saved?.issuer) }
    }

    // The account name is left blank on purpose: the entry model refuses that field first, so a form
    // checked only through it would answer a base32 mistake by naming another field.
    @Test
    fun `a typed secret that is not base32 is reported against the secret field`() {
        show()

        tap(MANUAL_PATH)
        compose.onNodeWithTag(SECRET_FIELD_TAG).performScrollTo().performTextInput("not base32!")

        compose.onNodeWithTag(SECRET_PROBLEM_TAG).assertTextEquals(BAD_SECRET_MESSAGE)
    }

    @Test
    fun `a secret field left empty reports nothing`() {
        show()

        tap(MANUAL_PATH)

        compose.onNodeWithTag(SECRET_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `a valid secret reports no base32 problem`() {
        show()

        tap(MANUAL_PATH)
        compose.onNodeWithTag(SECRET_FIELD_TAG).performScrollTo().performTextInput(SEED_BASE32)

        compose.onNodeWithTag(SECRET_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `saving a typed account hands over the secret that was typed`() {
        show()

        enterManually()
        tap(SAVE)

        compose.runOnIdle { assertEquals(SEED_BASE32, saved?.secret) }
    }

    // A starting counter that opened anywhere but zero would enrol the account at a position the
    // server is not expecting.
    @Test
    fun `the counter field opens at zero`() {
        show()

        tap(MANUAL_PATH)
        tap("HOTP")
        tap(ADVANCED)

        compose.onNodeWithTag(COUNTER_FIELD_TAG).performScrollTo().assertTextEquals("0")
    }

    @Test
    fun `the advanced fields are hidden until they are asked for`() {
        show()

        tap(MANUAL_PATH)

        compose.onNodeWithTag(DIGITS_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun `the advanced digit count reaches the account that is saved`() {
        show()

        enterManually()
        tap(ADVANCED)
        typeInto(DIGITS_FIELD_TAG, "8")
        tap(SAVE)

        compose.runOnIdle { assertEquals(8, saved?.digits) }
    }

    @Test
    fun `the advanced period reaches the account that is saved`() {
        show()

        enterManually()
        tap(ADVANCED)
        typeInto(PERIOD_FIELD_TAG, "60")
        tap(SAVE)

        compose.runOnIdle { assertEquals(60, saved?.period) }
    }

    @Test
    fun `the advanced algorithm reaches the account that is saved`() {
        show()

        enterManually()
        tap(ADVANCED)
        tap("SHA512")
        tap(SAVE)

        compose.runOnIdle { assertEquals(HashAlgorithm.SHA512, saved?.algorithm) }
    }

    // Which type is chosen reads off the control rather than off which one is greyed out.
    @Test
    fun `the chosen type is the one marked selected`() {
        show()

        tap(MANUAL_PATH)

        compose.onNodeWithText("TOTP").assertIsSelected()
    }

    @Test
    fun `a type not chosen is not marked selected`() {
        show()

        tap(MANUAL_PATH)

        compose.onNodeWithText("HOTP").assertIsNotSelected()
    }

    // The advanced section offers the moving factor the chosen type carries and not the other one.
    @Test
    fun `an hotp form offers a counter rather than a period`() {
        show()

        tap(MANUAL_PATH)
        tap("HOTP")
        tap(ADVANCED)

        compose.onNodeWithTag(PERIOD_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun `an hotp form saves the counter that was typed`() {
        show()

        enterHotpManually()
        tap(ADVANCED)
        typeInto(COUNTER_FIELD_TAG, "41")
        tap(SAVE)

        compose.runOnIdle { assertEquals(41uL, saved?.counter) }
    }

    @Test
    fun `an hotp form saves an account of its own type`() {
        show()

        enterHotpManually()
        tap(SAVE)

        compose.runOnIdle { assertEquals(OtpType.HOTP, saved?.type) }
    }

    @Test
    fun `a refused write is reported on the screen that asked for it`() {
        show(error = VaultError.LockedByAnotherProcess("vault.lock"))

        compose.onNodeWithText("Another TAuth process is holding the vault file.").assertIsDisplayed()
    }

    @Test
    fun `a lock that overtook the write is reported`() {
        show(error = VaultError.VaultClosed)

        compose.onNodeWithText("The vault locked before the account was saved.").assertIsDisplayed()
    }

    // The remaining branches of the mapping this screen holds, which is every case storing a new entry
    // reports.
    @Test
    fun `a refused value is reported with the rule it broke`() {
        show(error = VaultError.InvalidEntry("account name must not be empty"))

        compose.onNodeWithText("The account could not be saved: account name must not be empty.")
            .assertIsDisplayed()
    }

    @Test
    fun `a secret that will not decode is reported against the secret`() {
        show(error = VaultError.InvalidSecret("invalid base32 character"))

        compose.onNodeWithText("The secret could not be stored: invalid base32 character.").assertIsDisplayed()
    }

    @Test
    fun `a failed write shows the write message`() {
        show(error = VaultError.Io(RuntimeException("no space")))

        compose.onNodeWithText("The vault file could not be written.").assertIsDisplayed()
    }

    @Test
    fun `a vault past the size the writer will produce shows its own message`() {
        show(error = VaultError.TooLarge(size = 2, limit = 1))

        compose.onNodeWithText("The vault is larger than the file format allows.").assertIsDisplayed()
    }

    @Test
    fun `a version the reader does not know shows its own message`() {
        show(error = VaultError.UnsupportedVersion(found = 2, supported = 1))

        compose.onNodeWithText("The vault file is in a format this version of TAuth does not read.")
            .assertIsDisplayed()
    }

    private fun paste(text: String) = compose.onNodeWithTag(URI_FIELD_TAG).performTextInput(text)

    // The screen scrolls, and a control below the fold takes a click that lands nowhere unless it is
    // brought into view first.
    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun typeInto(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(text)
    }

    private fun enterManually() {
        tap(MANUAL_PATH)
        compose.onNodeWithTag(ISSUER_FIELD_TAG).performScrollTo().performTextInput("GitHub")
        compose.onNodeWithTag(ACCOUNT_FIELD_TAG).performScrollTo().performTextInput("alice")
        compose.onNodeWithTag(SECRET_FIELD_TAG).performScrollTo().performTextInput(SEED_BASE32)
    }

    private fun enterHotpManually() {
        tap(MANUAL_PATH)
        tap("HOTP")
        compose.onNodeWithTag(ACCOUNT_FIELD_TAG).performScrollTo().performTextInput("bob")
        compose.onNodeWithTag(SECRET_FIELD_TAG).performScrollTo().performTextInput(SEED_BASE32)
    }

    // A composition with no desktop under it has nothing to read an image with, so it offers no way
    // to try.
    @Test
    fun `no way to read an image is offered without one`() {
        show()

        compose.onNodeWithText("Read an image").assertDoesNotExist()
    }

    @Test
    fun `reading an image is offered where the shell can`() {
        show(scanning = { Outcome.Success(emptyList()) })

        compose.onNodeWithText("Read an image").assertIsDisplayed()
    }

    @Test
    fun `an account read from an image reaches the preview`() {
        show(scanning = { Outcome.Success(listOf(TOTP_URI)) })

        compose.onNodeWithText("Read an image").performClick()
        compose.onNodeWithText("Choose an image").performClick()

        compose.onNodeWithText("GitHub", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an account read from an image is what a save hands over`() {
        show(scanning = { Outcome.Success(listOf(TOTP_URI)) })

        compose.onNodeWithText("Read an image").performClick()
        compose.onNodeWithText("Choose an image").performClick()
        compose.onNodeWithText(SAVE).performClick()

        compose.runOnIdle { assertEquals("alice", saved?.accountName) }
    }

    @Test
    fun `an image holding no code says so`() {
        show(scanning = { Outcome.Success(emptyList()) })

        compose.onNodeWithText("Read an image").performClick()
        compose.onNodeWithText("Choose an image").performClick()

        compose.onNodeWithTag(SCAN_PROBLEM_TAG).assertTextEquals("No QR code was found in that image.")
    }

    @Test
    fun `a code that is not an account says so`() {
        show(scanning = { Outcome.Success(listOf("https://example.com")) })

        compose.onNodeWithText("Read an image").performClick()
        compose.onNodeWithText("Choose an image").performClick()

        compose.onNodeWithTag(SCAN_PROBLEM_TAG).assertTextEquals("That QR code is not an account TAuth can add.")
    }

    @Test
    fun `an image that could not be read says so`() {
        show(scanning = { Outcome.Failure(VaultError.Io(IOException("no such file"))) })

        compose.onNodeWithText("Read an image").performClick()
        compose.onNodeWithText("Choose an image").performClick()

        compose.onNodeWithTag(SCAN_PROBLEM_TAG).assertTextEquals("That image could not be read.")
    }

    // §9.5's selection list: the account is named by its issuer and account name, and the choice is
    // the user's.
    @Test
    fun `several accounts in an image are offered by name`() {
        show(scanning = { Outcome.Success(listOf(TOTP_URI, HOTP_URI)) })

        compose.onNodeWithText("Read an image").performClick()
        compose.onNodeWithText("Choose an image").performClick()

        compose.onNodeWithTag(scanPickTag(1)).assertTextEquals("bob")
    }

    @Test
    fun `the account chosen from an image reaches the preview`() {
        show(scanning = { Outcome.Success(listOf(TOTP_URI, HOTP_URI)) })

        compose.onNodeWithText("Read an image").performClick()
        compose.onNodeWithText("Choose an image").performClick()
        compose.onNodeWithTag(scanPickTag(1)).performClick()
        compose.onNodeWithText(SAVE).performClick()

        compose.runOnIdle { assertEquals("bob", saved?.accountName) }
    }

    private fun show(error: EntryAddError? = null, scanning: QrScanning? = null) {
        compose.setContent {
            TauthTheme {
                AddAccountScreen(
                    onSave = { saved = it },
                    onCancel = { cancels++ },
                    epochSeconds = PREVIEW_AT,
                    error = error,
                    scanning = scanning,
                )
            }
        }
    }
}
