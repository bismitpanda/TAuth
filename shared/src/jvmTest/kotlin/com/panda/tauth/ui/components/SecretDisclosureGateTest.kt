package com.panda.tauth.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.vault.DiscloseError
import com.panda.tauth.vault.VaultError
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val CONFIRM_LABEL = "Show"
private const val CANCEL_LABEL = "Cancel"
private const val PROGRESS_LABEL = "Checking your password"
private const val WRONG_PASSWORD_MESSAGE = "That password is not correct."
private const val DAMAGED_FILE_MESSAGE = "The vault file is damaged, so nothing can be shown from it."

// The component takes the statement as a parameter, so the sentence a caller supplies is the
// sentence on screen.
private const val STATEMENT = "The complete secret for GitHub — alice is about to be placed on the clipboard."

class SecretDisclosureGateTest {
    @get:Rule
    val compose = createComposeRule()

    private var captured: CharArray? = null
    private var dismissals = 0

    @Test
    fun `the statement the caller supplied is on screen`() {
        show()

        compose.onNodeWithTag(DISCLOSURE_STATEMENT_TAG).assertTextEquals(STATEMENT)
    }

    @Test
    fun `the password field holds focus when the gate opens`() {
        show()

        passwordField().assertIsFocused()
    }

    @Test
    fun `the confirm button is disabled before anything is typed`() {
        show()

        confirmButton().assertIsNotEnabled()
    }

    @Test
    fun `the confirm button hands over the password`() {
        show()
        type("aB3!efghij")

        confirmButton().performClick()

        compose.runOnIdle { assertContentEquals("aB3!efghij".toCharArray(), captured) }
    }

    @Test
    fun `an entry submitted with the keyboard hands over the password`() {
        show()
        type("aB3!efghij")

        passwordField().performImeAction()

        compose.runOnIdle { assertContentEquals("aB3!efghij".toCharArray(), captured) }
    }

    // The gate is the whole protection on a complete credential, so an empty entry must not reach the
    // check as a zero-length password.
    @Test
    fun `an empty entry hands over nothing`() {
        show()

        confirmButton().performClick()

        compose.runOnIdle { assertNull(captured) }
    }

    @Test
    fun `an empty entry submitted with the keyboard hands over nothing`() {
        show()

        passwordField().performImeAction()

        compose.runOnIdle { assertNull(captured) }
    }

    @Test
    fun `the confirm button is disabled while a check runs`() {
        var isBusy by mutableStateOf(false)
        compose.setContent { TauthTheme { gate(isBusy = isBusy) } }
        type("aB3!efghij")

        compose.runOnIdle { isBusy = true }

        confirmButton().assertIsNotEnabled()
    }

    @Test
    fun `a keyboard submission while a check runs hands over nothing`() {
        var isBusy by mutableStateOf(false)
        compose.setContent { TauthTheme { gate(isBusy = isBusy) } }
        type("aB3!efghij")

        compose.runOnIdle { isBusy = true }
        passwordField().performImeAction()

        compose.runOnIdle { assertNull(captured) }
    }

    // A password typed wrong is corrected rather than waited out, so what a running check blocks is a
    // second submission and not the field.
    @Test
    fun `the field takes characters while a check runs`() {
        var isBusy by mutableStateOf(true)
        compose.setContent { TauthTheme { gate(isBusy = isBusy) } }

        type("aB3!efghij")
        compose.runOnIdle { isBusy = false }
        confirmButton().performClick()

        compose.runOnIdle { assertContentEquals("aB3!efghij".toCharArray(), captured) }
    }

    @Test
    fun `the progress indicator is on screen while a check runs`() {
        show(isBusy = true)

        compose.onNodeWithContentDescription(PROGRESS_LABEL).assertIsDisplayed()
    }

    @Test
    fun `the progress indicator is absent while no check runs`() {
        show(isBusy = false)

        compose.onNodeWithContentDescription(PROGRESS_LABEL).assertDoesNotExist()
    }

    @Test
    fun `a wrong password shows the password message`() {
        show(error = VaultError.WrongPassword)

        compose.onNodeWithText(WRONG_PASSWORD_MESSAGE).assertIsDisplayed()
    }

    // A password that did not open the vault and a vault that reads back damaged mean different
    // things, and neither message may stand in for the other.
    @Test
    fun `a wrong password does not show the damaged-file message`() {
        show(error = VaultError.WrongPassword)

        compose.onNodeWithText(DAMAGED_FILE_MESSAGE).assertDoesNotExist()
    }

    @Test
    fun `a damaged header shows the damaged-file message`() {
        show(error = VaultError.Corrupt("header"))

        compose.onNodeWithText(DAMAGED_FILE_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `a damaged header does not show the password message`() {
        show(error = VaultError.Corrupt("header"))

        compose.onNodeWithText(WRONG_PASSWORD_MESSAGE).assertDoesNotExist()
    }

    @Test
    fun `an entry deleted underneath the gate shows its own message`() {
        show(error = VaultError.NoSuchEntry)

        compose.onNodeWithText("That account is no longer in the vault.").assertIsDisplayed()
    }

    @Test
    fun `a lock that overtook the check shows its own message`() {
        show(error = VaultError.VaultClosed)

        compose.onNodeWithText("The vault locked while your password was being checked.").assertIsDisplayed()
    }

    @Test
    fun `cancelling reports a dismissal`() {
        show()

        compose.onNodeWithText(CANCEL_LABEL).performClick()

        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun `cancelling hands over no password`() {
        show()
        type("aB3!efghij")

        compose.onNodeWithText(CANCEL_LABEL).performClick()

        compose.runOnIdle { assertNull(captured) }
    }

    private fun show(isBusy: Boolean = false, error: DiscloseError? = null) {
        compose.setContent { TauthTheme { gate(isBusy = isBusy, error = error) } }
    }

    @androidx.compose.runtime.Composable
    private fun gate(isBusy: Boolean = false, error: DiscloseError? = null) {
        SecretDisclosureGate(
            statement = STATEMENT,
            onConfirm = { captured = it },
            onDismiss = { dismissals++ },
            isBusy = isBusy,
            error = error,
        )
    }

    private fun passwordField(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction())

    private fun type(text: String) = passwordField().performTextInput(text)

    private fun confirmButton(): SemanticsNodeInteraction = compose.onNodeWithText(CONFIRM_LABEL)
}
