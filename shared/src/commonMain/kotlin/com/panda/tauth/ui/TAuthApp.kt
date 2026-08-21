package com.panda.tauth.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.panda.tauth.Outcome
import com.panda.tauth.session.CodeTicker
import com.panda.tauth.session.LockReason
import com.panda.tauth.session.SessionState
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.session.VaultSession
import com.panda.tauth.settings.PreferencesState
import com.panda.tauth.settings.withStartAtLogin
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.TotpCode
import com.panda.tauth.ui.components.OverWindow
import com.panda.tauth.ui.create.CreateVaultScreen
import com.panda.tauth.ui.edit.AddAccountScreen
import com.panda.tauth.ui.edit.EditAccountScreen
import com.panda.tauth.ui.edit.QrScanning
import com.panda.tauth.ui.imports.ImportScreen
import com.panda.tauth.ui.imports.ImportWork
import com.panda.tauth.ui.list.AccountListScreen
import com.panda.tauth.ui.qr.QrEncoding
import com.panda.tauth.ui.qr.QrSymbol
import com.panda.tauth.ui.settings.FileWriteError
import com.panda.tauth.ui.settings.PlaintextExport
import com.panda.tauth.ui.settings.SettingsScreen
import com.panda.tauth.ui.settings.SettingsWork
import com.panda.tauth.ui.settings.ShellSettings
import com.panda.tauth.ui.unlock.UnlockScreen
import com.panda.tauth.vault.EntryAddError
import com.panda.tauth.vault.EntryChangeError
import com.panda.tauth.vault.EntryEdit
import com.panda.tauth.vault.ImportReadError
import com.panda.tauth.vault.VaultCreateError
import com.panda.tauth.vault.VaultEntry
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultRewriteError
import com.panda.tauth.vault.VaultUnlockError
import com.panda.tauth.vault.toEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

// Where the unlocked graph is. Locking leaves it entirely, which is the routing below rather than a
// destination of its own.
private sealed interface Route {
    data object Accounts : Route

    data object Add : Route

    data object Settings : Route

    // The rows it previews live in the holder rather than here, so a credential is not part of what
    // says which screen is on the window.
    data object Import : Route

    data class Edit(val id: String) : Route
}

// What a password entry is doing and what the last one reported, one per operation because the field
// holds that operation's cases. The array reaching `run` is a copy no holder owns and nothing wipes.
@Stable
internal class PasswordAttempt<E : VaultError> {
    var isRunning: Boolean by mutableStateOf(false)
        private set

    var error: E? by mutableStateOf(null)
        private set

    fun run(scope: CoroutineScope, password: CharArray, derive: suspend (CharArray) -> Outcome<Unit, E>) {
        isRunning = true
        error = null
        scope.launch {
            // A lock or a closing window cancels this scope mid-derivation, and a fill after the
            // suspension would be skipped on that path.
            try {
                error = (derive(password) as? Outcome.Failure)?.error
            } finally {
                password.fill(Char.MIN_VALUE)
                isRunning = false
            }
        }
    }
}

