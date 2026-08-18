package com.panda.tauth.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.panda.tauth.Outcome
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.vault.DiscloseError
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

const val DISCLOSURE_TITLE = "Confirm your master password"
const val DISCLOSURE_CONFIRM_LABEL = "Disclose"
const val DISCLOSURE_CANCEL_LABEL = "Cancel"

private const val PASSWORD_LABEL = "Master password"
private const val PROGRESS_LABEL = "Checking your password"

// Carried by the statement whenever the gate is on screen, so its absence is as observable as its
// text and a caller that forgot to say what leaves the vault is visible.
const val DISCLOSURE_STATEMENT_TAG = "disclosure-statement"

// Names the gate's own field, which shares a screen with whatever fields the caller already has.
const val DISCLOSURE_PASSWORD_TAG = "disclosure-password"

// The vault being open is not the permission: an unattended window is open too. This performs no
// check itself — it collects the password and the caller verifies it before disclosing anything.
@Composable
fun SecretDisclosureGate(
    statement: String,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    error: DiscloseError? = null,
) {
    val password = remember { PasswordFieldState() }
    val focusRequester = remember { FocusRequester() }

    // destroy() is the owner's: it zeroes and stops the holder taking another character.
    DisposableEffect(Unit) {
        onDispose { password.destroy() }
    }

    // The dialog exists to take one password, so it opens on the field.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val spacing = LocalSpacing.current
    val canConfirm = password.length > 0 && !isBusy
    // The Done action of the field runs this with no button in the way, so both rules are enforced
    // here rather than by what the button does.
    val submit: () -> Unit = {
        if (canConfirm) {
            onConfirm(password.copyValue())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(DISCLOSURE_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                Text(
                    statement,
                    modifier = Modifier.testTag(DISCLOSURE_STATEMENT_TAG),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(PASSWORD_LABEL, style = MaterialTheme.typography.labelLarge)
                // The field takes characters while a check runs, so a password typed wrong is edited
                // rather than waited out. What the check blocks is a second one behind it.
                PasswordField(
                    state = password,
                    modifier = Modifier.fillMaxWidth().testTag(DISCLOSURE_PASSWORD_TAG),
                    focusRequester = focusRequester,
                    onSubmit = submit,
                )
                error?.let { failure ->
                    Text(
                        messageFor(failure),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = PROGRESS_LABEL },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = canConfirm) { Text(DISCLOSURE_CONFIRM_LABEL) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text(DISCLOSURE_CANCEL_LABEL) }
        },
    )
}

@Stable
class DisclosureState<T, E : VaultError> {
    var request: T? by mutableStateOf(null)
        private set

    var isBusy: Boolean by mutableStateOf(false)
        private set

    var error: E? by mutableStateOf(null)
        private set

    // Bumped by anything that ends the gate a check belongs to. A check finding its own count stale
    // discloses nothing, so a dismissal mid-check cannot land a credential on the clipboard.
    private var generation = 0

    fun ask(subject: T) {
        generation++
        isBusy = false
        error = null
        request = subject
    }

    fun cancel() {
        generation++
        isBusy = false
        request = null
        error = null
    }

    // The gate stays up on a refusal and the disclosed value is released on no other path, so a
    // password that does not open the vault discloses nothing at all.
    fun confirm(
        scope: CoroutineScope,
        password: CharArray,
        disclose: suspend (T, CharArray) -> Outcome<String, E>,
        onDisclosed: (String) -> Unit,
    ) {
        val subject = request ?: run {
            password.fill(Char.MIN_VALUE)
            return
        }
        val attempt = ++generation
        isBusy = true
        error = null
        scope.launch {
            // A lock cancels this scope mid-derivation, and the array is a copy no holder zeroes.
            try {
                val outcome = disclose(subject, password)
                if (attempt != generation) return@launch
                isBusy = false
                when (outcome) {
                    is Outcome.Failure -> error = outcome.error

                    is Outcome.Success -> {
                        request = null
                        onDisclosed(outcome.value)
                    }
                }
            } finally {
                password.fill(Char.MIN_VALUE)
            }
        }
    }
}

// No else branch, over the cases a disclosure reports: a case joining that view has to be given a
// message here before this compiles again.
private fun messageFor(error: DiscloseError): String = when (error) {
    // Kept apart from the damaged case below: this one means retype, that one means the file.
    is VaultError.WrongPassword -> "That password did not open the vault."

    // The check reads the header the open vault holds, so what is damaged here is that header.
    is VaultError.Corrupt -> "The vault file is damaged, so nothing can be disclosed from it."

    is VaultError.VaultClosed -> "The vault locked while your password was being checked."

    is VaultError.NoSuchEntry -> "That account is no longer in the vault."
}
