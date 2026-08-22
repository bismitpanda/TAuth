package com.panda.tauth.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.panda.tauth.Outcome
import com.panda.tauth.session.CodeTicker
import com.panda.tauth.session.VaultSession
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultFile
import com.panda.tauth.vault.VaultReadError
import com.panda.tauth.vault.VaultWriteError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test

// The sentence the window carries, as a literal: what a launch without single-instance service costs
// the person in front of it is a save that replaces another window's.
private const val NOTICE =
    "TAuth cannot tell whether another copy is running. If one is, the last save replaces the " +
        "other's changes."

// A location holding no vault, which opens the create screen without a derivation.
private const val CREATE_TITLE = "Create your vault"

private object NoVaultFile : VaultFile {
    override fun exists(): Boolean = false

    override fun read(): Outcome<ByteArray, VaultReadError> = Outcome.Failure(VaultError.NoVaultFile)

    // Every case here only renders, so this refuses rather than pretending to store what it is given.
    override fun write(bytes: ByteArray): Outcome<Unit, VaultWriteError> =
        Outcome.Failure(VaultError.Io(UnsupportedOperationException("this fake holds no vault")))
}

class SingleInstanceNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Default)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    @Test
    fun `a launch without single-instance service says so on screen`() {
        show(isSingleInstanceUnprotected = true)

        compose.onNodeWithText(NOTICE).assertIsDisplayed()
    }

    @Test
    fun `a launch holding the instance lock says nothing`() {
        show(isSingleInstanceUnprotected = false)

        compose.onNodeWithText(NOTICE).assertDoesNotExist()
    }

    @Test
    fun `the screen under the notice is drawn`() {
        show(isSingleInstanceUnprotected = true)

        compose.onNodeWithText(CREATE_TITLE).assertIsDisplayed()
    }

    private fun show(isSingleInstanceUnprotected: Boolean) {
        val session = VaultSession(NoVaultFile, {}, scope)
        val preferences = RecordingPreferences().state
        compose.setContent {
            TauthTheme {
                TAuthApp(
                    session = session,
                    ticker = CodeTicker(session),
                    clipboard = { _, _ -> CopyResult.COPIED },
                    preferences = preferences,
                    modifier = Modifier.fillMaxSize(),
                    isSingleInstanceUnprotected = isSingleInstanceUnprotected,
                )
            }
        }
    }
}
