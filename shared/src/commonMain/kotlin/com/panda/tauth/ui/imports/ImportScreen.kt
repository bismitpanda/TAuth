package com.panda.tauth.ui.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.panda.tauth.ui.theme.ButtonLabel
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.ui.theme.TauthIcons
import com.panda.tauth.vault.EntryAddError
import com.panda.tauth.vault.ImportRow
import com.panda.tauth.vault.VaultEntry
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.accepted

internal const val IMPORT_TITLE = "Import accounts"
internal const val IMPORT_CONFIRM_LABEL = "Add these accounts"
internal const val IMPORT_CANCEL_LABEL = "Cancel"
internal const val IMPORT_ADD_ANYWAY_LABEL = "Add anyway"

internal const val IMPORT_SUMMARY_TAG = "import-summary"
internal const val IMPORT_PROBLEM_TAG = "import-problem"

internal fun importRowTag(position: Int): String = "import-row-$position"

internal fun importChoiceTag(position: Int): String = "import-choice-$position"

// A count of nothing and a total equal to what is being taken each state a choice nobody can act on,
// so neither is said.
internal fun importSummary(rows: List<ImportRow>, addAnyway: Set<Int>): String {
    val accounts = rows.filterIsInstance<ImportRow.Account>()
    val accepted = rows.accepted(addAnyway).size
    val duplicates = accounts.count { it.isDuplicate }
    val refused = rows.size - accounts.size

    val added = when {
        accepted == 0 -> "No accounts will be added"
        accepted == accounts.size -> "$accepted ${if (accepted == 1) "account" else "accounts"} will be added"
        else -> "$accepted of ${accounts.size} accounts will be added"
    }
    val notes = buildList {
        if (duplicates > 0) add("$duplicates already here")
        if (refused > 0) add("$refused could not be read")
    }
    return if (notes.isEmpty()) "$added." else "$added. ${notes.joinToString(", ")}."
}

internal fun importRowLabel(entry: VaultEntry): String =
    entry.issuer?.let { "$it — ${entry.accountName}" } ?: entry.accountName

// The screen holds no session: it draws the rows it is given and reports what was chosen. The rows
// carry secrets and nothing here puts one on the screen.
@Composable
fun ImportScreen(
    rows: List<ImportRow>,
    modifier: Modifier = Modifier,
    addAnyway: Set<Int> = emptySet(),
    isBusy: Boolean = false,
    error: EntryAddError? = null,
    onToggleDuplicate: (Int) -> Unit = {},
    onImport: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(IMPORT_TITLE, style = MaterialTheme.typography.headlineSmall)
        Text(
            importSummary(rows, addAnyway),
            modifier = Modifier.testTag(IMPORT_SUMMARY_TAG),
            style = MaterialTheme.typography.bodyMedium,
        )
        error?.let { failure ->
            Text(
                messageFor(failure),
                modifier = Modifier.testTag(IMPORT_PROBLEM_TAG),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (isBusy) {
            CircularProgressIndicator()
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
        ) {
            items(rows, key = { it.position }) { row ->
                RowEntry(row, isAddedAnyway = row.position in addAnyway, isEnabled = !isBusy) {
                    onToggleDuplicate(row.position)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            Button(
                onClick = onImport,
                enabled = !isBusy && rows.accepted(addAnyway).isNotEmpty(),
            ) { ButtonLabel(TauthIcons.import, IMPORT_CONFIRM_LABEL) }
            TextButton(onClick = onCancel, enabled = !isBusy) { Text(IMPORT_CANCEL_LABEL) }
        }
    }
}

@Composable
private fun RowEntry(
    row: ImportRow,
    isAddedAnyway: Boolean,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier.fillMaxWidth().testTag(importRowTag(row.position)),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            when (row) {
                is ImportRow.Account -> Account(row, isAddedAnyway, isEnabled, onToggle)

                // The line is a credential, so what is on screen is where it sat and the rule it
                // broke rather than any of it.
                is ImportRow.Refused -> Text(
                    "Line ${row.position}: ${row.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// A duplicate is drawn back rather than labelled: the summary counts them once, and a note on every
// row says the same thing as many times as the file holds copies.
@Composable
private fun RowScope.Account(row: ImportRow.Account, isAddedAnyway: Boolean, isEnabled: Boolean, onToggle: () -> Unit) {
    Text(
        importRowLabel(row.entry),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
        color = if (row.isDuplicate) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
    if (row.isDuplicate) {
        Row(
            modifier = Modifier
                .testTag(importChoiceTag(row.position))
                .toggleable(value = isAddedAnyway, enabled = isEnabled, role = Role.Checkbox) { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.extraSmall),
        ) {
            Checkbox(checked = isAddedAnyway, onCheckedChange = null, enabled = isEnabled)
            Text(IMPORT_ADD_ANYWAY_LABEL, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun messageFor(error: EntryAddError): String = when (error) {
    is VaultError.InvalidSecret -> "One of these accounts carries a secret this cannot store: ${error.detail}."
    is VaultError.InvalidEntry -> "One of these accounts cannot be stored: ${error.detail}."
    is VaultError.VaultClosed -> "The vault locked before the accounts were added, so none of them were."
    is VaultError.TooLarge -> "These accounts would take the vault past the size it can be written at."
    is VaultError.UnsupportedVersion -> "This vault is a version TAuth does not know how to write."
    is VaultError.Io -> "The vault could not be written, so none of these accounts were added."
    is VaultError.LockedByAnotherProcess -> "Another TAuth holds the vault file, so none were added."
}
