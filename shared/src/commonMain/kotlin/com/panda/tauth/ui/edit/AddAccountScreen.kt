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
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.OtpType
import com.panda.tauth.ui.components.ChoiceRow
import com.panda.tauth.ui.components.FormField
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.vault.DraftError
import com.panda.tauth.vault.EntryAddError
import com.panda.tauth.vault.EntryDraft
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.resolved
import com.panda.tauth.vault.secretProblem

internal const val ADD_TITLE = "Add an account"
internal const val PASTE_PATH_LABEL = "Paste a URI"
internal const val MANUAL_PATH_LABEL = "Enter details"
internal const val SAVE_LABEL = "Save account"
internal const val CANCEL_LABEL = "Cancel"

internal const val URI_FIELD_TAG = "add-uri"
internal const val ISSUER_FIELD_TAG = "add-issuer"
internal const val ACCOUNT_FIELD_TAG = "add-account-name"
internal const val SECRET_FIELD_TAG = "add-secret"
internal const val SECRET_PROBLEM_TAG = "add-secret-problem"
internal const val DIGITS_FIELD_TAG = "add-digits"
internal const val PERIOD_FIELD_TAG = "add-period"
internal const val COUNTER_FIELD_TAG = "add-counter"

internal const val URI_FIELD_LABEL = "otpauth:// URI"
internal const val ISSUER_LABEL = "Issuer"
internal const val ACCOUNT_LABEL = "Account name"
internal const val SECRET_LABEL = "Secret (base32)"
internal const val DIGITS_LABEL = "Digits"
internal const val PERIOD_LABEL = "Period, in seconds"
internal const val COUNTER_LABEL = "Starting counter"
internal const val TYPE_LABEL = "Type"
internal const val ALGORITHM_LABEL = "Algorithm"
internal const val ADVANCED_LABEL = "Advanced"

// Which way the account is being entered. Both arrive at the same resolved account and the same
// preview; only the fields on the way there differ.
private enum class AddPath {
    PASTE,
    MANUAL,
}

// The screen takes no clock of its own: the sample code in the preview is computed for the second the
// caller names, so a fixed clock produces a fixed sample.
@Composable
fun AddAccountScreen(
    onSave: (OtpAuthUri) -> Unit,
    onCancel: () -> Unit,
    epochSeconds: Long,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    error: EntryAddError? = null,
) {
    val spacing = LocalSpacing.current
    var path by remember { mutableStateOf(AddPath.PASTE) }
    var pasted by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(EntryDraft()) }

    val resolved: Outcome<OtpAuthUri, DraftError>? = when (path) {
        AddPath.PASTE -> if (pasted.isBlank()) null else OtpAuthUri.parse(pasted)
        AddPath.MANUAL -> if (draft.secret.isEmpty() && draft.accountName.isEmpty()) null else draft.resolved()
    }
    val ready = (resolved as? Outcome.Success)?.value

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Text(ADD_TITLE, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            TextButton(onClick = { path = AddPath.PASTE }, enabled = path != AddPath.PASTE) {
                Text(PASTE_PATH_LABEL)
            }
            TextButton(onClick = { path = AddPath.MANUAL }, enabled = path != AddPath.MANUAL) {
                Text(MANUAL_PATH_LABEL)
            }
        }
        when (path) {
            AddPath.PASTE -> FormField(
                label = URI_FIELD_LABEL,
                value = pasted,
                onValueChange = { pasted = it },
                tag = URI_FIELD_TAG,
                enabled = !isBusy,
            )

            AddPath.MANUAL -> ManualEntry(draft = draft, isEnabled = !isBusy, onChange = { draft = it })
        }
        EntryPreview(resolved = resolved, epochSeconds = epochSeconds)
        error?.let {
            Text(
                messageFor(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            Button(onClick = { ready?.let(onSave) }, enabled = ready != null && !isBusy) { Text(SAVE_LABEL) }
            TextButton(onClick = onCancel, enabled = !isBusy) { Text(CANCEL_LABEL) }
        }
        if (isBusy) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ManualEntry(
    draft: EntryDraft,
    isEnabled: Boolean,
    onChange: (EntryDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var isAdvancedShown by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        ChoiceRow(
            label = TYPE_LABEL,
            options = OtpType.entries,
            selected = draft.type,
            optionLabel = { it.uriAuthority.uppercase() },
            onSelect = { onChange(draft.copy(type = it)) },
            enabled = isEnabled,
        )
        FormField(
            label = ISSUER_LABEL,
            value = draft.issuer,
            onValueChange = { onChange(draft.copy(issuer = it)) },
            tag = ISSUER_FIELD_TAG,
            enabled = isEnabled,
        )
        FormField(
            label = ACCOUNT_LABEL,
            value = draft.accountName,
            onValueChange = { onChange(draft.copy(accountName = it)) },
            tag = ACCOUNT_FIELD_TAG,
            enabled = isEnabled,
        )
        // The secret is base32 text here as it is in the stored entry. What it stands for is decoded
        // where a code is generated and zeroed there; no field holds the key bytes.
        FormField(
            label = SECRET_LABEL,
            value = draft.secret,
            onValueChange = { onChange(draft.copy(secret = it)) },
            tag = SECRET_FIELD_TAG,
            enabled = isEnabled,
        )
        draft.secretProblem()?.let {
            Text(
                draftProblemFor(it),
                modifier = Modifier.testTag(SECRET_PROBLEM_TAG),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = { isAdvancedShown = !isAdvancedShown }, enabled = isEnabled) { Text(ADVANCED_LABEL) }
        if (isAdvancedShown) {
            AdvancedFields(draft = draft, isEnabled = isEnabled, onChange = onChange)
        }
    }
}

@Composable
private fun AdvancedFields(
    draft: EntryDraft,
    isEnabled: Boolean,
    onChange: (EntryDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        ChoiceRow(
            label = ALGORITHM_LABEL,
            options = HashAlgorithm.entries,
            selected = draft.algorithm,
            optionLabel = { it.name },
            onSelect = { onChange(draft.copy(algorithm = it)) },
            enabled = isEnabled,
        )
        FormField(
            label = DIGITS_LABEL,
            value = draft.digits,
            onValueChange = { onChange(draft.copy(digits = it)) },
            tag = DIGITS_FIELD_TAG,
            enabled = isEnabled,
        )
        when (draft.type) {
            OtpType.TOTP -> FormField(
                label = PERIOD_LABEL,
                value = draft.period,
                onValueChange = { onChange(draft.copy(period = it)) },
                tag = PERIOD_FIELD_TAG,
                enabled = isEnabled,
            )

            OtpType.HOTP -> FormField(
                label = COUNTER_LABEL,
                value = draft.counter,
                onValueChange = { onChange(draft.copy(counter = it)) },
                tag = COUNTER_FIELD_TAG,
                enabled = isEnabled,
            )
        }
    }
}

// No else branch, over the cases storing a new entry reports: a case joining that view has to be given
// a message here before this compiles again.
private fun messageFor(error: EntryAddError): String = when (error) {
    is VaultError.InvalidEntry -> "The account could not be saved: ${error.detail}."
    is VaultError.InvalidSecret -> "The secret could not be stored: ${error.detail}."
    is VaultError.VaultClosed -> "The vault locked before the account was saved."
    is VaultError.LockedByAnotherProcess -> "Another TAuth process is holding the vault file."
    is VaultError.Io -> "The vault file could not be written."
    is VaultError.TooLarge -> "The vault is larger than the file format allows."
    is VaultError.UnsupportedVersion -> "The vault file is in a format this version of TAuth does not read."
}
