package com.panda.tauth.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import com.panda.tauth.Outcome
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.OtpType
import com.panda.tauth.ui.components.ChoiceRow
import com.panda.tauth.ui.components.FormField
import com.panda.tauth.ui.theme.ControlIcon
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.ui.theme.TauthIcons
import com.panda.tauth.vault.DraftError
import com.panda.tauth.vault.EntryAddError
import com.panda.tauth.vault.EntryDraft
import com.panda.tauth.vault.ImageReadError
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.resolved
import com.panda.tauth.vault.secretProblem

internal const val ADD_TITLE = "Add an account"
internal const val PASTE_PATH_LABEL = "Paste a URI"
internal const val SCAN_PATH_LABEL = "Read an image"
internal const val MANUAL_PATH_LABEL = "Enter details"

internal const val SCAN_CHOOSE_LABEL = "Choose an image"
internal const val SCAN_PICK_TITLE = "Which account?"
internal const val SCAN_PICK_CANCEL_LABEL = "Cancel"

internal const val SCAN_NO_CODE = "No QR code was found in that image."
internal const val SCAN_NOT_AN_ACCOUNT = "That QR code is not an account TAuth can add."

internal const val SCAN_PROBLEM_TAG = "add-scan-problem"

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

internal fun scanPickTag(index: Int): String = "add-scan-choice-$index"

internal fun scanMessageFor(error: ImageReadError): String = when (error) {
    is VaultError.Corrupt -> "That image could not be read: ${error.detail}."
    is VaultError.Io -> "That image could not be read."
}

@Composable
private fun PathChoice(icon: Painter, label: String, isChosen: Boolean, onChoose: () -> Unit) {
    TextButton(onClick = onChoose, enabled = !isChosen) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ControlIcon))
        Spacer(Modifier.width(LocalSpacing.current.small))
        Text(label)
    }
}

// Which way the account is being entered. All three arrive at the same resolved account and the same
// preview; only the fields on the way there differ.
private enum class AddPath {
    PASTE,
    SCAN,
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
    // Absent where the composition has no desktop under it to read an image with.
    scanning: QrScanning? = null,
) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(AddPath.PASTE) }
    var pasted by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(EntryDraft()) }
    val scan = remember { ScanState() }

    val resolved: Outcome<OtpAuthUri, DraftError>? = when (path) {
        // A scan converges on the pasted field, so one image and one paste reach the same preview.
        AddPath.PASTE, AddPath.SCAN -> if (pasted.isBlank()) null else OtpAuthUri.parse(pasted)

        AddPath.MANUAL -> if (draft.secret.isEmpty() && draft.accountName.isEmpty()) null else draft.resolved()
    }
    val ready = (resolved as? Outcome.Success)?.value

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Text(ADD_TITLE, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            PathChoice(TauthIcons.paste, PASTE_PATH_LABEL, path == AddPath.PASTE) { path = AddPath.PASTE }
            scanning?.let {
                PathChoice(TauthIcons.image, SCAN_PATH_LABEL, path == AddPath.SCAN) { path = AddPath.SCAN }
            }
            PathChoice(TauthIcons.typed, MANUAL_PATH_LABEL, path == AddPath.MANUAL) { path = AddPath.MANUAL }
        }
        when (path) {
            AddPath.PASTE -> FormField(
                label = URI_FIELD_LABEL,
                value = pasted,
                onValueChange = { pasted = it },
                tag = URI_FIELD_TAG,
                enabled = !isBusy,
            )

            AddPath.SCAN -> ScanPath(
                scan = scan,
                isEnabled = !isBusy,
                onChoose = { scanning?.let { scan.read(scope, it) { uri -> pasted = uri } } },
                onPick = { uri -> pasted = uri },
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
            Button(onClick = { ready?.let(onSave) }, enabled = ready != null && !isBusy) {
                Icon(TauthIcons.save, contentDescription = null, modifier = Modifier.size(ControlIcon))
                Spacer(Modifier.width(LocalSpacing.current.small))
                Text(SAVE_LABEL)
            }
            TextButton(onClick = onCancel, enabled = !isBusy) { Text(CANCEL_LABEL) }
        }
        if (isBusy) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ScanPath(
    scan: ScanState,
    isEnabled: Boolean,
    onChoose: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Button(onClick = onChoose, enabled = isEnabled && !scan.isBusy) {
            Icon(TauthIcons.image, contentDescription = null, modifier = Modifier.size(ControlIcon))
            Spacer(Modifier.width(LocalSpacing.current.small))
            Text(SCAN_CHOOSE_LABEL)
        }
        val problem = scan.error?.let(::scanMessageFor) ?: scan.notice
        problem?.let {
            Text(
                it,
                modifier = Modifier.testTag(SCAN_PROBLEM_TAG),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (scan.choices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = scan::cancelChoice,
            title = { Text(SCAN_PICK_TITLE) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                    // Named by issuer and account alone: the list stands on screen while it is read.
                    scan.choices.forEachIndexed { index, uri ->
                        TextButton(
                            onClick = { scan.choose(uri, onPick) },
                            modifier = Modifier.testTag(scanPickTag(index)),
                        ) { Text(scannedLabel(uri)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = scan::cancelChoice) { Text(SCAN_PICK_CANCEL_LABEL) } },
        )
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

private fun messageFor(error: EntryAddError): String = when (error) {
    is VaultError.InvalidEntry -> "The account could not be saved: ${error.detail}."
    is VaultError.InvalidSecret -> "The secret could not be stored: ${error.detail}."
    is VaultError.VaultClosed -> "The vault locked before the account was saved."
    is VaultError.LockedByAnotherProcess -> "Another TAuth process is holding the vault file."
    is VaultError.Io -> "The vault file could not be written."
    is VaultError.TooLarge -> "The vault is larger than the file format allows."
    is VaultError.UnsupportedVersion -> "This vault was made by a newer version of TAuth."
}
