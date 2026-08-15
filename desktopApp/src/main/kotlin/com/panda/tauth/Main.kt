package com.panda.tauth

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.panda.tauth.session.CodeTicker
import com.panda.tauth.session.LockReason
import com.panda.tauth.session.SessionClipboard
import com.panda.tauth.session.VaultSession
import com.panda.tauth.settings.PreferencesStore
import com.panda.tauth.ui.ClipboardCopy
import com.panda.tauth.ui.CopyResult
import com.panda.tauth.ui.TAuthApp
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.vault.VaultStore

private const val WINDOW_TITLE = "TAuth"

private val LOGGER = System.getLogger("com.panda.tauth.Main")

fun main() = application {
    // The scope belongs to the application composition, so shutting the application down cancels the
    // clipboard's pending clear and any lock the session has scheduled.
    val scope = rememberCoroutineScope()
    val clipboard = remember(scope) { ClipboardService(scope) }
    val session = remember(scope) {
        VaultSession(VaultStore(), SessionClipboard { clipboard.clearIfHoldsOwnValue() }, scope)
    }
    val ticker = remember(session) { CodeTicker(session) }
    val preferences = remember { PreferencesStore().load() }

    Window(
        onCloseRequest = {
            // The key is zeroed and the clipboard is taken back before the process ends, rather than
            // being left to whatever the operating system does with an exiting application's heap.
            session.lock(LockReason.Exit)
            exitApplication()
        },
        title = WINDOW_TITLE,
    ) {
        TauthTheme {
            TAuthApp(
                session = session,
                ticker = ticker,
                clipboard = clipboard.asCopy(),
                preferences = preferences,
            )
        }
    }
}

// The copied text is a code or a complete credential, so neither the message nor the log line carries
// it; the platform's failure detail stays here.
private fun ClipboardService.asCopy(): ClipboardCopy = ClipboardCopy { text, clearAfterSeconds ->
    when (val outcome = copy(text, clearAfterSeconds)) {
        is Outcome.Success -> CopyResult.COPIED

        is Outcome.Failure -> {
            LOGGER.log(System.Logger.Level.WARNING, "the clipboard refused a copy: ${outcome.error::class.simpleName}")
            CopyResult.REFUSED
        }
    }
}
