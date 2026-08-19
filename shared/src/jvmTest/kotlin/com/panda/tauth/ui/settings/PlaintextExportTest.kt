package com.panda.tauth.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.panda.tauth.Outcome
import com.panda.tauth.ui.components.DISCLOSURE_PASSWORD_TAG
import com.panda.tauth.ui.components.DISCLOSURE_STATEMENT_TAG
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.vault.ExportFormat
import com.panda.tauth.vault.PasswordGateError
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Rule
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The flow's own wording, written out here so a changed label fails the test naming it.
private const val CONTINUE = "Continue"
private const val CANCEL = "Cancel"
private const val DISCLOSE = "Disclose"
private const val JSON = "JSON"
private const val URI_LIST = "otpauth:// URIs"

private const val RIGHT_PASSWORD = "correct horse battery staple"
private const val WRONG_PASSWORD = "wrong"

private const val ACCOUNTS = 3
private const val DISCLOSED = "otpauth://totp/GitHub:alice?secret=AAAA\n"

class PlaintextExportTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private var disclosedFormat: ExportFormat? = null
    private var written: String? = null
    private var writtenFormat: ExportFormat? = null
    private var finishes = 0
    private var reportedError: FileWriteError? = null
    private var writeAnswer: Outcome<Unit, FileWriteError> = Outcome.Success(Unit)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    @Test
    fun `the warning states what the file holds`() {
        show()

        compose.onNodeWithTag(PLAINTEXT_WARNING_TAG).assertIsDisplayed()
    }

    // Every account leaves at once, so the count is what the gate states rather than one name.
    @Test
    fun `the gate states how many accounts leave`() {
        show()

        compose.onNodeWithText(CONTINUE).performClick()

        compose.onNodeWithTag(DISCLOSURE_STATEMENT_TAG).assertIsDisplayed()
    }

    @Test
    fun `no password is asked for before the warning is read`() {
        show()

        compose.onNodeWithTag(DISCLOSURE_PASSWORD_TAG).assertDoesNotExist()
    }

    @Test
    fun `nothing is disclosed while the warning stands`() {
        show()

        compose.runOnIdle { assertNull(disclosedFormat) }
    }

    @Test
    fun `dismissing the warning writes nothing`() {
        show()

        compose.onNodeWithText(CANCEL).performClick()

        compose.runOnIdle { assertNull(written) }
    }

    @Test
    fun `dismissing the warning ends the request`() {
        show()

        compose.onNodeWithText(CANCEL).performClick()

        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun `the format chosen is the format disclosed`() {
        show()

        compose.onNodeWithText(URI_LIST).performClick()
        compose.onNodeWithText(CONTINUE).performClick()
        confirmWith(RIGHT_PASSWORD)

        compose.runOnIdle { assertEquals(ExportFormat.URI_LIST, disclosedFormat) }
    }

    @Test
    fun `the format chosen is the format written`() {
        show()

        compose.onNodeWithText(URI_LIST).performClick()
        compose.onNodeWithText(CONTINUE).performClick()
        confirmWith(RIGHT_PASSWORD)

        compose.runOnIdle { assertEquals(ExportFormat.URI_LIST, writtenFormat) }
    }

    @Test
    fun `a json export is what the flow opens on`() {
        show()

        compose.onNodeWithText(JSON).assertIsDisplayed()
        compose.onNodeWithText(CONTINUE).performClick()
        confirmWith(RIGHT_PASSWORD)

        compose.runOnIdle { assertEquals(ExportFormat.JSON, disclosedFormat) }
    }

    @Test
    fun `an accepted password writes what was disclosed`() {
        show()

        compose.onNodeWithText(CONTINUE).performClick()
        confirmWith(RIGHT_PASSWORD)

        compose.runOnIdle { assertEquals(DISCLOSED, written) }
    }

    @Test
    fun `a refused password writes nothing`() {
        show()

        compose.onNodeWithText(CONTINUE).performClick()
        confirmWith(WRONG_PASSWORD)

        compose.runOnIdle { assertNull(written) }
    }

    @Test
    fun `a refused password leaves the gate standing`() {
        show()

        compose.onNodeWithText(CONTINUE).performClick()
        confirmWith(WRONG_PASSWORD)

        compose.onNodeWithTag(DISCLOSURE_STATEMENT_TAG).assertIsDisplayed()
    }

    @Test
    fun `a refused password says the password did not open the vault`() {
        show()

        compose.onNodeWithText(CONTINUE).performClick()
        confirmWith(WRONG_PASSWORD)

        compose.onNodeWithText("That password did not open the vault.").assertIsDisplayed()
    }

    @Test
    fun `a completed export ends the request`() {
        show()

        compose.onNodeWithText(CONTINUE).performClick()
        confirmWith(RIGHT_PASSWORD)

        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun `a refused destination is reported`() {
        writeAnswer = Outcome.Failure(ExportError.Io(IOException("read-only file system")))
        show()

        compose.onNodeWithText(CONTINUE).performClick()
        confirmWith(RIGHT_PASSWORD)

        compose.runOnIdle { assertEquals(ExportError.Io::class, reportedError!!::class) }
    }

    @Test
    fun `a destination that took the file reports nothing`() {
        show()

        compose.onNodeWithText(CONTINUE).performClick()
        confirmWith(RIGHT_PASSWORD)

        compose.runOnIdle { assertNull(reportedError) }
    }

    private fun confirmWith(password: String) {
        compose.onNodeWithTag(DISCLOSURE_PASSWORD_TAG)
            .onChildren()
            .filterToOne(hasSetTextAction())
            .performTextInput(password)
        compose.onNodeWithText(DISCLOSE).performClick()
    }

    private fun show() {
        compose.setContent {
            TauthTheme {
                PlaintextExport(
                    isRequested = true,
                    accountCount = ACCOUNTS,
                    scope = scope,
                    onDisclose = { password, format ->
                        if (password.concatToString() == RIGHT_PASSWORD) {
                            disclosedFormat = format
                            Outcome.Success(DISCLOSED)
                        } else {
                            Outcome.Failure<PasswordGateError>(VaultError.WrongPassword)
                        }
                    },
                    onWrite = { text, format ->
                        written = text
                        writtenFormat = format
                        writeAnswer
                    },
                    onFinished = { finishes++ },
                    onWriteError = { reportedError = it },
                )
            }
        }
    }
}
