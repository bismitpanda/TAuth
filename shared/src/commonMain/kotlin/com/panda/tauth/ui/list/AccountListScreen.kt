package com.panda.tauth.ui.list

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
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
import com.panda.tauth.ui.theme.TauthIcons
import com.panda.tauth.vault.DiscloseError
import com.panda.tauth.vault.EntryChangeError
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val TITLE = "Accounts"
internal const val SEARCH_TAG = "account-search"
internal const val SEARCH_PLACEHOLDER = "Search"
internal const val ADD_LABEL = "Add account"
internal const val SETTINGS_LABEL = "Settings"
internal const val LOCK_LABEL = "Lock"

internal const val SORT_MANUAL_LABEL = "Manual order"
internal const val SORT_ISSUER_ASCENDING_LABEL = "Issuer A–Z"
internal const val SORT_ISSUER_DESCENDING_LABEL = "Issuer Z–A"
internal const val SORT_NEWEST_LABEL = "Newest first"
internal const val SORT_OLDEST_LABEL = "Oldest first"

internal const val SORT_LABEL = "Sort accounts"
internal const val SORT_MENU_TAG = "sort-menu"

internal const val EMPTY_HEADING = "No accounts yet"
internal const val EMPTY_BODY =
    "Add an account by pasting the otpauth:// URI its provider gave you, by reading an image of the " +
        "QR code it showed you, or by typing its details in by hand."

internal const val DELETE_CONFIRM_LABEL = "Delete account"
internal const val DELETE_CANCEL_LABEL = "Keep account"

internal const val LIST_ERROR_TAG = "list-error"

internal fun sortChoiceTag(label: String): String = "sort-$label"

enum class SortChoice(val order: SortOrder, val isDescending: Boolean, val label: String) {
    MANUAL(SortOrder.MANUAL, false, SORT_MANUAL_LABEL),
    ISSUER_ASCENDING(SortOrder.ISSUER, false, SORT_ISSUER_ASCENDING_LABEL),
    ISSUER_DESCENDING(SortOrder.ISSUER, true, SORT_ISSUER_DESCENDING_LABEL),
    NEWEST_FIRST(SortOrder.RECENTLY_ADDED, false, SORT_NEWEST_LABEL),
    OLDEST_FIRST(SortOrder.RECENTLY_ADDED, true, SORT_OLDEST_LABEL),
    ;

    companion object {
        fun of(order: SortOrder, isDescending: Boolean): SortChoice =
            entries.firstOrNull { it.order == order && it.isDescending == isDescending }
                ?: entries.first { it.order == order }
    }
}

@Composable
private fun sortIcon(choice: SortChoice): Painter = when (choice) {
    SortChoice.MANUAL -> TauthIcons.sortManual
    SortChoice.ISSUER_ASCENDING, SortChoice.ISSUER_DESCENDING -> TauthIcons.sortIssuer
    SortChoice.NEWEST_FIRST, SortChoice.OLDEST_FIRST -> TauthIcons.sortRecent
}

internal fun disclosureStatement(entry: UnlockedEntry): String =
    "The complete secret for ${entry.describe()} is about to be placed on the clipboard as an otpauth:// URI."

internal fun qrDisclosureStatement(entry: UnlockedEntry): String =
    "The complete secret for ${entry.describe()} is about to be drawn on screen as a QR code."

