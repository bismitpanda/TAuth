package com.panda.tauth.ui.create

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.vault.VaultCreateError
import com.panda.tauth.vault.VaultError
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

private const val ACKNOWLEDGE_LABEL = "I understand that if I lose my password, I lose every code stored here"
private const val NOTE_HEADING = "Your password cannot be recovered"
private const val CREATE_LABEL = "Create vault"

class CreateVaultScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var captured: CharArray? = null

    @Test
    fun `the recovery note is on screen before anything is typed`() {
        show()

        compose.onNodeWithText(NOTE_HEADING).assertIsDisplayed()
    }

    @Test
    fun `acknowledging the note leaves it on screen`() {
        show()

        acknowledge()

        compose.onNodeWithText(NOTE_HEADING).assertIsDisplayed()
    }

    @Test
    fun `the create button is disabled until the note is acknowledged`() {
        show()

        typePassword("aB3!efghij")
        typeConfirmation("aB3!efghij")

        createButton().assertIsNotEnabled()
    }

    @Test
    fun `the create button is disabled below the minimum length`() {
        show()
        acknowledge()

        typePassword("aB3!efg")
        typeConfirmation("aB3!efg")

        createButton().assertIsNotEnabled()
    }

    @Test
    fun `the create button is disabled while the confirmation differs`() {
        show()
        acknowledge()

        typePassword("aB3!efghij")
        typeConfirmation("aB3!efghik")

        createButton().assertIsNotEnabled()
    }

    @Test
    fun `the create button is enabled once the note is acknowledged and the passwords match`() {
        show()
        acknowledge()

        typePassword("aB3!efghij")
        typeConfirmation("aB3!efghij")

        createButton().assertIsEnabled()
    }

    @Test
    fun `the create button is disabled while the vault is being created`() {
        var isBusy by mutableStateOf(false)
        compose.setContent {
            TauthTheme { CreateVaultScreen(onCreate = { captured = it }, isBusy = isBusy) }
        }
        acknowledge()
        typePassword("aB3!efghij")
        typeConfirmation("aB3!efghij")

        compose.runOnIdle { isBusy = true }

        createButton().assertIsNotEnabled()
    }

    @Test
    fun `a password on the common list submits at the minimum length`() {
        show()
        acknowledge()
        typePassword("password")
        typeConfirmation("password")

        createButton().performClick()

        compose.runOnIdle { assertContentEquals("password".toCharArray(), captured) }
    }

    @Test
    fun `the password handed over is the one typed`() {
        show()
        acknowledge()
        typePassword("aB3!efghijklmnop")
        typeConfirmation("aB3!efghijklmnop")

        createButton().performClick()

        compose.runOnIdle { assertContentEquals("aB3!efghijklmnop".toCharArray(), captured) }
    }

    @Test
    fun `a password short of the minimum length hands over nothing`() {
        show()
        acknowledge()
        typePassword("aB3!efg")
        typeConfirmation("aB3!efg")

        createButton().performClick()

        compose.runOnIdle { assertNull(captured) }
    }

    // The Done action of either password field runs the submit path with no button between it and
    // the vault, so the three requirements are asserted again on that route.
    @Test
    fun `an entry submitted with the keyboard hands over the password`() {
        show()
        acknowledge()
        typePassword("aB3!efghij")
        typeConfirmation("aB3!efghij")

        passwordField().performImeAction()

        compose.runOnIdle { assertContentEquals("aB3!efghij".toCharArray(), captured) }
    }

    @Test
    fun `a keyboard submission short of the minimum length hands over nothing`() {
        show()
        acknowledge()
        typePassword("aB3!efg")
        typeConfirmation("aB3!efg")

        passwordField().performImeAction()

        compose.runOnIdle { assertNull(captured) }
    }

    @Test
    fun `a keyboard submission with the note unacknowledged hands over nothing`() {
        show()
        typePassword("aB3!efghij")
        typeConfirmation("aB3!efghij")

        passwordField().performImeAction()

        compose.runOnIdle { assertNull(captured) }
    }

    @Test
    fun `a keyboard submission with a differing confirmation hands over nothing`() {
        show()
        acknowledge()
        typePassword("aB3!efghij")
        typeConfirmation("aB3!efghik")

        passwordField().performImeAction()

        compose.runOnIdle { assertNull(captured) }
    }

    @Test
    fun `a password short of the minimum length says how long one has to be`() {
        show()

        typePassword("aB3!efg")

        compose.onNodeWithText("Use at least 8 characters").assertIsDisplayed()
    }

    @Test
    fun `the meter reports a common password as weak`() {
        show()

        // Fair by shape — nine characters over two classes — and Weak only because it is listed.
        typePassword("password1")

        compose.onNodeWithText("Strength: Weak").assertIsDisplayed()
    }

    @Test
    fun `the meter reports a long password of four classes as strong`() {
        show()

        typePassword("aB3!efghijklmnop")

        compose.onNodeWithText("Strength: Strong").assertIsDisplayed()
    }

    @Test
    fun `a failed write shows the write message`() {
        show(error = VaultError.Io(RuntimeException("no space")))

        compose.onNodeWithText("The vault file could not be written.").assertIsDisplayed()
    }

    @Test
    fun `a corrupt vault shows the damaged-file message`() {
        show(error = VaultError.Corrupt("header"))

        compose.onNodeWithText("The vault file was written but reads back damaged.").assertIsDisplayed()
    }

    @Test
    fun `an existing vault file shows its own message`() {
        show(error = VaultError.VaultFileExists)

        compose.onNodeWithText("A vault already exists at this location.").assertIsDisplayed()
    }

    // A password that does not open the vault and a vault that reads back damaged mean different
    // things to the person holding the password, and neither message may stand in for the other.
    @Test
    fun `a wrong password shows the password message`() {
        show(error = VaultError.WrongPassword)

        compose.onNodeWithText("The vault was created but the password did not open it.").assertIsDisplayed()
    }

    @Test
    fun `a wrong password does not show the damaged-file message`() {
        show(error = VaultError.WrongPassword)

        compose.onNodeWithText("The vault file was written but reads back damaged.").assertDoesNotExist()
    }

    @Test
    fun `an integrity failure shows the damaged-file message`() {
        show(error = VaultError.IntegrityFailure)

        compose.onNodeWithText("The vault file was written but reads back damaged.").assertIsDisplayed()
    }

    // The creation writes the file and reads it back, so the file can be gone by the time it is read.
    @Test
    fun `a vault file gone by the read-back shows its own message`() {
        show(error = VaultError.NoVaultFile)

        compose.onNodeWithText("The vault was created but could not be found afterward.")
            .assertIsDisplayed()
    }

    @Test
    fun `a vault held by another process shows its own message`() {
        show(error = VaultError.LockedByAnotherProcess("vault.tauth"))

        compose.onNodeWithText("Another TAuth process is holding the vault file.").assertIsDisplayed()
    }

    @Test
    fun `a vault past the size the writer will produce shows its own message`() {
        show(error = VaultError.TooLarge(size = 2, limit = 1))

        compose.onNodeWithText("The vault is larger than the file format allows.").assertIsDisplayed()
    }

    @Test
    fun `a version the reader does not know shows its own message`() {
        show(error = VaultError.UnsupportedVersion(found = 2, supported = 1))

        compose.onNodeWithText("This vault was made by a newer version of TAuth.")
            .assertIsDisplayed()
    }

    @Test
    fun `a lock that overtook the creation shows its own message`() {
        show(error = VaultError.VaultClosed)

        compose.onNodeWithText("The vault locked while it was being created.").assertIsDisplayed()
    }

    // A secret that will not decode reads back as damage rather than as anything about the password.
    @Test
    fun `a secret that does not decode shows the damaged-file message`() {
        show(error = VaultError.InvalidSecret("invalid base32 character"))

        compose.onNodeWithText("The vault file was written but reads back damaged.").assertIsDisplayed()
    }

    @Test
    fun `an integrity failure does not show the password message`() {
        show(error = VaultError.IntegrityFailure)

        compose.onNodeWithText("The vault was created but the password did not open it.").assertDoesNotExist()
    }

    private fun show(isBusy: Boolean = false, error: VaultCreateError? = null) {
        compose.setContent {
            TauthTheme { CreateVaultScreen(onCreate = { captured = it }, isBusy = isBusy, error = error) }
        }
    }

    private fun acknowledge() = compose.onNodeWithText(ACKNOWLEDGE_LABEL).performClick()

    private fun passwordField(): SemanticsNodeInteraction = compose.onAllNodes(hasSetTextAction())[0]

    private fun typePassword(text: String) = passwordField().performTextInput(text)

    private fun typeConfirmation(text: String) = compose.onAllNodes(hasSetTextAction())[1].performTextInput(text)

    private fun createButton(): SemanticsNodeInteraction = compose.onNodeWithText(CREATE_LABEL)
}
