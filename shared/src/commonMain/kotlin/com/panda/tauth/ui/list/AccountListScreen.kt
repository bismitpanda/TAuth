package com.panda.tauth.ui.list

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.panda.tauth.Outcome
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.settings.SortOrder
import com.panda.tauth.totp.OtpType
import com.panda.tauth.totp.TotpCode
import com.panda.tauth.ui.ClipboardCopy
import com.panda.tauth.ui.CopyResult
import com.panda.tauth.ui.components.DisclosureState
import com.panda.tauth.ui.components.SecretDisclosureGate
import com.panda.tauth.ui.qr.QrEncoding
import com.panda.tauth.ui.qr.QrSymbol
import com.panda.tauth.ui.qr.ShowQrDialog
import com.panda.tauth.ui.settings.FileWriteError
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.vault.DiscloseError
import com.panda.tauth.vault.EntryChangeError
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val TITLE = "Accounts"
internal const val SEARCH_TAG = "account-search"
internal const val SEARCH_PLACEHOLDER = "Search"
internal const val ADD_LABEL = "Add account"
internal const val SETTINGS_LABEL = "Settings"
internal const val LOCK_LABEL = "Lock"

internal const val SORT_MANUAL_LABEL = "Manual order"
internal const val SORT_ISSUER_LABEL = "Issuer A–Z"
internal const val SORT_RECENT_LABEL = "Recently added"

internal const val EMPTY_HEADING = "No accounts yet"
internal const val EMPTY_BODY =
    "Add an account by pasting the otpauth:// URI its provider gave you, by reading an image of the " +
        "QR code it showed you, or by typing its details in by hand."

internal const val DELETE_CONFIRM_LABEL = "Delete account"
internal const val DELETE_CANCEL_LABEL = "Keep account"

internal const val LIST_ERROR_TAG = "list-error"

// No else branch: an ordering added elsewhere has to be given a label here before this compiles again.
private fun sortLabel(order: SortOrder): String = when (order) {
    SortOrder.MANUAL -> SORT_MANUAL_LABEL
    SortOrder.ISSUER -> SORT_ISSUER_LABEL
    SortOrder.RECENTLY_ADDED -> SORT_RECENT_LABEL
}

internal fun disclosureStatement(entry: UnlockedEntry): String =
    "The complete secret for ${entry.describe()} is about to be placed on the clipboard as an otpauth:// URI."

internal fun qrDisclosureStatement(entry: UnlockedEntry): String =
    "The complete secret for ${entry.describe()} is about to be drawn on screen as a QR code."

internal fun UnlockedEntry.describe(): String = issuer?.let { "$it — $accountName" } ?: accountName

private class RowCallbacks(
    val onCopyCode: (UnlockedEntry) -> Unit,
    val onGenerate: (String) -> Unit,
    val onHideCode: (String) -> Unit,
    val onEdit: (String) -> Unit,
    val onCopyUri: (UnlockedEntry) -> Unit,
    val onShowQr: (UnlockedEntry) -> Unit,
    val onDelete: (UnlockedEntry) -> Unit,
    val onMove: (String, Int) -> Unit,
)