internal fun UnlockedEntry.describe(): String = issuer?.let { "$it: $accountName" } ?: accountName

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
    isSortDescending: Boolean = false,
    clipboardClearSeconds: Int = 0,
    clipboard: ClipboardCopy = ClipboardCopy { _, _ -> CopyResult.REFUSED },
    qrEncoding: QrEncoding = QrEncoding { null },
    error: EntryChangeError? = null,
    onSortChange: (SortOrder, Boolean) -> Unit = { _, _ -> },
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
    var selected by remember { mutableStateOf(0) }
    val searchFocus = remember { FocusRequester() }

    val shown = remember(entries, query, sortOrder, isSortDescending) {
        sorted(entries.filter { matchesQuery(it, query) }, sortOrder, isSortDescending)
    }

    StartOverOnSort(sortOrder, isSortDescending, listState) { selected = it }

    val selectedIndex = rememberSelection(selected, shown.size, searchFocus, listState)

    // The ticker cannot see which rows are on screen, so the list says.
    val visibleIds by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() }
    }
    LaunchedEffect(visibleIds) { onVisibleChange(visibleIds) }

    val callbacks = rowCallbacks(
        rows = rows,
        scope = scope,
        codes = codes,
        clipboard = clipboard,
        clearSeconds = clipboardClearSeconds,
        copyGate = copyGate,
        qrGate = qrGate,
        onGenerate = onGenerate,
        onEdit = onEdit,
        onDelete = { pendingDelete = it },
        onMove = onMove,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.medium)
            .listKeys(
                isReorderable = sortOrder == SortOrder.MANUAL && query.isBlank(),
                index = selectedIndex,
                shown = shown,
                hasQuery = query.isNotEmpty(),
                onSelect = { selected = it },
                onCopy = callbacks.onCopyCode,
                onMove = onMove,
                onClearQuery = { query = "" },
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Header(
            query = query,
            sortOrder = sortOrder,
            isSortDescending = isSortDescending,
            searchFocus = searchFocus,
            onQueryChange = { query = it },
            onSortChange = onSortChange,
            onAdd = onAdd,
            onSettings = onSettings,
            onLock = onLock,
        )
        ListError(error)
        Body(entries, shown, listState, codes, rows, selectedIndex, callbacks, sortOrder, query, onAdd)
    }

    DeleteConfirmationFor(
        entry = pendingDelete,
        onSettled = { pendingDelete = null },
        onDelete = onDelete,
    )

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

// The scroll is here rather than left to the selection, which does not move when the first row was
// already the selected one.
@Composable
private fun StartOverOnSort(
    sortOrder: SortOrder,
    isDescending: Boolean,
    listState: LazyListState,
    onSelect: (Int) -> Unit,
) {
    LaunchedEffect(sortOrder, isDescending) {
        onSelect(0)
        listState.animateScrollToItem(0)
    }
}

@Composable
private fun rememberSelection(selected: Int, count: Int, searchFocus: FocusRequester, listState: LazyListState): Int {
    val index = selected.coerceIn(0, (count - 1).coerceAtLeast(0))
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    LaunchedEffect(index) { if (count > 0) listState.animateScrollToItem(index) }
    return index
}

@Suppress("LongParameterList")
@Composable
private fun Body(
    entries: List<UnlockedEntry>,
    shown: List<UnlockedEntry>,
    listState: LazyListState,
    codes: Map<String, TotpCode>,
    rows: RowState,
    selectedIndex: Int,
    callbacks: RowCallbacks,
    sortOrder: SortOrder,
    query: String,
    onAdd: () -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyState(onAdd = onAdd)
    } else {
        AccountList(
            shown = shown,
            listState = listState,
            codes = codes,
            rows = rows,
            isDragEnabled = sortOrder == SortOrder.MANUAL && query.isBlank(),
            selectedIndex = selectedIndex,
            callbacks = callbacks,
        )
    }
}

@Composable
private fun DeleteConfirmationFor(entry: UnlockedEntry?, onSettled: () -> Unit, onDelete: (String) -> Unit) {
    entry?.let {
        DeleteConfirmation(
            entry = it,
            onConfirm = {
                onSettled()
                onDelete(it.id)
            },
            onDismiss = onSettled,
        )
    }
}

@Suppress("LongParameterList")
private fun rowCallbacks(
    rows: RowState,
    scope: CoroutineScope,
    codes: Map<String, TotpCode>,
    clipboard: ClipboardCopy,
    clearSeconds: Int,
    copyGate: DisclosureState<UnlockedEntry, DiscloseError>,
    qrGate: DisclosureState<UnlockedEntry, DiscloseError>,
    onGenerate: suspend (String) -> Outcome<String, EntryChangeError>,
    onEdit: (String) -> Unit,
    onDelete: (UnlockedEntry) -> Unit,
    onMove: (String, Int) -> Unit,
): RowCallbacks = RowCallbacks(
    onCopyCode = { entry ->
        val current = if (entry.type == OtpType.TOTP) codes[entry.id]?.code else rows.generated[entry.id]
        current?.let { rows.copy(scope, entry.id, CODE_SUBJECT, it, clipboard, clearSeconds) }
    },
    onGenerate = { id -> rows.generate(scope, id, onGenerate) },
    onHideCode = rows::hideCode,
    onEdit = onEdit,
    onCopyUri = copyGate::ask,
    onShowQr = qrGate::ask,
    onDelete = onDelete,
    onMove = onMove,
)

