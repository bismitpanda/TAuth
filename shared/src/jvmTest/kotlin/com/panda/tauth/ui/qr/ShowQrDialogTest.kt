package com.panda.tauth.ui.qr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.ui.hotpRow
import com.panda.tauth.ui.settings.ExportError
import com.panda.tauth.ui.settings.FileWriteError
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.ui.totpRow
import org.junit.Rule
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

private const val COPY_URI = "Copy URI"
private const val SAVE = "Save as PNG"
private const val CLOSE = "Close"
private const val SYMBOL = "QR code"
private const val UNAVAILABLE = "This account does not fit in a QR code, so its URI has to be copied instead."

private const val IDLE_MILLIS = 60_000L
private const val JUST_INSIDE_MILLIS = 59_000L
private const val OVERSHOOT_MILLIS = 2_000L

// Three modules on a side, which is no real symbol and is all the drawing needs to have something to
// lay down.
private val SYMBOL_FIXTURE = QrSymbol(3, BooleanArray(9) { it % 2 == 0 })

class ShowQrDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private var copies = 0
    private var dismissals = 0
    private var saves = 0

    @Test
    fun `the symbol is drawn`() {
        show()

        compose.onNodeWithContentDescription(SYMBOL).assertIsDisplayed()
    }

    @Test
    fun `the account the symbol carries is named beneath it`() {
        show()

        compose.onNodeWithTag(QR_IDENTITY_TAG).assertIsDisplayed()
    }

    @Test
    fun `the account is named by its issuer and account name`() {
        show()

        compose.onNodeWithText("GitHub — alice").assertIsDisplayed()
    }

    // Scanning takes the counter as it stands, so the value that leaves is on screen beside the
    // symbol rather than left to be inferred from the account.
    @Test
    fun `an hotp account states the counter the symbol carries`() {
        show(entry = hotpRow(counter = 41uL))

        compose.onNodeWithTag(QR_COUNTER_TAG).assertIsDisplayed()
    }

    @Test
    fun `an hotp account states what scanning that counter does`() {
        show(entry = hotpRow(counter = 41uL))

        compose.onNodeWithText("41 — $QR_COUNTER_NOTE").assertIsDisplayed()
    }

    @Test
    fun `a totp account states no counter`() {
        show()

        compose.onNodeWithTag(QR_COUNTER_TAG).assertDoesNotExist()
    }

    @Test
    fun `a uri that does not fit says so`() {
        show(symbol = null)

        compose.onNodeWithText(UNAVAILABLE).assertIsDisplayed()
    }

    @Test
    fun `a uri that does not fit draws no symbol`() {
        show(symbol = null)

        compose.onNodeWithContentDescription(SYMBOL).assertDoesNotExist()
    }

    @Test
    fun `copying the uri reports it`() {
        show()

        compose.onNodeWithText(COPY_URI).performClick()

        compose.runOnIdle { assertEquals(1, copies) }
    }

    @Test
    fun `closing reports a dismissal`() {
        show()

        compose.onNodeWithText(CLOSE).performClick()

        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    // A credential left on screen is readable by anything that can see the screen, so the dialog
    // does not outlast the person who opened it.
    @Test
    fun `the dialog closes after a minute with nobody at it`() {
        show()

        compose.mainClock.advanceTimeBy(IDLE_MILLIS + OVERSHOOT_MILLIS)

        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun `the dialog stands while it is being used`() {
        show()

        compose.mainClock.advanceTimeBy(JUST_INSIDE_MILLIS)
        compose.onNodeWithText(COPY_URI).performClick()
        compose.mainClock.advanceTimeBy(JUST_INSIDE_MILLIS)

        compose.runOnIdle { assertEquals(0, dismissals) }
    }

    @Test
    fun `a save is offered where there is somewhere to write`() {
        show()

        compose.onNodeWithText(SAVE).assertIsDisplayed()
    }

    @Test
    fun `no save is offered with nowhere to write`() {
        show(canSave = false)

        compose.onNodeWithText(SAVE).assertDoesNotExist()
    }

    @Test
    fun `saving reports the request`() {
        show()

        compose.onNodeWithText(SAVE).performClick()

        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun `a running save takes no second request`() {
        show(isSaving = true)

        compose.onNodeWithText(SAVE).performClick()

        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test
    fun `a destination that cannot restrict the image says so`() {
        show(saveError = ExportError.NotRestricted)

        compose.onNodeWithTag(QR_SAVE_PROBLEM_TAG)
            .assertTextEquals("That location cannot keep the image to you alone, so nothing was written there.")
    }

    @Test
    fun `a destination that could not be written says so`() {
        show(saveError = ExportError.Io(IOException("read-only file system")))

        compose.onNodeWithTag(QR_SAVE_PROBLEM_TAG)
            .assertTextEquals("The image could not be written to that location.")
    }

    // The save opens a file dialog the user is standing in front of, and the interval closing the
    // dialog underneath it would cancel the write it is waiting for.
    @Test
    fun `the dialog stands while a save is in flight`() {
        show(isSaving = true)

        compose.mainClock.advanceTimeBy(IDLE_MILLIS + OVERSHOOT_MILLIS)

        compose.runOnIdle { assertEquals(0, dismissals) }
    }

    private fun show(
        entry: UnlockedEntry = totpRow(),
        symbol: QrSymbol? = SYMBOL_FIXTURE,
        canSave: Boolean = true,
        isSaving: Boolean = false,
        saveError: FileWriteError? = null,
    ) {
        compose.setContent {
            TauthTheme {
                ShowQrDialog(
                    entry = entry,
                    symbol = symbol,
                    onCopyUri = { copies++ },
                    onDismiss = { dismissals++ },
                    onSaveImage = if (canSave) ({ saves++ }) else null,
                    isSaving = isSaving,
                    saveError = saveError,
                )
            }
        }
    }
}