// Which screen is on the window follows the session's own state rather than a navigation stack, so a
// lock arriving from anywhere puts the password prompt up.
@Composable
fun TAuthApp(
    session: VaultSession,
    ticker: CodeTicker,
    clipboard: ClipboardCopy,
    preferences: PreferencesState,
    modifier: Modifier = Modifier,
    qrEncoding: QrEncoding = QrEncoding { null },
    shell: ShellSettings = ShellSettings(),
    isSingleInstanceUnprotected: Boolean = false,
    clock: Clock = Clock.System,
    onIdleLockSuppressed: (Boolean) -> Unit = {},
    onSaveQrImage: (suspend (QrSymbol) -> Outcome<Unit, FileWriteError>)? = null,
    scanning: QrScanning? = null,
    pasting: QrScanning? = null,
) {
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsState()
    val visible = remember { MutableStateFlow(emptySet<String>()) }

    var codes by remember { mutableStateOf(emptyMap<String, TotpCode>()) }
    var nowSeconds by remember { mutableStateOf(clock.now().epochSeconds) }
    var route by remember { mutableStateOf<Route>(Route.Accounts) }
    val creation = remember { PasswordAttempt<VaultCreateError>() }
    val unlocking = remember { PasswordAttempt<VaultUnlockError>() }
    val settings = remember { SettingsWork<VaultRewriteError>() }
    // A reorder or a delete fails on the list; a save fails on the destination that asked for it.
    // One slot for both would report a refused delete on the add screen, about a write never tried.
    var listError by remember { mutableStateOf<EntryChangeError?>(null) }
    // One slot per destination: an add reports a secret that will not decode and an edit reports an
    // account that is not there, so no one slot holds what both can report.
    var addError by remember { mutableStateOf<EntryAddError?>(null) }
    var editError by remember { mutableStateOf<EntryChangeError?>(null) }
    var isEntryBusy by remember { mutableStateOf(false) }

    // A vault the user has to open again is what empties the graph. A running derivation is not one:
    // the route it was started from is the route it returns to.
    val isClosed = state is SessionState.Locked || state is SessionState.NoVault

    // The policy is published with the unlocked state and is unreadable without it, so the screen
    // that started a rewrite goes on drawing the one the session published before it.
    LaunchedEffect(state) {
        (state as? SessionState.Unlocked)?.let { settings.adopt(it.policy) }
        if (isClosed) {
            route = Route.Accounts
            listError = null
            addError = null
            editError = null
            settings.clearError()
        }
    }

    CollectCodes(ticker, visible, state is SessionState.Unlocked) { tick ->
        codes = tick
        nowSeconds = clock.now().epochSeconds
    }

    val create: (CharArray) -> Unit = { creation.run(scope, it, session::create) }
    val unlock: (CharArray) -> Unit = { unlocking.run(scope, it, session::unlock) }

    WithSingleInstanceNotice(isSingleInstanceUnprotected, modifier) { screenModifier ->
        when (val current = state) {
            is SessionState.NoVault -> CreateVaultScreen(create, screenModifier, error = creation.error)

            is SessionState.Locked ->
                UnlockScreen(unlock, screenModifier, error = unlocking.error, lastReason = current.lastReason)

            is SessionState.Unlocking -> when {
                route is Route.Settings ->
                    SettingsDestination(session, settings, preferences, shell, scope, screenModifier) {
                        route = Route.Accounts
                    }

                creation.isRunning -> CreateVaultScreen(create, screenModifier, isBusy = true)

                else -> UnlockScreen(unlock, screenModifier, isBusy = true)
            }

            is SessionState.Unlocked -> UnlockedGraph(
                session = session,
                state = current,
                route = route,
                codes = codes,
                nowSeconds = nowSeconds,
                preferences = preferences,
                settings = settings,
                shell = shell,
                clipboard = clipboard,
                qrEncoding = qrEncoding,
                isEntryBusy = isEntryBusy,
                listError = listError,
                addError = addError,
                editError = editError,
                modifier = screenModifier,
                scope = scope,
                onRoute = {
                    // A destination opens on nothing the previous one left behind.
                    addError = null
                    editError = null
                    settings.clearError()
                    route = it
                },
                onVisibleChange = { visible.value = it },
                onIdleLockSuppressed = onIdleLockSuppressed,
                onSaveQrImage = onSaveQrImage,
                scanning = scanning,
                pasting = pasting,
                onListWork = { work -> scope.launch { listError = (work() as? Outcome.Failure)?.error } },
                onAddWork = scope.entryWork({ isEntryBusy = it }, { addError = it }),
                onEditWork = scope.entryWork({ isEntryBusy = it }, { editError = it }),
                clock = clock,
            )
        }
    }
}

// The preview stands for as long as there are rows to decide about, so the route follows them rather
// than being set by each of the paths that reads a file and finishes with one.
@Composable
private fun FollowPreview(isPreviewing: Boolean, route: Route, onRoute: (Route) -> Unit) {
    LaunchedEffect(isPreviewing) {
        if (isPreviewing) {
            onRoute(Route.Import)
        } else if (route is Route.Import) {
            onRoute(Route.Settings)
        }
    }
}

// The ticker ends with the lock that zeroes the keys behind it, so a collection belongs to one unlock
// rather than outliving the vault it was started over.
@Composable
private fun CollectCodes(
    ticker: CodeTicker,
    visible: MutableStateFlow<Set<String>>,
    isUnlocked: Boolean,
    onTick: (Map<String, TotpCode>) -> Unit,
) {
    LaunchedEffect(isUnlocked) {
        if (!isUnlocked) return@LaunchedEffect
        ticker.codes(visible).collect { onTick(it) }
    }
}

// Both destinations run their save the same way and differ only in the slot the failure lands in, which
// is that destination's own view of what its operation reports.
private fun <E : VaultError> CoroutineScope.entryWork(
    onBusy: (Boolean) -> Unit,
    onError: (E?) -> Unit,
): (suspend () -> Outcome<*, E>) -> Unit = { work ->
    launch {
        onBusy(true)
        onError((work() as? Outcome.Failure)?.error)
        onBusy(false)
    }
}

