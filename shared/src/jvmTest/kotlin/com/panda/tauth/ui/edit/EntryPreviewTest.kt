package com.panda.tauth.ui.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.panda.tauth.Outcome
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.ui.SEED_BASE32
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.vault.VaultError
import org.junit.Rule
import kotlin.test.Test

// The preview's own wording, repeated here as literals so a changed message fails the test that names
// it rather than following it.
private const val MALFORMED_MESSAGE = "That is not an account this reads: not an otpauth URI."
private const val DAMAGED_MESSAGE = "The vault file is damaged."
private const val WRONG_PASSWORD_MESSAGE = "That password did not open the vault."

private const val TOTP_URI = "otpauth://totp/GitHub:alice?secret=$SEED_BASE32&issuer=GitHub"

class EntryPreviewTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a resolved account previews its account name`() {
        show(OtpAuthUri.parse(TOTP_URI))

        compose.onNodeWithText("alice").assertIsDisplayed()
    }

    @Test
    fun `nothing resolved yet previews no problem`() {
        show(null)

        compose.onNodeWithTag(PREVIEW_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `a malformed URI is reported in its own words`() {
        show(Outcome.Failure(VaultError.MalformedUri("not an otpauth URI")))

        compose.onNodeWithText(MALFORMED_MESSAGE).assertIsDisplayed()
    }

    // A damaged file and a password that did not work mean different things, and no mapping in the
    // application may let one stand in for the other, this screen included.
    @Test
    fun `a damaged file shows the damaged-file message`() {
        show(Outcome.Failure(VaultError.IntegrityFailure))

        compose.onNodeWithText(DAMAGED_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `a damaged file does not show the password message`() {
        show(Outcome.Failure(VaultError.IntegrityFailure))

        compose.onNodeWithText(WRONG_PASSWORD_MESSAGE).assertDoesNotExist()
    }

    @Test
    fun `a wrong password shows the password message`() {
        show(Outcome.Failure(VaultError.WrongPassword))

        compose.onNodeWithText(WRONG_PASSWORD_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `a wrong password does not show the damaged-file message`() {
        show(Outcome.Failure(VaultError.WrongPassword))

        compose.onNodeWithText(DAMAGED_MESSAGE).assertDoesNotExist()
    }

    private fun show(resolved: Outcome<OtpAuthUri, VaultError>?) {
        compose.setContent {
            TauthTheme { EntryPreview(resolved = resolved, epochSeconds = 0) }
        }
    }
}
