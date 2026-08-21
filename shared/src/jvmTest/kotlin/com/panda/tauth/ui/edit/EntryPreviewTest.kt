package com.panda.tauth.ui.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.panda.tauth.Outcome
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.ui.SEED_BASE32
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.vault.DraftError
import com.panda.tauth.vault.VaultError
import org.junit.Rule
import kotlin.test.Test

private const val MALFORMED_MESSAGE = "That is not an account this reads: not an otpauth URI."
private const val SECRET_MESSAGE = "The secret is not usable: invalid base32 character."
private const val DETAILS_MESSAGE = "These details do not make an account: digits must be 6..8."

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

    @Test
    fun `a secret that is not usable says so about the secret`() {
        show(Outcome.Failure(VaultError.InvalidSecret("invalid base32 character")))

        compose.onNodeWithText(SECRET_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `details that do not make an account say so with the rule they broke`() {
        show(Outcome.Failure(VaultError.InvalidEntry("digits must be 6..8")))

        compose.onNodeWithText(DETAILS_MESSAGE).assertIsDisplayed()
    }

    private fun show(resolved: Outcome<OtpAuthUri, DraftError>?) {
        compose.setContent {
            TauthTheme { EntryPreview(resolved = resolved, epochSeconds = 0) }
        }
    }
}
