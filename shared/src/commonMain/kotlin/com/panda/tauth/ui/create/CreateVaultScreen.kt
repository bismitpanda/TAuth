package com.panda.tauth.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.panda.tauth.password.MIN_MASTER_PASSWORD_LENGTH
import com.panda.tauth.password.PasswordStrength
import com.panda.tauth.password.passwordStrength
import com.panda.tauth.ui.components.PasswordField
import com.panda.tauth.ui.components.PasswordFieldState
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.vault.VaultError

private const val TITLE = "Create your vault"
private const val NOTE_HEADING = "Your master password cannot be recovered"
private const val NOTE_BODY =
    "TAuth keeps no copy of it and has no reset path. If you forget it, every secret stored in " +
        "this vault is lost with it."
private const val ACKNOWLEDGE_LABEL = "I understand that losing my master password loses every stored secret"
private const val PASSWORD_LABEL = "Master password"
private const val CONFIRMATION_LABEL = "Confirm master password"
private const val CREATE_LABEL = "Create vault"
private const val TOO_SHORT_MESSAGE = "Use at least $MIN_MASTER_PASSWORD_LENGTH characters"
private const val MISMATCH_MESSAGE = "The two passwords do not match"
private const val STRENGTH_PREFIX = "Strength: "

// The session does not appear here: the screen reports a password and the caller decides what to
// do with it.
@Composable
fun CreateVaultScreen(
    onCreate: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    error: VaultError? = null,
) {
    val password = remember { PasswordFieldState() }
    val confirmation = remember { PasswordFieldState() }
    var isAcknowledged by remember { mutableStateOf(false) }

    // Both holders end with the screen. destroy() zeroes what they hold and stops them taking
    // another character.
    DisposableEffect(Unit) {
        onDispose {
            password.destroy()
            confirmation.destroy()
        }
    }

    val spacing = LocalSpacing.current
    val strength = remember(password.revision) { password.scored() }
    val isMatched = remember(password.revision, confirmation.revision) { password.matches(confirmation) }
    // The strength is not among these. It reports on the password and refuses none.
    val canCreate = isAcknowledged && password.length >= MIN_MASTER_PASSWORD_LENGTH && isMatched && !isBusy
    // The Done action of either field runs this with no button in the way, so the acknowledgement,
    // the minimum length and the match are enforced here rather than by what the button does.
    val submit: () -> Unit = {
        if (canCreate) {
            // The array handed over is the caller's to zero once the derivation has read it.
            onCreate(password.copyValue())
            // The confirmation has done its whole job the moment the two matched, and a second copy
            // of the password living through the derivation is a second copy to zero.
            confirmation.clear()
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Text(TITLE, style = MaterialTheme.typography.headlineSmall)
        RecoveryNote()
        Acknowledgement(isAcknowledged = isAcknowledged, isEnabled = !isBusy, onChange = { isAcknowledged = it })
        Text(PASSWORD_LABEL, style = MaterialTheme.typography.labelLarge)
        PasswordField(state = password, modifier = Modifier.fillMaxWidth(), enabled = !isBusy, onSubmit = submit)
        if (password.length > 0) {
            StrengthMeter(strength)
        }
        if (password.length in 1 until MIN_MASTER_PASSWORD_LENGTH) {
            Text(TOO_SHORT_MESSAGE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(CONFIRMATION_LABEL, style = MaterialTheme.typography.labelLarge)
        PasswordField(state = confirmation, modifier = Modifier.fillMaxWidth(), enabled = !isBusy, onSubmit = submit)
        if (confirmation.length > 0 && !isMatched) {
            Text(MISMATCH_MESSAGE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        error?.let { failure ->
            Text(
                messageFor(failure),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = submit, enabled = canCreate) { Text(CREATE_LABEL) }
        if (isBusy) {
            CircularProgressIndicator()
        }
    }
}

// No dismiss control and no collapse: the consequence stated here is the one the user is agreeing
// to below it.
@Composable
private fun RecoveryNote(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(LocalSpacing.current.medium),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.small),
        ) {
            Text(NOTE_HEADING, style = MaterialTheme.typography.titleMedium)
            Text(NOTE_BODY, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Acknowledgement(
    isAcknowledged: Boolean,
    isEnabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = isAcknowledged, enabled = isEnabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.small),
    ) {
        Checkbox(checked = isAcknowledged, onCheckedChange = null, enabled = isEnabled)
        Text(ACKNOWLEDGE_LABEL, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StrengthMeter(strength: PasswordStrength, modifier: Modifier = Modifier) {
    val steps = PasswordStrength.entries.size.toFloat()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.extraSmall),
    ) {
        LinearProgressIndicator(
            progress = { (strength.ordinal + 1) / steps },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(STRENGTH_PREFIX + strength.name, style = MaterialTheme.typography.bodySmall)
    }
}

// No else branch: a VaultError case added elsewhere has to be given a message here before this
// compiles again.
private fun messageFor(error: VaultError): String = when (error) {
    is VaultError.VaultFileExists -> "A vault already exists at this location."

    is VaultError.NoVaultFile -> "The vault file was gone before it could be opened."

    is VaultError.LockedByAnotherProcess -> "Another TAuth process is holding the vault file."

    is VaultError.Io -> "The vault file could not be written."

    is VaultError.TooLarge -> "The vault is larger than the file format allows."

    // Kept apart from the damage cases below: this one means the password, those mean the file.
    is VaultError.WrongPassword -> "The vault was written but the password did not open it."

    is VaultError.IntegrityFailure, is VaultError.Corrupt ->
        "The vault file was written but reads back damaged."

    is VaultError.UnsupportedVersion -> "The vault file is in a format this version of TAuth does not read."

    is VaultError.InvalidSecret, is VaultError.MalformedUri, is VaultError.InvalidEntry,
    is VaultError.NoSuchEntry, is VaultError.VaultClosed,
    -> "The vault could not be created."
}

// The copy the scorer reads is zeroed before the reading leaves this function: copyValue hands out
// a fresh array whose caller owns it.
private fun PasswordFieldState.scored(): PasswordStrength {
    val value = copyValue()
    return try {
        passwordStrength(value)
    } finally {
        value.fill(Char.MIN_VALUE)
    }
}

// Compared as CharArrays: routing either through a String would leave a copy of the password nothing
// can zero. Neither side is a stored secret, so the early exit is no oracle.
private fun PasswordFieldState.matches(other: PasswordFieldState): Boolean {
    val left = copyValue()
    val right = other.copyValue()
    return try {
        left.contentEquals(right)
    } finally {
        left.fill(Char.MIN_VALUE)
        right.fill(Char.MIN_VALUE)
    }
}