// One destination at a time, all of them inside the unlocked vault: leaving it is the routing above.
@Composable
private fun UnlockedGraph(
    session: VaultSession,
    state: SessionState.Unlocked,
    route: Route,
    codes: Map<String, TotpCode>,
    nowSeconds: Long,
    preferences: PreferencesState,
    settings: SettingsWork<VaultRewriteError>,
    shell: ShellSettings,
    clipboard: ClipboardCopy,
    qrEncoding: QrEncoding,
    isEntryBusy: Boolean,
    listError: EntryChangeError?,
    addError: EntryAddError?,
    editError: EntryChangeError?,
    modifier: Modifier,
    scope: CoroutineScope,
    onRoute: (Route) -> Unit,
    onVisibleChange: (Set<String>) -> Unit,
    onIdleLockSuppressed: (Boolean) -> Unit,
    onSaveQrImage: (suspend (QrSymbol) -> Outcome<Unit, FileWriteError>)?,
    scanning: QrScanning?,
    pasting: QrScanning?,
    onListWork: (suspend () -> Outcome<*, EntryChangeError>) -> Unit,
    onAddWork: (suspend () -> Outcome<*, EntryAddError>) -> Unit,
    onEditWork: (suspend () -> Outcome<*, EntryChangeError>) -> Unit,
    clock: Clock,
) {
    // Held here rather than above the graph, so the rows — which carry every secret the file offered
    // — end with the unlocked vault they were read against.
    val imports = remember { ImportWork() }

    FollowPreview(imports.isPreviewing, route, onRoute)

    when (route) {
        is Route.Accounts -> AccountListScreen(
            entries = state.entries,
            codes = codes,
            modifier = modifier,
            sortOrder = preferences.value.sortOrder,
            isSortDescending = preferences.value.sortDescending,
            clipboardClearSeconds = state.policy.clipboardClearSeconds,
            clipboard = clipboard,
            qrEncoding = qrEncoding,
            error = listError,
            // The ordering outlives the window it was chosen in, so it goes to the file rather than
            // into state the next launch starts without.
            onSortChange = { order, isDescending ->
                scope.launch { preferences.update { it.copy(sortOrder = order, sortDescending = isDescending) } }
            },
            onVisibleChange = onVisibleChange,
            onIdleLockSuppressed = onIdleLockSuppressed,
            onSaveQrImage = onSaveQrImage,
            onGenerate = { id -> session.generateHotpCode(id) },
            onDiscloseUri = { id, password -> session.discloseUri(id, password) },
            onMove = { id, index -> onListWork { session.moveEntry(id, index) } },
            onDelete = { id -> onListWork { session.deleteEntry(id) } },
            onEdit = { id -> onRoute(Route.Edit(id)) },
            onAdd = { onRoute(Route.Add) },
            onSettings = { onRoute(Route.Settings) },
            onLock = { session.lock(LockReason.Manual) },
        )

        is Route.Settings ->
            SettingsDestination(
                session = session,
                settings = settings,
                preferences = preferences,
                shell = shell,
                scope = scope,
                modifier = modifier,
                importError = imports.readError,
                onImport = { imports.open(scope, shell.onChooseImport) { session.readImport(it, clock.now()) } },
                onScanImport = { imports.open(scope, shell.onScanImport) { session.readImport(it, clock.now()) } },
            ) {
                // Leaving the screen leaves what the last read reported, as every other destination
                // leaves what it reported.
                imports.clearReadError()
                onRoute(Route.Accounts)
            }

        is Route.Import -> OverWindow(modifier = modifier, onDismiss = imports::clear) { inner ->
            ImportScreen(
                rows = imports.rows,
                modifier = inner,
                source = imports.source,
                note = imports.note,
                addAnyway = imports.addAnyway,
                isBusy = imports.isBusy,
                error = imports.addError,
                onToggleDuplicate = imports::toggle,
                onImport = { imports.add(scope, session::addEntries) },
                onCancel = imports::clear,
            )
        }

        is Route.Add -> AddDestination(
            scanning = scanning,
            pasting = pasting,
            epochSeconds = nowSeconds,
            isBusy = isEntryBusy,
            error = addError,
            modifier = modifier,
            onSave = { uri -> onAddWork { addAndReturn(session, uri, clock, onRoute) } },
            onLeave = { onRoute(Route.Accounts) },
        )

        is Route.Edit -> EditDestination(
            entry = state.entries.firstOrNull { it.id == route.id },
            id = route.id,
            isBusy = isEntryBusy,
            error = editError,
            modifier = modifier,
            onSave = { entry, edit -> onEditWork { editAndReturn(session, entry, edit, onRoute) } },
            onLeave = { onRoute(Route.Accounts) },
        )
    }
}

