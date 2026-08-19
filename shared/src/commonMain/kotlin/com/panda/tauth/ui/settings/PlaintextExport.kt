package com.panda.tauth.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
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
import com.panda.tauth.ui.components.ChoiceRow
import com.panda.tauth.ui.components.SecretDisclosureGate
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.vault.ExportFormat
import com.panda.tauth.vault.PasswordGateError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val PLAINTEXT_TITLE = "Export accounts unencrypted"

// What the file is, said before the password is asked for rather than after.
internal const val PLAINTEXT_WARNING =
    "The file this writes holds every secret in the vault in plain text. Anything that can read it " +
        "can generate your codes, and no password protects it once it has left TAuth. It is created " +
        "readable by you alone, and where it is copied to afterwards is not something TAuth can see."

internal const val PLAINTEXT_COUNTER_NOTE =
    "Counter-based accounts are written at the counter they stand at. Codes generated here afterwards " +
        "move this vault on and leave the file behind."

internal const val PLAINTEXT_FORMAT_LABEL = "Format"
internal const val PLAINTEXT_JSON_LABEL = "JSON"
internal const val PLAINTEXT_URI_LIST_LABEL = "otpauth:// URIs"
internal const val PLAINTEXT_CONTINUE_LABEL = "Continue"
internal const val PLAINTEXT_CANCEL_LABEL = "Cancel"

internal const val PLAINTEXT_WARNING_TAG = "plaintext-warning"
internal const val PLAINTEXT_PROBLEM_TAG = "plaintext-problem"

internal fun plaintextStatement(count: Int): String =
    "The complete secret for every one of the $count accounts in this vault is about to be written to " +
        "a file in plain text."

internal fun formatLabel(format: ExportFormat): String = when (format) {
    ExportFormat.JSON -> PLAINTEXT_JSON_LABEL
    ExportFormat.URI_LIST -> PLAINTEXT_URI_LIST_LABEL
}

// The whole vault in the clear, so it carries §9's gate like the other two disclosures and states
// what it is first. The text is held no longer than the write that consumes it.
@Composable
internal fun PlaintextExport(
    isRequested: Boolean,
    accountCount: Int,
    scope: CoroutineScope,
    onDisclose: suspend (CharArray, ExportFormat) -> Outcome<String, PasswordGateError>,
    onWrite: suspend (String, ExportFormat) -> Outcome<Unit, FileWriteError>,
    onFinished: () -> Unit,
    onWriteError: (FileWriteError?) -> Unit,
) {
    var format by remember { mutableStateOf(ExportFormat.JSON) }
    var asked by remember { mutableStateOf<ExportFormat?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var gateError by remember { mutableStateOf<PasswordGateError?>(null) }

    val finish = {
        asked = null
        format = ExportFormat.JSON
        gateError = null
        onFinished()
    }

    if (isRequested && asked == null) {
        Warning(
            format = format,
            onFormatChange = { format = it },
            onContinue = { asked = format },
            onDismiss = finish,
        )
    }

    asked?.let { chosen ->
        SecretDisclosureGate(
            statement = plaintextStatement(accountCount),
            isBusy = isBusy,
            error = gateError,
            onConfirm = { password ->
                scope.launch {
                    isBusy = true
                    onWriteError(null)
                    when (val disclosed = onDisclose(password, chosen)) {
                        is Outcome.Failure -> {
                            gateError = disclosed.error
                            isBusy = false
                        }

                        is Outcome.Success -> {
                            onWriteError((onWrite(disclosed.value, chosen) as? Outcome.Failure)?.error)
                            isBusy = false
                            finish()
                        }
                    }
                }
            },
            onDismiss = finish,
        )
    }
}

@Composable
private fun Warning(
    format: ExportFormat,
    onFormatChange: (ExportFormat) -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(PLAINTEXT_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                Text(
                    PLAINTEXT_WARNING,
                    modifier = Modifier.testTag(PLAINTEXT_WARNING_TAG),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(PLAINTEXT_COUNTER_NOTE, style = MaterialTheme.typography.bodySmall)
                ChoiceRow(
                    label = PLAINTEXT_FORMAT_LABEL,
                    options = ExportFormat.entries,
                    selected = format,
                    optionLabel = ::formatLabel,
                    onSelect = onFormatChange,
                )
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(PLAINTEXT_CONTINUE_LABEL) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(PLAINTEXT_CANCEL_LABEL) } },
    )
}
