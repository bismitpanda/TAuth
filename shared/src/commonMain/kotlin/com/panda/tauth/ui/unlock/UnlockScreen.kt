package com.panda.tauth.ui.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.panda.tauth.session.LockReason
import com.panda.tauth.ui.components.PasswordField
import com.panda.tauth.ui.components.PasswordFieldState
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultUnlockError

private const val TITLE = "Unlock your vault"
private const val PASSWORD_LABEL = "Password"
private const val UNLOCK_LABEL = "Unlock"
private const val PROGRESS_LABEL = "Checking your password"

// Carried by the subtitle whenever one is on screen, so its absence is as observable as its text.
internal const val UNLOCK_SUBTITLE_TAG = "unlock-subtitle"

// The session does not appear here: the screen reports a password and the caller decides what to
// do with it.
@Composable
fun UnlockScreen(
    onUnlock: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    error: VaultUnlockError? = null,
    lastReason: LockReason? = null,
) {
    val password = remember { PasswordFieldState() }
    val focusRequester = remember { FocusRequester() }

    // The holder ends with the screen. destroy() zeroes what it holds and stops it taking another
    // character.
    DisposableEffect(Unit) {
        onDispose { password.destroy() }
    }

    // Hiding to the tray leaves this composition standing, so a first-composition effect never runs
    // again.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { if (it) focusRequester.requestFocus() }
    }

    val spacing = LocalSpacing.current
    val subtitle = lastReason?.let(::subtitleFor)
    // No attempt count and no delay after a failure: a rate limit here obstructs the person holding
    // the password without impeding anyone who has copied the file.
    val canUnlock = password.length > 0 && !isBusy
    // The Done action of the field runs this with no button in the way, so both rules are enforced
    // here rather than by what the button does.
    val submit: () -> Unit = {
        if (canUnlock) {
            // The array handed over is the caller's to zero once the derivation has read it. The
            // holder keeps its characters, so a failed attempt is corrected rather than retyped.
            onUnlock(password.copyValue())
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium, Alignment.CenterVertically),
    ) {
        Text(TITLE, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let { reported ->
            Text(
                reported,
                modifier = Modifier.testTag(UNLOCK_SUBTITLE_TAG),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(PASSWORD_LABEL, style = MaterialTheme.typography.labelLarge)
        // The field takes characters while a derivation runs, so a password typed wrong is edited
        // rather than waited out. What the derivation blocks is a second one behind it.
        PasswordField(
            state = password,
            modifier = Modifier.fillMaxWidth(),
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
        Button(onClick = submit, enabled = canUnlock) { Text(UNLOCK_LABEL) }
        if (isBusy) {
            // The derivation takes long enough that a still screen reads as a hung one.
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = PROGRESS_LABEL })
        }
    }
}

// A lock the user asked for and an exit lock need no explanation; what is left fired while the user's
// attention was elsewhere.
private fun subtitleFor(reason: LockReason): String? = when (reason) {
    LockReason.Manual, LockReason.Exit -> null
    LockReason.Idle -> "Locked automatically after a period of inactivity."
    LockReason.HiddenToTray -> "Locked when the window was hidden to the tray."
    LockReason.Minimised -> "Locked when the window was minimised."
    LockReason.FocusLost -> "Locked when the window lost focus."
}

private fun messageFor(error: VaultUnlockError): String = when (error) {
    // Kept apart from the damage cases below: this one means retype, those mean the file.
    is VaultError.WrongPassword -> "That password is not correct."

    // The body authenticated and then failed to read, which is the same damaged file to the person
    // in front of it as a failed tag.
    is VaultError.IntegrityFailure, is VaultError.Corrupt, is VaultError.InvalidSecret ->
        "The vault file is damaged and cannot be opened."

    is VaultError.NoVaultFile -> "There is no vault file at this location."

    is VaultError.UnsupportedVersion -> "This vault was made by a newer version of TAuth."

    is VaultError.Io -> "The vault file could not be read."

    // A lock overtook the derivation. The vault the user closed stays closed, and this says so
    // rather than reporting a failure they did not cause.
    is VaultError.VaultClosed -> "The vault locked while your password was being checked."
}