// The screen holds no session: it draws the entries it is given and reports what was pressed.
@Composable
fun AccountListScreen(
    entries: List<UnlockedEntry>,
    codes: Map<String, TotpCode>,
    modifier: Modifier = Modifier,
    sortOrder: SortOrder = SortOrder.MANUAL,
    clipboardClearSeconds: Int = 0,
    clipboard: ClipboardCopy = ClipboardCopy { _, _ -> CopyResult.REFUSED },
    qrEncoding: QrEncoding = QrEncoding { null },
    error: EntryChangeError? = null,
    onSortOrderChange: (SortOrder) -> Unit = {},
    onVisibleChange: (Set<String>) -> Unit = {},
    onIdleLockSuppressed: (Boolean) -> Unit = {},
    // Absent where the composition has no desktop under it to write a file to.
    onSaveQrImage: (suspend (QrSymbol) -> Outcome<Unit, FileWriteError>)? = null,
    onGenerate: suspend (String) -> Outcome<String, EntryChangeError> = { Outcome.Failure(VaultError.VaultClosed) },
    onDiscloseUri: suspend (String, CharArray) -> Outcome<String, DiscloseError> = { _, _ ->
        Outcome.Failure(VaultError.VaultClosed)
    },
    onMove: (String, Int) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    onAdd: () -> Unit = {},
    onSettings: () -> Unit = {},
    onLock: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // Generated codes end with this composition, so a lock takes them off the screen with it.
    val rows = remember { RowState() }
    // One gate per disclosure: the two state different destinations for the same secret, and one
    // slot would leave a password typed for the clipboard opening the dialog instead.
    val copyGate = remember { DisclosureState<UnlockedEntry, DiscloseError>() }
    val qrGate = remember { DisclosureState<UnlockedEntry, DiscloseError>() }

    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<UnlockedEntry?>(null) }

    val shown = remember(entries, query, sortOrder) {
        sorted(entries.filter { matchesQuery(it, query) }, sortOrder)
    }

    // The ticker cannot see which rows are on screen, so the list says.
    val visibleIds by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() }
    }
    LaunchedEffect(visibleIds) { onVisibleChange(visibleIds) }

    val callbacks = RowCallbacks(
        // Selected by type, as the row selects what it draws. Reading the ticker first would copy a
        // code an hotp row is not showing, should one ever arrive under its id.
        onCopyCode = { entry ->
            val current = if (entry.type == OtpType.TOTP) codes[entry.id]?.code else rows.generated[entry.id]
            current?.let { rows.copy(scope, entry.id, CODE_SUBJECT, it, clipboard, clipboardClearSeconds) }
        },
        onGenerate = { id -> rows.generate(scope, id, onGenerate) },
        onHideCode = rows::hideCode,
        onEdit = onEdit,
        onCopyUri = copyGate::ask,
        onShowQr = qrGate::ask,
        onDelete = { entry -> pendingDelete = entry },
        onMove = onMove,
    )

    Column(
        modifier = modifier.fillMaxSize().padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Header(
            query = query,
            sortOrder = sortOrder,
            onQueryChange = { query = it },
            onSortOrderChange = onSortOrderChange,
            onAdd = onAdd,
            onSettings = onSettings,
            onLock = onLock,
        )
        ListError(error)
        if (entries.isEmpty()) {
            EmptyState(onAdd = onAdd)
        } else {
            AccountList(
                shown = shown,
                listState = listState,
                codes = codes,
                rows = rows,
                // A drop is a position in the whole vault, so it can only be read off a list that is
                // showing the whole vault in the order the vault stores it.
                isDragEnabled = sortOrder == SortOrder.MANUAL && query.isBlank(),
                callbacks = callbacks,
            )
        }
    }

    pendingDelete?.let { entry ->
        DeleteConfirmation(
            entry = entry,
            onConfirm = {
                pendingDelete = null
                onDelete(entry.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    Disclosures(
        copyGate = copyGate,
        qrGate = qrGate,
        qrEncoding = qrEncoding,
        scope = scope,
        onDisclose = onDiscloseUri,
        // Both end on the clipboard under the delay a copied code clears under, matched on the
        // string that was placed there.
        onCopyUri = { entry, uri -> rows.copy(scope, entry.id, URI_SUBJECT, uri, clipboard, clipboardClearSeconds) },
        onIdleLockSuppressed = onIdleLockSuppressed,
        onSaveImage = onSaveQrImage,
    )
}

// The two actions that put this screen's secrets somewhere else, each behind a gate of its own.
@Composable
private fun Disclosures(
    copyGate: DisclosureState<UnlockedEntry, DiscloseError>,
    qrGate: DisclosureState<UnlockedEntry, DiscloseError>,
    qrEncoding: QrEncoding,
    scope: CoroutineScope,
    onDisclose: suspend (String, CharArray) -> Outcome<String, DiscloseError>,
    onCopyUri: (UnlockedEntry, String) -> Unit,
    onIdleLockSuppressed: (Boolean) -> Unit,
    onSaveImage: (suspend (QrSymbol) -> Outcome<Unit, FileWriteError>)?,
) {
    copyGate.request?.let { entry ->
        SecretDisclosureGate(
            statement = disclosureStatement(entry),
            isBusy = copyGate.isBusy,
            error = copyGate.error,
            onConfirm = { password ->
                copyGate.confirm(scope, password, { subject, entered -> onDisclose(subject.id, entered) }) { uri ->
                    onCopyUri(entry, uri)
                }
            },
            onDismiss = copyGate::cancel,
        )
    }

    QrDisclosure(qrGate, qrEncoding, scope, onDisclose, onCopyUri, onIdleLockSuppressed, onSaveImage)
}

// The gate and what it opens. The URI it discloses is a complete credential and lives here only
// while the dialog drawing it does.
@Composable
private fun QrDisclosure(
    gate: DisclosureState<UnlockedEntry, DiscloseError>,
    qrEncoding: QrEncoding,
    scope: CoroutineScope,
    onDisclose: suspend (String, CharArray) -> Outcome<String, DiscloseError>,
    onCopyUri: (UnlockedEntry, String) -> Unit,
    onIdleLockSuppressed: (Boolean) -> Unit,
    onSaveImage: (suspend (QrSymbol) -> Outcome<Unit, FileWriteError>)?,
) {
    var shown by remember { mutableStateOf<Pair<UnlockedEntry, String>?>(null) }

    gate.request?.let { entry ->
        SecretDisclosureGate(
            statement = qrDisclosureStatement(entry),
            isBusy = gate.isBusy,
            error = gate.error,
            onConfirm = { password ->
                gate.confirm(scope, password, { subject, entered -> onDisclose(subject.id, entered) }) { uri ->
                    shown = entry to uri
                }
            },
            onDismiss = gate::cancel,
        )
    }

    shown?.let { (entry, uri) ->
        // A symbol is read off the screen with no hand on the machine, which the idle timer would
        // take for an empty room. The dialog's own minute is what bounds the hold.
        DisposableEffect(Unit) {
            onIdleLockSuppressed(true)
            onDispose { onIdleLockSuppressed(false) }
        }
        val symbol = remember(uri) { qrEncoding.encode(uri) }
        var isSaving by remember { mutableStateOf(false) }
        var saveError by remember { mutableStateOf<FileWriteError?>(null) }
        ShowQrDialog(
            entry = entry,
            symbol = symbol,
            onCopyUri = { onCopyUri(entry, uri) },
            onDismiss = { shown = null },
            // Offered only where there is both a symbol to write and somewhere to write it.
            onSaveImage = symbol?.let { drawn ->
                onSaveImage?.let { save ->
                    {
                        scope.launch {
                            isSaving = true
                            saveError = (save(drawn) as? Outcome.Failure)?.error
                            isSaving = false
                        }
                    }
                }
            },
            isSaving = isSaving,
            saveError = saveError,
        )
    }
}

@Composable
private fun AccountList(
    shown: List<UnlockedEntry>,
    listState: LazyListState,
    codes: Map<String, TotpCode>,
    rows: RowState,
    isDragEnabled: Boolean,
    callbacks: RowCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.small),
    ) {
        itemsIndexed(shown, key = { _, entry -> entry.id }) { index, entry ->
            AccountRow(
                entry = entry,
                code = codes[entry.id],
                generatedCode = rows.generated[entry.id],
                isGenerateEnabled = entry.id !in rows.coolingDown,
                notice = rows.noticeFor(entry.id),
                dragModifier = dragModifier(
                    isEnabled = isDragEnabled,
                    id = entry.id,
                    index = index,
                    count = shown.size,
                    rowPitch = {
                        val items = listState.layoutInfo.visibleItemsInfo
                        rowPitch(items.map { it.offset }, items.firstOrNull()?.size ?: 0)
                    },
                    onMove = callbacks.onMove,
                ),
                onCopyCode = { callbacks.onCopyCode(entry) },
                onGenerate = { callbacks.onGenerate(entry.id) },
                onHideCode = { callbacks.onHideCode(entry.id) },
                onEdit = { callbacks.onEdit(entry.id) },
                onCopyUri = { callbacks.onCopyUri(entry) },
                onShowQr = { callbacks.onShowQr(entry) },
                onDelete = { callbacks.onDelete(entry) },
            )
        }
    }
}

@Composable
private fun Header(
    query: String,
    sortOrder: SortOrder,
    onQueryChange: (String) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            Text(TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = onAdd) { Text(ADD_LABEL) }
            Button(onClick = onSettings) { Text(SETTINGS_LABEL) }
            Button(onClick = onLock) { Text(LOCK_LABEL) }
        }
        SearchField(query = query, onQueryChange = onQueryChange)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            SortOrder.entries.forEach { option ->
                SortChoice(
                    label = sortLabel(option),
                    isSelected = sortOrder == option,
                    onSelect = { onSortOrderChange(option) },
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().testTag(SEARCH_TAG),
        placeholder = { Text(SEARCH_PLACEHOLDER) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun ListError(error: EntryChangeError?, modifier: Modifier = Modifier) {
    error?.let {
        Text(
            messageFor(it),
            modifier = modifier.testTag(LIST_ERROR_TAG),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// The chosen ordering stays pressable: a disabled control reads as the one option that cannot be had.
@Composable
private fun SortChoice(label: String, isSelected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val marked = modifier.semantics { selected = isSelected }
    if (isSelected) {
        Button(onClick = onSelect, modifier = marked) { Text(label) }
    } else {
        OutlinedButton(onClick = onSelect, modifier = marked) { Text(label) }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxWidth().padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(EMPTY_HEADING, style = MaterialTheme.typography.titleMedium)
        Text(EMPTY_BODY, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onAdd) { Text(ADD_LABEL) }
    }
}

// Named, because a deletion has no recovery path here: the account it removes has to be the one on
// screen rather than whichever row the menu was last opened over.
@Composable
private fun DeleteConfirmation(
    entry: UnlockedEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("Delete ${entry.describe()}?") },
        text = { Text("This removes the account and its secret from the vault, and cannot be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text(DELETE_CONFIRM_LABEL) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(DELETE_CANCEL_LABEL) } },
    )
}

// No else branch, over the cases a change to an entry reports: a case joining that view has to be
// given a message here before this compiles again.
private fun messageFor(error: EntryChangeError): String = when (error) {
    is VaultError.NoSuchEntry -> "That account is no longer in the vault."
    is VaultError.InvalidEntry -> "The change was refused: ${error.detail}."
    is VaultError.VaultClosed -> "The vault locked before the change was saved."
    is VaultError.LockedByAnotherProcess -> "Another TAuth process is holding the vault file."
    is VaultError.Io -> "The vault file could not be written."
    is VaultError.TooLarge -> "The vault is larger than the file format allows."
    is VaultError.UnsupportedVersion -> "The vault file is in a format this version of TAuth does not read."
}

private fun dragModifier(
    isEnabled: Boolean,
    id: String,
    index: Int,
    count: Int,
    rowPitch: () -> Int,
    onMove: (String, Int) -> Unit,
): Modifier {
    if (!isEnabled) return Modifier
    return Modifier.pointerInput(id, index, count) {
        var travelled = 0f
        detectDragGestures(
            onDragStart = { travelled = 0f },
            onDrag = { change, amount ->
                change.consume()
                travelled += amount.y
            },
            onDragEnd = { onMove(id, dropIndex(index, travelled, rowPitch().toFloat(), count)) },
            onDragCancel = { travelled = 0f },
        )
    }
}
