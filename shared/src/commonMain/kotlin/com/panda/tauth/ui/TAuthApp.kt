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
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.TotpCode
import com.panda.tauth.ui.create.CreateVaultScreen
import com.panda.tauth.ui.edit.AddAccountScreen
import com.panda.tauth.ui.edit.EditAccountScreen
import com.panda.tauth.ui.list.AccountListScreen
import com.panda.tauth.ui.settings.SettingsScreen
import com.panda.tauth.ui.settings.SettingsWork
import com.panda.tauth.ui.settings.ShellSettings
import com.panda.tauth.ui.unlock.UnlockScreen
import com.panda.tauth.vault.EntryEdit
import com.panda.tauth.vault.VaultEntry
import com.panda.tauth.vault.VaultError
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

    data class Edit(val id: String) : Route
}

// What a password entry is doing and what the last one reported. The array reaching `run` is a copy
// the field made, which no holder owns and nothing else wipes.
@Stable
internal class PasswordAttempt {
    var isCreating: Boolean by mutableStateOf(false)
        private set

    var error: VaultError? by mutableStateOf(null)
        private set

    fun run(
        scope: CoroutineScope,
        password: CharArray,
        isCreate: Boolean,
        derive: suspend (CharArray) -> Outcome<Unit, VaultError>,
    ) {
        isCreating = isCreate
        error = null
        scope.launch {
            // A lock or a closing window cancels this scope mid-derivation, and a fill after the
            // suspension would be skipped on that path.
            try {
                error = (derive(password) as? Outcome.Failure)?.error
            } finally {
                password.fill(Char.MIN_VALUE)
                isCreating = false
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
    shell: ShellSettings = ShellSettings(),
    isSingleInstanceUnprotected: Boolean = false,
    clock: Clock = Clock.System,
) {
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsState()
    val visible = remember { MutableStateFlow(emptySet<String>()) }

    var codes by remember { mutableStateOf(emptyMap<String, TotpCode>()) }
    var nowSeconds by remember { mutableStateOf(clock.now().epochSeconds) }
    var route by remember { mutableStateOf<Route>(Route.Accounts) }
    val attempt = remember { PasswordAttempt() }
    val settings = remember { SettingsWork() }
    // A reorder or a delete fails on the list; a save fails on the destination that asked for it.
    // One slot for both would report a refused delete on the add screen, about a write never tried.
    var listError by remember { mutableStateOf<VaultError?>(null) }
    var entryError by remember { mutableStateOf<VaultError?>(null) }
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
            entryError = null
            settings.clearError()
        }
    }

    // The ticker ends with the lock that zeroes the keys behind it, so a new one is collected for
    // each unlock rather than a single collection outliving the vault it was started over.
    LaunchedEffect(state is SessionState.Unlocked) {
        if (state !is SessionState.Unlocked) return@LaunchedEffect
        ticker.codes(visible).collect { tick ->
            codes = tick
            nowSeconds = clock.now().epochSeconds
        }
    }

    val create: (CharArray) -> Unit = { attempt.run(scope, it, isCreate = true, session::create) }
    val unlock: (CharArray) -> Unit = { attempt.run(scope, it, isCreate = false, session::unlock) }

    WithSingleInstanceNotice(isSingleInstanceUnprotected, modifier) { screenModifier ->
        when (val current = state) {
            is SessionState.NoVault -> CreateVaultScreen(create, screenModifier, error = attempt.error)

            is SessionState.Locked ->
                UnlockScreen(unlock, screenModifier, error = attempt.error, lastReason = current.lastReason)

            // A derivation is running and the state alone does not say which one asked for it, so the
            // screen that took the password is the screen that reports its progress. The settings
            // route is reached only from an open vault, which is what makes it the asker here.
            is SessionState.Unlocking -> when {
                route is Route.Settings ->
                    SettingsDestination(session, settings, preferences, shell, scope, screenModifier) {
                        route = Route.Accounts
                    }

                attempt.isCreating -> CreateVaultScreen(create, screenModifier, isBusy = true)

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
                isEntryBusy = isEntryBusy,
                listError = listError,
                entryError = entryError,
                modifier = screenModifier,
                scope = scope,
                onRoute = {
                    // A destination opens on nothing the previous one left behind.
                    entryError = null
                    settings.clearError()
                    route = it
                },
                onVisibleChange = { visible.value = it },
                onListWork = { work -> scope.launch { listError = (work() as? Outcome.Failure)?.error } },
                onEntryWork = { work ->
                    scope.launch {
                        isEntryBusy = true
                        entryError = (work() as? Outcome.Failure)?.error
                        isEntryBusy = false
                    }
                },
                clock = clock,
            )
        }
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
    settings: SettingsWork,
    shell: ShellSettings,
    clipboard: ClipboardCopy,
    isEntryBusy: Boolean,
    listError: VaultError?,
    entryError: VaultError?,
    modifier: Modifier,
    scope: CoroutineScope,
    onRoute: (Route) -> Unit,
    onVisibleChange: (Set<String>) -> Unit,
    onListWork: (suspend () -> Outcome<*, VaultError>) -> Unit,
    onEntryWork: (suspend () -> Outcome<*, VaultError>) -> Unit,
    clock: Clock,
) {
    when (route) {
        is Route.Accounts -> AccountListScreen(
            entries = state.entries,
            codes = codes,
            modifier = modifier,
            sortOrder = preferences.value.sortOrder,
            clipboardClearSeconds = state.policy.clipboardClearSeconds,
            clipboard = clipboard,
            error = listError,
            // The ordering outlives the window it was chosen in, so it goes to the file rather than
            // into state the next launch starts without.
            onSortOrderChange = { order -> scope.launch { preferences.update { it.copy(sortOrder = order) } } },
            onVisibleChange = onVisibleChange,
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
            SettingsDestination(session, settings, preferences, shell, scope, modifier) {
                onRoute(Route.Accounts)
            }

        is Route.Add -> AddAccountScreen(
            onSave = { uri -> onEntryWork { addAndReturn(session, uri, clock, onRoute) } },
            onCancel = { onRoute(Route.Accounts) },
            epochSeconds = nowSeconds,
            modifier = modifier,
            isBusy = isEntryBusy,
            error = entryError,
        )

        is Route.Edit -> {
            val entry = state.entries.firstOrNull { it.id == route.id }
            if (entry == null) {
                // The account went while this destination was open. Routing is a write to the state
                // above, so it waits for the composition rather than running inside it.
                LaunchedEffect(route.id) { onRoute(Route.Accounts) }
            } else {
                EditAccountScreen(
                    entry = entry,
                    onSave = { edit -> onEntryWork { editAndReturn(session, entry, edit, onRoute) } },
                    onCancel = { onRoute(Route.Accounts) },
                    modifier = modifier,
                    isBusy = isEntryBusy,
                    error = entryError,
                )
            }
        }
    }
}

// The destination is drawn from two states: an open vault, and the derivation a rewrite started here
// runs under. Both draw the same controls over the policy the session last published.
@Composable
private fun SettingsDestination(
    session: VaultSession,
    settings: SettingsWork,
    preferences: PreferencesState,
    shell: ShellSettings,
    scope: CoroutineScope,
    modifier: Modifier,
    onBack: () -> Unit,
) {
    SettingsScreen(
        policy = settings.policy,
        preferences = preferences.value,
        modifier = modifier,
        shell = shell,
        isBusy = settings.isBusy,
        error = settings.error,
        exportError = settings.exportError,
        onPolicyChange = { policy -> settings.run(scope) { session.setPolicy(policy) } },
        // A refused preference write is reported where the file is written. What this slot carries is
        // what the vault refused, which is a different thing.
        onThemeChange = { theme -> scope.launch { preferences.update { it.copy(theme = theme) } } },
        onSortOrderChange = { order -> scope.launch { preferences.update { it.copy(sortOrder = order) } } },
        onMinimiseToTrayChange = { isOn -> scope.launch { preferences.update { it.copy(minimiseToTray = isOn) } } },
        onStartMinimisedChange = { isOn -> scope.launch { preferences.update { it.copy(startMinimised = isOn) } } },
        onChangePassword = { current, next ->
            settings.run(scope, current, next) { session.changePassword(current, next) }
        },
        onRotate = { password -> settings.run(scope, password) { session.rotateDek(password) } },
        // What leaves is the ciphertext the file already holds, so the shell places it without a gate.
        onExport = { settings.export(scope, session::exportEncrypted, shell.onExport) },
        onBack = onBack,
    )
}

private suspend fun addAndReturn(
    session: VaultSession,
    uri: OtpAuthUri,
    clock: Clock,
    onRoute: (Route) -> Unit,
): Outcome<Unit, VaultError> {
    val outcome = session.addEntry(uri.toEntry(VaultEntry.newId(), clock.now()))
    if (outcome is Outcome.Success) onRoute(Route.Accounts)
    return outcome
}

private suspend fun editAndReturn(
    session: VaultSession,
    entry: UnlockedEntry,
    edit: EntryEdit,
    onRoute: (Route) -> Unit,
): Outcome<Unit, VaultError> {
    val outcome = session.editEntry(entry.id, edit)
    if (outcome is Outcome.Success) onRoute(Route.Accounts)
    return outcome
}