@Suppress("LongParameterList")
private fun Modifier.listKeys(
    isReorderable: Boolean,
    index: Int,
    shown: List<UnlockedEntry>,
    hasQuery: Boolean,
    onSelect: (Int) -> Unit,
    onCopy: (UnlockedEntry) -> Unit,
    onMove: (String, Int) -> Unit,
    onClearQuery: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    onListKey(
        event = event,
        isReorderable = isReorderable,
        index = index,
        count = shown.size,
        hasQuery = hasQuery,
        onSelect = onSelect,
        onCopy = { shown.getOrNull(index)?.let(onCopy) },
        onMove = { to -> shown.getOrNull(index)?.let { onMove(it.id, to) } },
        onClearQuery = onClearQuery,
    )
}

@Suppress("LongParameterList")
private fun onListKey(
    event: KeyEvent,
    isReorderable: Boolean,
    index: Int,
    count: Int,
    hasQuery: Boolean,
    onSelect: (Int) -> Unit,
    onCopy: () -> Unit,
    onMove: (Int) -> Unit,
    onClearQuery: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || count == 0) return false
    val step = when (event.key) {
        Key.DirectionDown -> 1
        Key.DirectionUp -> -1
        else -> 0
    }
    return when {
        step != 0 && event.isAltPressed -> {
            if (isReorderable) onMove((index + step).coerceIn(0, count - 1))
            isReorderable
        }

        step != 0 -> {
            onSelect((index + step).coerceIn(0, count - 1))
            true
        }

        event.key == Key.Enter -> {
            onCopy()
            true
        }

        event.key == Key.Escape && hasQuery -> {
            onClearQuery()
            true
        }

        else -> false
    }
}