@Composable
private fun EditDestination(
    entry: UnlockedEntry?,
    id: String,
    isBusy: Boolean,
    error: EntryChangeError?,
    onSave: (UnlockedEntry, EntryEdit) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entry == null) {
        // Routing is a write to the state above, so it waits for the composition rather than running
        // inside it.
        LaunchedEffect(id) { onLeave() }
        return
    }
    OverWindow(modifier = modifier, onDismiss = onLeave) { inner ->
        EditAccountScreen(
            entry = entry,
            onSave = { edit -> onSave(entry, edit) },
            onCancel = onLeave,
            modifier = inner,
            isBusy = isBusy,
            error = error,
        )
    }
}

@Composable
private fun AddDestination(
    scanning: QrScanning?,
    pasting: QrScanning?,
    epochSeconds: Long,
    isBusy: Boolean,
    error: EntryAddError?,
    onSave: (OtpAuthUri) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverWindow(modifier = modifier, onDismiss = onLeave) { inner ->
        AddAccountScreen(
            scanning = scanning,
            pasting = pasting,
            onSave = onSave,
            onCancel = onLeave,
            epochSeconds = epochSeconds,
            modifier = inner,
            isBusy = isBusy,
            error = error,
        )
    }
}

// The destination is drawn from two states: an open vault, and the derivation a rewrite started here
// runs under. Both draw the same controls over the policy the session last published.
@Composable
private fun SettingsDestination(
    session: VaultSession,
    settings: SettingsWork<VaultRewriteError>,
    preferences: PreferencesState,
    shell: ShellSettings,
    scope: CoroutineScope,
    modifier: Modifier,
    importError: ImportReadError? = null,
    onImport: () -> Unit = {},
    onScanImport: () -> Unit = {},
    onBack: () -> Unit,
) {
    var isPlaintextRequested by remember { mutableStateOf(false) }
    var plaintextError by remember { mutableStateOf<FileWriteError?>(null) }
    // Read here rather than passed in: this destination is drawn from two branches, and only one of
    // them has an unlocked vault to count.
    val state by session.state.collectAsState()

    PlaintextExport(
        isRequested = isPlaintextRequested,
        accountCount = (state as? SessionState.Unlocked)?.entries?.size ?: 0,
        scope = scope,
        onDisclose = session::disclosePlaintext,
        onWrite = shell.onExportPlaintext,
        onFinished = { isPlaintextRequested = false },
        onWriteError = { plaintextError = it },
    )

    SettingsScreen(
        policy = settings.policy,
        preferences = preferences.value,
        modifier = modifier,
        shell = shell,
        isBusy = settings.isBusy,
        error = settings.error,
        exportError = settings.exportError,
        plaintextError = plaintextError,
        importError = importError,
        onPolicyChange = { policy -> settings.run(scope) { session.setPolicy(policy) } },
        // A refused preference write is reported where the file is written. What this slot carries is
        // what the vault refused, which is a different thing.
        onThemeChange = { theme -> scope.launch { preferences.update { it.copy(theme = theme) } } },
        onMinimiseToTrayChange = { isOn -> scope.launch { preferences.update { it.copy(minimiseToTray = isOn) } } },
        onStartMinimisedChange = { isOn -> scope.launch { preferences.update { it.copy(startMinimised = isOn) } } },
        onStartAtLoginChange = { isOn -> scope.launch { preferences.update { it.withStartAtLogin(isOn) } } },
        onChangePassword = { current, next ->
            settings.run(scope, current, next) { session.changePassword(current, next) }
        },
        onRotate = { password -> settings.run(scope, password) { session.rotateDek(password) } },
        // What leaves is the ciphertext the file already holds, so the shell places it without a gate.
        onExport = { settings.export(scope, session::exportEncrypted, shell.onExport) },
        // The gate and the warning belong to the flow above; this control only asks for it.
        onPlaintextExport = {
            plaintextError = null
            isPlaintextRequested = true
        },
        onImport = onImport,
        onScanImport = onScanImport,
        onBack = onBack,
    )
}

private suspend fun addAndReturn(
    session: VaultSession,
    uri: OtpAuthUri,
    clock: Clock,
    onRoute: (Route) -> Unit,
): Outcome<Unit, EntryAddError> {
    val outcome = session.addEntries(listOf(uri.toEntry(VaultEntry.newId(), clock.now())))
    if (outcome is Outcome.Success) onRoute(Route.Accounts)
    return outcome
}

private suspend fun editAndReturn(
    session: VaultSession,
    entry: UnlockedEntry,
    edit: EntryEdit,
    onRoute: (Route) -> Unit,
): Outcome<Unit, EntryChangeError> {
    val outcome = session.editEntry(entry.id, edit)
    if (outcome is Outcome.Success) onRoute(Route.Accounts)
    return outcome
}
