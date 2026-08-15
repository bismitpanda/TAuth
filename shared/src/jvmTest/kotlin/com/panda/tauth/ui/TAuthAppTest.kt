package com.panda.tauth.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.panda.tauth.Outcome
import com.panda.tauth.session.CodeTicker
import com.panda.tauth.session.LockReason
import com.panda.tauth.session.SessionClipboard
import com.panda.tauth.session.VaultSession
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.valueOrNull
import com.panda.tauth.vault.TEST_SECRET
import com.panda.tauth.vault.VaultBody
import com.panda.tauth.vault.VaultCodec
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultFile
import com.panda.tauth.vault.totpEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

private const val PASSWORD = "correct horse battery staple"

// Fixed, so the codes the list draws are the codes of one instant rather than of whenever the suite
// happens to run.
private val FIXED_CLOCK = object : Clock {
    override fun now(): Instant = Instant.parse("2026-08-15T12:00:00Z")
}

private const val CREATE_TITLE = "Create your vault"
private const val CREATE_LABEL = "Create vault"
private const val ACKNOWLEDGE_LABEL = "I understand that losing my master password loses every stored secret"
private const val UNLOCK_TITLE = "Unlock your vault"
private const val LIST_TITLE = "Accounts"
private const val ADD_TITLE_TEXT = "Add an account"

private const val ADD_LABEL_TEXT = "Add account"
private const val LOCK_LABEL_TEXT = "Lock"
private const val CANCEL_LABEL_TEXT = "Cancel"
private const val MENU_LABEL = "More"
private const val DELETE_LABEL = "Delete"
private const val DELETE_CONFIRM_LABEL = "Delete account"
private const val WRITE_REFUSED_MESSAGE = "Another TAuth process is holding the vault file."
private const val EDIT_LABEL = "Edit"
private const val EDIT_TITLE = "Edit account"
private const val EDIT_SAVE_LABEL = "Save changes"
private const val EDIT_ACCOUNT_TAG = "edit-account-name"
private const val ADD_URI_TAG = "add-uri"
private const val ADD_SAVE_LABEL = "Save account"

// A second account, distinct from the one the vault was created with, so the list showing it is the
// list showing something the add path put there.
private const val PASTED_URI = "otpauth://totp/Zendesk:carol?secret=$TEST_SECRET&issuer=Zendesk"
private const val PASTED_ACCOUNT = "carol"
private const val RENAMED_ACCOUNT = "erin"

private const val WAIT_MILLIS = 30_000L

// One derivation for the whole class rather than one per test: Argon2id is priced to be slow.
private val VAULT by lazy {
    val body = VaultBody(entries = listOf(totpEntry().copy(secret = TEST_SECRET)))
    checkNotNull(VaultCodec.create(PASSWORD.toCharArray(), body).valueOrNull)
}

// The write refuses the way it does when another process holds the vault's lock file, which leaves
// the previous vault whole on disk.
private val WRITE_REFUSED = VaultError.LockedByAnotherProcess("/nowhere/vault.tauth.lock")

private class MemoryVaultFile(var contents: ByteArray?, private val isWritable: Boolean = true) : VaultFile {
    override fun exists(): Boolean = contents != null

    override fun read(): Outcome<ByteArray, VaultError> =
        contents?.let { Outcome.Success(it) } ?: Outcome.Failure(VaultError.NoVaultFile)

    override fun write(bytes: ByteArray): Outcome<Unit, VaultError> {
        if (!isWritable) return Outcome.Failure(WRITE_REFUSED)
        contents = bytes
        return Outcome.Success(Unit)
    }
}

