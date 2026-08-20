package com.panda.tauth.ui.unlock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import com.panda.tauth.session.LockReason
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultUnlockError
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

// The screen's own wording, repeated here as literals so a changed message fails the test that
// names it rather than following it.
private const val UNLOCK_LABEL = "Unlock"
private const val PROGRESS_LABEL = "Checking your password"
private const val WRONG_PASSWORD_MESSAGE = "That password is not correct."
private const val DAMAGED_FILE_MESSAGE = "The vault file is damaged and cannot be opened."
private const val IDLE_SUBTITLE = "Locked automatically after a period of inactivity."
private const val FOCUS_LOST_SUBTITLE = "Locked when the window lost focus."
private const val HIDDEN_TO_TRAY_SUBTITLE = "Locked when the window was hidden to the tray."
private const val MINIMISED_SUBTITLE = "Locked when the window was minimised."

class UnlockScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var captured: CharArray? = null

    @Test
    fun `the password field holds focus when the screen opens`() {
        show()

        passwordField().assertIsFocused()
    }

    @Test
    fun `the unlock button hands over the password`() {
        show()
        type("aB3!efghij")

        unlockButton().performClick()

        compose.runOnIdle { assertContentEquals("aB3!efghij".toCharArray(), captured) }
    }

    @Test
    fun `an entry submitted with the keyboard hands over the password`() {
        show()
        type("aB3!efghij")

        passwordField().performImeAction()

        compose.runOnIdle { assertContentEquals("aB3!efghij".toCharArray(), captured) }
    }

    @Test
    fun `an empty entry hands over nothing`() {
        show()

        unlockButton().performClick()

        compose.runOnIdle { assertNull(captured) }
    }

    // The two routes read one rule today, which is exactly what a route of its own has to hold in
    // place: an empty entry reaching the derivation is a zero-length password against the file.
    @Test
    fun `an empty entry submitted with the keyboard hands over nothing`() {
        show()

        passwordField().performImeAction()

        compose.runOnIdle { assertNull(captured) }
    }

    @Test
    fun `the unlock button is disabled before anything is typed`() {
        show()

        unlockButton().assertIsNotEnabled()
    }

    @Test
    fun `the unlock button is disabled while the derivation runs`() {
        var isBusy by mutableStateOf(false)
        compose.setContent { TauthTheme { UnlockScreen(onUnlock = { captured = it }, isBusy = isBusy) } }
        type("aB3!efghij")

        compose.runOnIdle { isBusy = true }

        unlockButton().assertIsNotEnabled()
    }

    @Test
    fun `a keyboard submission while the derivation runs hands over nothing`() {
        var isBusy by mutableStateOf(false)
        compose.setContent { TauthTheme { UnlockScreen(onUnlock = { captured = it }, isBusy = isBusy) } }
        type("aB3!efghij")

        compose.runOnIdle { isBusy = true }
        passwordField().performImeAction()

        compose.runOnIdle { assertNull(captured) }
    }

    @Test
    fun `the progress indicator is on screen while the derivation runs`() {
        show(isBusy = true)

        compose.onNodeWithContentDescription(PROGRESS_LABEL).assertIsDisplayed()
    }

    @Test
    fun `the progress indicator is absent while no derivation runs`() {
        show(isBusy = false)

        compose.onNodeWithContentDescription(PROGRESS_LABEL).assertDoesNotExist()
    }

    @Test
    fun `a wrong password shows the password message`() {
        show(error = VaultError.WrongPassword)

        compose.onNodeWithText(WRONG_PASSWORD_MESSAGE).assertIsDisplayed()
    }

    // A password that does not open the vault and a vault that reads back damaged mean different
    // things to the person holding the password, and neither message may stand in for the other.
    @Test
    fun `a wrong password does not show the damaged-file message`() {
        show(error = VaultError.WrongPassword)

        compose.onNodeWithText(DAMAGED_FILE_MESSAGE).assertDoesNotExist()
    }

    @Test
    fun `an integrity failure shows the damaged-file message`() {
        show(error = VaultError.IntegrityFailure)

        compose.onNodeWithText(DAMAGED_FILE_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `an integrity failure does not show the password message`() {
        show(error = VaultError.IntegrityFailure)

        compose.onNodeWithText(WRONG_PASSWORD_MESSAGE).assertDoesNotExist()
    }

    @Test
    fun `a missing vault file shows its own message`() {
        show(error = VaultError.NoVaultFile)

        compose.onNodeWithText("There is no vault file at this location.").assertIsDisplayed()
    }

    @Test
    fun `a corrupt vault shows the damaged-file message`() {
        show(error = VaultError.Corrupt("header"))

        compose.onNodeWithText(DAMAGED_FILE_MESSAGE).assertIsDisplayed()
    }

    // A secret the body carries that is not base32 comes back from the decode an unlock does after
    // the tag has authenticated, and reads to the person in front of it as the same damaged file.
    @Test
    fun `a secret that does not decode shows the damaged-file message`() {
        show(error = VaultError.InvalidSecret("not base32"))

        compose.onNodeWithText(DAMAGED_FILE_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `a version the reader does not know shows its own message`() {
        show(error = VaultError.UnsupportedVersion(found = 2, supported = 1))

        compose.onNodeWithText("This vault was made by a newer version of TAuth.")
            .assertIsDisplayed()
    }

    @Test
    fun `a failed read shows the read message`() {
        show(error = VaultError.Io(RuntimeException("disk gone")))

        compose.onNodeWithText("The vault file could not be read.").assertIsDisplayed()
    }

    @Test
    fun `a lock that overtook the derivation shows its own message`() {
        show(error = VaultError.VaultClosed)

        compose.onNodeWithText("The vault locked while your password was being checked.").assertIsDisplayed()
    }

    // A failed attempt leaves every part of the retry working: the button live, the field taking
    // characters, and the submit path handing the next password over with nothing in between.
    @Test
    fun `a failed attempt leaves the unlock button enabled`() {
        show(error = VaultError.WrongPassword)

        type("aB3!efghij")

        unlockButton().assertIsEnabled()
    }

    @Test
    fun `a failed attempt leaves the password field enabled`() {
        show(error = VaultError.WrongPassword)

        passwordField().assertIsEnabled()
    }

    @Test
    fun `a failed attempt hands over the next password typed`() {
        show(error = VaultError.WrongPassword)
        type("second-try")

        unlockButton().performClick()

        compose.runOnIdle { assertContentEquals("second-try".toCharArray(), captured) }
    }

    @Test
    fun `a failed attempt hands over the next password submitted with the keyboard`() {
        show(error = VaultError.WrongPassword)
        type("second-try")

        passwordField().performImeAction()

        compose.runOnIdle { assertContentEquals("second-try".toCharArray(), captured) }
    }

    // Read through the tag rather than the sentence, so the tag the three absence tests below look
    // for is the one a reported subtitle carries.
    @Test
    fun `an idle timeout is reported as a subtitle`() {
        show(lastReason = LockReason.Idle)

        compose.onNodeWithTag(UNLOCK_SUBTITLE_TAG).assertTextEquals(IDLE_SUBTITLE)
    }

    @Test
    fun `a focus loss is reported as a subtitle`() {
        show(lastReason = LockReason.FocusLost)

        compose.onNodeWithText(FOCUS_LOST_SUBTITLE).assertIsDisplayed()
    }

    @Test
    fun `a hide to the tray is reported as a subtitle`() {
        show(lastReason = LockReason.HiddenToTray)

        compose.onNodeWithText(HIDDEN_TO_TRAY_SUBTITLE).assertIsDisplayed()
    }

    @Test
    fun `a minimise is reported as a subtitle`() {
        show(lastReason = LockReason.Minimised)

        compose.onNodeWithText(MINIMISED_SUBTITLE).assertIsDisplayed()
    }

    // The tag is on whatever subtitle the screen draws, so these three see any text a reported
    // reason would put on screen rather than one particular sentence.
    @Test
    fun `a manual lock is not reported as a subtitle`() {
        show(lastReason = LockReason.Manual)

        compose.onNodeWithTag(UNLOCK_SUBTITLE_TAG).assertDoesNotExist()
    }

    @Test
    fun `an exit is not reported as a subtitle`() {
        show(lastReason = LockReason.Exit)

        compose.onNodeWithTag(UNLOCK_SUBTITLE_TAG).assertDoesNotExist()
    }

    @Test
    fun `a vault that has not been unlocked in this session shows no subtitle`() {
        show(lastReason = null)

        compose.onNodeWithTag(UNLOCK_SUBTITLE_TAG).assertDoesNotExist()
    }

    private fun show(isBusy: Boolean = false, error: VaultUnlockError? = null, lastReason: LockReason? = null) {
        compose.setContent {
            TauthTheme {
                UnlockScreen(
                    onUnlock = { captured = it },
                    isBusy = isBusy,
                    error = error,
                    lastReason = lastReason,
                )
            }
        }
    }

    private fun passwordField(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction())

    private fun type(text: String) = passwordField().performTextInput(text)

    private fun unlockButton(): SemanticsNodeInteraction = compose.onNodeWithText(UNLOCK_LABEL)
}
