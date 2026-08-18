package com.panda.tauth.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.panda.tauth.Outcome
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpType
import com.panda.tauth.ui.components.ChoiceRow
import com.panda.tauth.ui.components.FormField
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.vault.EntryChangeError
import com.panda.tauth.vault.EntryEdit
import com.panda.tauth.vault.EntryEditDraft
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.resolved

internal const val EDIT_TITLE = "Edit account"
internal const val EDIT_SAVE_LABEL = "Save changes"
internal const val EDIT_ISSUER_TAG = "edit-issuer"
internal const val EDIT_ACCOUNT_TAG = "edit-account-name"
internal const val EDIT_DIGITS_TAG = "edit-digits"
internal const val EDIT_PERIOD_TAG = "edit-period"
internal const val EDIT_COUNTER_TAG = "edit-counter"
internal const val EDIT_WARNING_TAG = "edit-advanced-warning"
internal const val EDIT_PROBLEM_TAG = "edit-problem"

internal const val ADVANCED_WARNING =
    "Changing the algorithm, the digit count or the period makes this account's codes stop matching " +
        "the server unless the same change is made there."

internal const val COUNTER_HELP =
    "The counter is what a server's look-ahead window is measured against. Set it back or forward to " +
        "resynchronise an account that has run past it."

internal const val TYPE_PREFIX = "Type: "
internal const val SECRET_NOTE =
    "The secret cannot be changed. Replacing one means deleting the account and adding it again, so a " +
        "mistyped character cannot destroy the only copy of a credential."

// The secret and the type are absent from this screen rather than shown and refused: EntryEdit has no
// field for either, so nothing here can ask for a change to one.
@Composable
fun EditAccountScreen(
    entry: UnlockedEntry,
    onSave: (EntryEdit) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    error: EntryChangeError? = null,
) {
    val spacing = LocalSpacing.current
    var draft by remember(entry.id) { mutableStateOf(entry.toDraft()) }
    var isAdvancedShown by remember { mutableStateOf(false) }

    val resolved = draft.resolved(entry.type)
    val edit = (resolved as? Outcome.Success)?.value

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Text(EDIT_TITLE, style = MaterialTheme.typography.headlineSmall)
        Text(TYPE_PREFIX + entry.type.uriAuthority.uppercase(), style = MaterialTheme.typography.labelLarge)
        FormField(
            ISSUER_LABEL,
            draft.issuer,
            { draft = draft.copy(issuer = it) },
            EDIT_ISSUER_TAG,
            enabled = !isBusy,
        )
        FormField(
            ACCOUNT_LABEL,
            draft.accountName,
            { draft = draft.copy(accountName = it) },
            EDIT_ACCOUNT_TAG,
            enabled = !isBusy,
        )
        Text(SECRET_NOTE, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { isAdvancedShown = !isAdvancedShown }, enabled = !isBusy) { Text(ADVANCED_LABEL) }
        if (isAdvancedShown) {
            AdvancedEdit(entry = entry, draft = draft, isEnabled = !isBusy, onChange = { draft = it })
        }
        (resolved as? Outcome.Failure)?.let {
            Text(
                problemFor(it.error),
                modifier = Modifier.testTag(EDIT_PROBLEM_TAG),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        error?.let {
            Text(
                problemFor(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            Button(onClick = { edit?.let(onSave) }, enabled = edit != null && !isBusy) { Text(EDIT_SAVE_LABEL) }
            TextButton(onClick = onCancel, enabled = !isBusy) { Text(CANCEL_LABEL) }
        }
        if (isBusy) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun AdvancedEdit(
    entry: UnlockedEntry,
    draft: EntryEditDraft,
    isEnabled: Boolean,
    onChange: (EntryEditDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                ADVANCED_WARNING,
                modifier = Modifier.padding(spacing.medium).testTag(EDIT_WARNING_TAG),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ChoiceRow(
            label = ALGORITHM_LABEL,
            options = HashAlgorithm.entries,
            selected = draft.algorithm,
            optionLabel = { it.name },
            onSelect = { onChange(draft.copy(algorithm = it)) },
            enabled = isEnabled,
        )
        FormField(
            DIGITS_LABEL,
            draft.digits,
            { onChange(draft.copy(digits = it)) },
            EDIT_DIGITS_TAG,
            enabled = isEnabled,
        )
        when (entry.type) {
            OtpType.TOTP -> FormField(
                PERIOD_LABEL,
                draft.period,
                { onChange(draft.copy(period = it)) },
                EDIT_PERIOD_TAG,
                enabled = isEnabled,
            )

            OtpType.HOTP -> {
                FormField(
                    COUNTER_LABEL,
                    draft.counter,
                    { onChange(draft.copy(counter = it)) },
                    EDIT_COUNTER_TAG,
                    enabled = isEnabled,
                )
                Text(COUNTER_HELP, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun UnlockedEntry.toDraft(): EntryEditDraft = EntryEditDraft(
    issuer = issuer.orEmpty(),
    accountName = accountName,
    algorithm = algorithm,
    digits = digits.toString(),
    period = period?.toString().orEmpty(),
    counter = counter?.toString().orEmpty(),
)

// No else branch, over the cases a change to an entry reports: a case joining that view has to be given
// a message here before this compiles again.
private fun problemFor(error: EntryChangeError): String = when (error) {
    is VaultError.InvalidEntry -> "The change was refused: ${error.detail}."
    is VaultError.NoSuchEntry -> "That account is no longer in the vault."
    is VaultError.VaultClosed -> "The vault locked before the change was saved."
    is VaultError.LockedByAnotherProcess -> "Another TAuth process is holding the vault file."
    is VaultError.Io -> "The vault file could not be written."
    is VaultError.TooLarge -> "The vault is larger than the file format allows."
    is VaultError.UnsupportedVersion -> "The vault file is in a format this version of TAuth does not read."
}
