package com.panda.tauth.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.panda.tauth.Outcome
import com.panda.tauth.settings.SecurityPolicy
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// What a settings action is doing and what the last one reported. The policy lives in the encrypted
// body, which is unreadable for the length of a rewrite the screen has to stay standing through.
@Stable
internal class SettingsWork {
    var policy: SecurityPolicy by mutableStateOf(SecurityPolicy())
        private set

    var isBusy: Boolean by mutableStateOf(false)
        private set

    var error: VaultError? by mutableStateOf(null)
        private set

    // Held apart from the slot above, which carries what a vault write refused. An export writes
    // nothing to the vault, so neither half of one belongs there.
    var exportError: ExportError? by mutableStateOf(null)
        private set

    fun adopt(published: SecurityPolicy) {
        policy = published
    }

    fun clearError() {
        error = null
        exportError = null
    }

    // The vault is read and the copy placed, and which of the two failed is what the message the user
    // reads has to turn on.
    fun export(
        scope: CoroutineScope,
        read: suspend () -> Outcome<ByteArray, VaultError>,
        place: suspend (ByteArray) -> Outcome<Unit, ExportError>,
    ) {
        isBusy = true
        error = null
        exportError = null
        scope.launch {
            try {
                exportError = when (val bytes = read()) {
                    is Outcome.Failure -> ExportError.VaultUnreadable(bytes.error)
                    is Outcome.Success -> (place(bytes.value) as? Outcome.Failure)?.error
                }
            } finally {
                isBusy = false
            }
        }
    }

    // The arrays are copies the password fields handed over and no holder owns them. A lock or a
    // closing window cancels this scope mid-derivation, so the zeroing sits in a finally.
    fun run(scope: CoroutineScope, vararg passwords: CharArray, work: suspend () -> Outcome<*, VaultError>) {
        isBusy = true
        error = null
        scope.launch {
            try {
                error = (work() as? Outcome.Failure)?.error
            } finally {
                passwords.forEach { it.fill(Char.MIN_VALUE) }
                isBusy = false
            }
        }
    }
}