// Routing alone: which screen the window carries follows the session's state rather than a stack the
// screens push on to.
class TAuthAppTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Default)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    @Test
    fun `a location holding no vault opens the create screen`() {
        show(MemoryVaultFile(null))

        compose.onNodeWithText(CREATE_TITLE).assertIsDisplayed()
    }

    // A creation runs through the same Unlocking state an unlock does, and the screen that took the
    // password is the one that reports the derivation. Nothing else drives a create through the app.
    @Test
    fun `a created vault opens the account list`() {
        show(MemoryVaultFile(null))
        waitForText(CREATE_TITLE)

        acknowledgeAndCreate()

        waitForText(LIST_TITLE)
    }

    @Test
    fun `a location holding a vault opens the unlock screen`() {
        show(MemoryVaultFile(VAULT))

        compose.onNodeWithText(UNLOCK_TITLE).assertIsDisplayed()
    }

    @Test
    fun `an unlocked vault opens the account list`() {
        show(MemoryVaultFile(VAULT))

        unlock()

        waitForText(LIST_TITLE)
    }

    @Test
    fun `locking from the list returns to the unlock screen`() {
        show(MemoryVaultFile(VAULT))
        unlock()
        waitForText(LIST_TITLE)

        compose.onNodeWithText(LOCK_LABEL_TEXT).performClick()

        waitForText(UNLOCK_TITLE)
    }

    @Test
    fun `the add button opens the add screen inside the unlocked graph`() {
        show(MemoryVaultFile(VAULT))
        unlock()
        waitForText(LIST_TITLE)

        compose.onNodeWithText(ADD_LABEL_TEXT).performClick()

        waitForText(ADD_TITLE_TEXT)
    }

    @Test
    fun `cancelling the add screen returns to the list`() {
        show(MemoryVaultFile(VAULT))
        unlock()
        waitForText(LIST_TITLE)
        compose.onNodeWithText(ADD_LABEL_TEXT).performClick()
        waitForText(ADD_TITLE_TEXT)

        compose.onNodeWithText(CANCEL_LABEL_TEXT).performClick()

        waitForText(LIST_TITLE)
    }

    @Test
    fun `a lock taken from the add screen returns to the unlock screen`() {
        val session = show(MemoryVaultFile(VAULT))
        unlock()
        waitForText(LIST_TITLE)
        compose.onNodeWithText(ADD_LABEL_TEXT).performClick()
        waitForText(ADD_TITLE_TEXT)

        compose.runOnIdle { session.lock(LockReason.Manual) }

        waitForText(UNLOCK_TITLE)
    }

    // The unlock screen is drawn for a locked vault whatever route the graph was left on, so what
    // establishes that the lock left the destination is where the next unlock lands.
    @Test
    fun `a vault locked from the add screen unlocks onto the account list`() {
        val session = show(MemoryVaultFile(VAULT))
        unlock()
        waitForText(LIST_TITLE)
        compose.onNodeWithText(ADD_LABEL_TEXT).performClick()
        waitForText(ADD_TITLE_TEXT)
        compose.runOnIdle { session.lock(LockReason.Manual) }
        waitForText(UNLOCK_TITLE)

        unlock()

        waitForText(LIST_TITLE)
    }

    // Everything from the paste to the vault write to the row the list draws afterwards. Nothing else
    // in the suite turns a confirmed account into a stored one.
    @Test
    fun `an account saved from the add screen appears on the list`() {
        show(MemoryVaultFile(VAULT))
        unlock()
        waitForText(LIST_TITLE)
        compose.onNodeWithText(ADD_LABEL_TEXT).performClick()
        waitForText(ADD_TITLE_TEXT)

        compose.onNodeWithTag(ADD_URI_TAG).performTextInput(PASTED_URI)
        compose.onNodeWithText(ADD_SAVE_LABEL).performScrollTo().performClick()

        // The add screen's own preview draws the account name too, so the assertion waits until the
        // list is what is on screen before looking for the row.
        waitForText(LIST_TITLE)
        compose.onNodeWithText(PASTED_ACCOUNT).assertIsDisplayed()
    }

    @Test
    fun `an account saved from the add screen reaches the vault file`() {
        val file = MemoryVaultFile(VAULT)
        show(file)
        unlock()
        waitForText(LIST_TITLE)
        compose.onNodeWithText(ADD_LABEL_TEXT).performClick()
        waitForText(ADD_TITLE_TEXT)

        compose.onNodeWithTag(ADD_URI_TAG).performTextInput(PASTED_URI)
        compose.onNodeWithText(ADD_SAVE_LABEL).performScrollTo().performClick()
        waitForText(LIST_TITLE)

        assertEquals(listOf("alice", PASTED_ACCOUNT), storedNames(file))
    }

    // The edit destination sits inside the unlocked graph and nothing else here opens it.
    @Test
    fun `the row overflow menu opens the edit destination`() {
        show(MemoryVaultFile(VAULT))
        unlock()
        waitForText(LIST_TITLE)

        compose.onAllNodesWithText(MENU_LABEL)[0].performClick()
        compose.onNodeWithText(EDIT_LABEL).performClick()

        waitForText(EDIT_TITLE)
    }

    @Test
    fun `a rename made on the edit screen reaches the vault file`() {
        val file = MemoryVaultFile(VAULT)
        show(file)
        unlock()
        waitForText(LIST_TITLE)
        compose.onAllNodesWithText(MENU_LABEL)[0].performClick()
        compose.onNodeWithText(EDIT_LABEL).performClick()
        waitForText(EDIT_TITLE)

        compose.onNodeWithTag(EDIT_ACCOUNT_TAG).performScrollTo().performTextClearance()
        compose.onNodeWithTag(EDIT_ACCOUNT_TAG).performTextInput(RENAMED_ACCOUNT)
        compose.onNodeWithText(EDIT_SAVE_LABEL).performScrollTo().performClick()
        waitForText(LIST_TITLE)

        assertEquals(listOf(RENAMED_ACCOUNT), storedNames(file))
    }

    // A refused delete is the list's failure and belongs on the list, not on whichever destination is
    // opened next.
    @Test
    fun `a refused delete is reported on the account list`() {
        show(MemoryVaultFile(VAULT, isWritable = false))
        unlock()
        waitForText(LIST_TITLE)

        compose.onAllNodesWithText(MENU_LABEL)[0].performClick()
        compose.onNodeWithText(DELETE_LABEL).performClick()
        compose.onNodeWithText(DELETE_CONFIRM_LABEL).performClick()

        waitForText(WRITE_REFUSED_MESSAGE)
    }

    @Test
    fun `a refused delete does not follow the user onto the add screen`() {
        show(MemoryVaultFile(VAULT, isWritable = false))
        unlock()
        waitForText(LIST_TITLE)
        compose.onAllNodesWithText(MENU_LABEL)[0].performClick()
        compose.onNodeWithText(DELETE_LABEL).performClick()
        compose.onNodeWithText(DELETE_CONFIRM_LABEL).performClick()
        waitForText(WRITE_REFUSED_MESSAGE)

        compose.onNodeWithText(ADD_LABEL_TEXT).performClick()
        waitForText(ADD_TITLE_TEXT)

        compose.onNodeWithText(WRITE_REFUSED_MESSAGE).assertDoesNotExist()
    }

    private fun unlock() {
        compose.onNode(hasSetTextAction()).performTextInput(PASSWORD)
        compose.onNodeWithText("Unlock").performClick()
    }

    // The create screen carries two password fields; the second is the confirmation.
    private fun acknowledgeAndCreate() {
        compose.onNodeWithText(ACKNOWLEDGE_LABEL).performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(PASSWORD)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput(PASSWORD)
        compose.onNodeWithText(CREATE_LABEL).performClick()
    }

    // Read back through the codec rather than off the screen, so what is asserted is what the file
    // holds and not what the session published.
    private fun storedNames(file: MemoryVaultFile): List<String> =
        checkNotNull(VaultCodec.open(checkNotNull(file.contents), PASSWORD.toCharArray()).valueOrNull)
            .use { open -> open.body.entries.sortedBy { it.orderIndex }.map { it.accountName } }

    private fun waitForText(text: String) {
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun show(file: MemoryVaultFile): VaultSession {
        val session = VaultSession(file, SessionClipboard {}, scope)
        compose.setContent {
            TauthTheme {
                TAuthApp(
                    session = session,
                    ticker = CodeTicker(session, FIXED_CLOCK),
                    clipboard = { _, _ -> CopyResult.COPIED },
                    modifier = Modifier.fillMaxSize(),
                    clock = FIXED_CLOCK,
                )
            }
        }
        return session
    }
}