@Composable
private fun AccountList(
    shown: List<UnlockedEntry>,
    listState: LazyListState,
    codes: Map<String, TotpCode>,
    rows: RowState,
    isDragEnabled: Boolean,
    selectedIndex: Int,
    callbacks: RowCallbacks,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val drag = remember { RowDragState() }
    val density = LocalDensity.current
    val marginPixels = with(density) { spacing.extraLarge.toPx() }
    val stepPixels = with(density) { spacing.small.toPx() }

    ScrollWhileDragging(drag, listState, marginPixels, stepPixels)

    val order = reordered(shown, drag.startIndex, drag.targetIndex)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        itemsIndexed(order, key = { _, entry -> entry.id }) { index, entry ->
            val isDragged = drag.draggedId == entry.id
            PinWhileDragged(isDragged)
            Box(modifier = if (isDragged) Modifier.zIndex(1f) else Modifier.animateItem()) {
                if (isDragged) DragSlot(Modifier.matchParentSize())
                AccountRow(
                    modifier = if (isDragged) Modifier.dragPlacement(drag, index, listState) else Modifier,
                    entry = entry,
                    isSelected = index == selectedIndex,
                    code = codes[entry.id],
                    generatedCode = rows.generated[entry.id],
                    isGenerateEnabled = entry.id !in rows.coolingDown,
                    notice = rows.noticeFor(entry.id),
                    dragModifier = dragHandle(isDragEnabled, entry.id, index, drag, listState, callbacks.onMove),
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

    LaunchedEffect(shown) { drag.settle() }
}

@Composable
private fun Header(
    query: String,
    sortOrder: SortOrder,
    searchFocus: FocusRequester,
    onQueryChange: (String) -> Unit,
    isSortDescending: Boolean,
    onSortChange: (SortOrder, Boolean) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            Text(TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            ActionIcon(TauthIcons.add, ADD_LABEL, onAdd)
            ActionIcon(TauthIcons.settings, SETTINGS_LABEL, onSettings)
            ActionIcon(TauthIcons.lock, LOCK_LABEL, onLock)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                query = query,
                searchFocus = searchFocus,
                onQueryChange = onQueryChange,
                modifier = Modifier.weight(1f),
            )
            SortMenu(sortOrder = sortOrder, isSortDescending = isSortDescending, onSortChange = onSortChange)
        }
    }
}

@Composable
private fun SortMenu(
    sortOrder: SortOrder,
    isSortDescending: Boolean,
    onSortChange: (SortOrder, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isOpen by remember { mutableStateOf(false) }
    val chosen = SortChoice.of(sortOrder, isSortDescending)

    Box(modifier = modifier) {
        IconButton(onClick = { isOpen = true }, modifier = Modifier.testTag(SORT_MENU_TAG)) {
            Icon(TauthIcons.sortManual, contentDescription = SORT_LABEL)
        }
        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            SortChoice.entries.forEach { option ->
                val isChosen = option == chosen
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { Icon(sortIcon(option), contentDescription = null) },
                    trailingIcon = { if (isChosen) Icon(TauthIcons.check, contentDescription = null) },
                    modifier = Modifier.testTag(sortChoiceTag(option.label)).semantics { selected = isChosen },
                    onClick = {
                        isOpen = false
                        onSortChange(option.order, option.isDescending)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ActionIcon(icon: Painter, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun SearchField(
    query: String,
    searchFocus: FocusRequester,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().focusRequester(searchFocus).testTag(SEARCH_TAG),
        placeholder = { Text(SEARCH_PLACEHOLDER) },
        leadingIcon = { Icon(TauthIcons.search, contentDescription = null) },
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

private fun messageFor(error: EntryChangeError): String = when (error) {
    is VaultError.NoSuchEntry -> "That account is no longer in the vault."
    is VaultError.InvalidEntry -> "The change was refused: ${error.detail}."
    is VaultError.VaultClosed -> "The vault locked before the change was saved."
    is VaultError.LockedByAnotherProcess -> "Another TAuth process is holding the vault file."
    is VaultError.Io -> "The vault file could not be written."
    is VaultError.TooLarge -> "The vault is larger than the file format allows."
    is VaultError.UnsupportedVersion -> "This vault was made by a newer version of TAuth."
}

private fun LazyListState.rowBounds(): List<RowBounds> =
    layoutInfo.visibleItemsInfo.map { RowBounds(it.index, it.offset, it.size) }

// Keying on the index too would tear the gesture down the moment the rearrangement moves the row.
@Composable
private fun dragHandle(
    isEnabled: Boolean,
    id: String,
    index: Int,
    drag: RowDragState,
    listState: LazyListState,
    onMove: (String, Int) -> Unit,
): Modifier {
    if (!isEnabled) return Modifier
    val grabbed by rememberUpdatedState(index)
    return Modifier.pointerInput(id) {
        detectDragGestures(
            onDragStart = { drag.start(id, grabbed, listState.rowBounds()) },
            onDrag = { change, amount ->
                change.consume()
                drag.dragBy(amount.y, listState.rowBounds())
            },
            onDragEnd = { drag.release()?.let { onMove(it.id, it.toIndex) } },
            onDragCancel = { drag.settle() },
        )
    }
}

// A slot the list is not reporting holds the last drawing rather than snapping the row to the top.
private fun Modifier.dragPlacement(drag: RowDragState, index: Int, listState: LazyListState): Modifier =
    this.graphicsLayer {
        val top = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset
        translationY = top?.let { drag.translationFor(it) } ?: translationY
    }

// The gesture is a node inside the item, and a lazy list disposes an item whose slot leaves view.
@Composable
private fun PinWhileDragged(isDragged: Boolean) {
    val container = LocalPinnableContainer.current
    DisposableEffect(isDragged, container) {
        val handle = if (isDragged) container?.pin() else null
        onDispose { handle?.release() }
    }
}

@Composable
private fun ScrollWhileDragging(drag: RowDragState, listState: LazyListState, margin: Float, step: Float) {
    val draggedId = drag.draggedId
    LaunchedEffect(draggedId) {
        if (draggedId == null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            val pixels = edgeScroll(drag.centreY, viewport, margin, step)
            if (pixels != 0f) listState.scrollBy(pixels)
            drag.retarget(listState.rowBounds())
            listState.keepInView(drag.targetIndex)
        }
    }
}

// The list holds its position by the key of its first visible row, so an arrangement reaching that
// row pushes the dragged one off the top instead of moving anything.
private suspend fun LazyListState.keepInView(index: Int) {
    if (index != NO_ROW && layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
}
