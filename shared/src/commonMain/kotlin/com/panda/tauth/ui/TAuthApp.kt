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
import com.panda.tauth.settings.Preferences
import com.panda.tauth.settings.SortOrder
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.TotpCode
import com.panda.tauth.ui.create.CreateVaultScreen
import com.panda.tauth.ui.edit.AddAccountScreen
import com.panda.tauth.ui.edit.EditAccountScreen
import com.panda.tauth.ui.list.AccountListScreen
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
    modifier: Modifier = Modifier,
    preferences: Preferences = Preferences(),
    clock: Clock = Clock.System,
) {
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsState()
    val visible = remember { MutableStateFlow(emptySet<String>()) }

    var codes by remember { mutableStateOf(emptyMap<String, TotpCode>()) }
    var nowSeconds by remember { mutableStateOf(clock.now().epochSeconds) }
    var route by remember { mutableStateOf<Route>(Route.Accounts) }
    var sortOrder by remember { mutableStateOf(preferences.sortOrder) }
    val attempt = remember { PasswordAttempt() }
    // A reorder or a delete fails on the list; a save fails on the destination that asked for it.
    // One slot for both would report a refused delete on the add screen, about a write never tried.
    var listError by remember { mutableStateOf<VaultError?>(null) }
    var entryError by remember { mutableStateOf<VaultError?>(null) }
    var isEntryBusy by remember { mutableStateOf(false) }

    val isUnlocked = state is SessionState.Unlocked

    // The ticker ends with the lock that zeroes the keys behind it, so a new one is collected for
    // each unlock rather than a single collection outliving the vault it was started over.
    LaunchedEffect(isUnlocked) {
        if (!isUnlocked) {
            route = Route.Accounts
            listError = null
            entryError = null
            return@LaunchedEffect
        }
        ticker.codes(visible).collect { tick ->
            codes = tick
            nowSeconds = clock.now().epochSeconds
        }
    }

    val create: (CharArray) -> Unit = { attempt.run(scope, it, isCreate = true, session::create) }
    val unlock: (CharArray) -> Unit = { attempt.run(scope, it, isCreate = false, session::unlock) }

    when (val current = state) {
        is SessionState.NoVault -> CreateVaultScreen(create, modifier, error = attempt.error)

        is SessionState.Locked ->
            UnlockScreen(unlock, modifier, error = attempt.error, lastReason = current.lastReason)

        // A derivation is running and the state alone does not say which one asked for it, so the
        // screen that took the password is the screen that reports its progress.
        is SessionState.Unlocking ->
            if (attempt.isCreating) {
                CreateVaultScreen(create, modifier, isBusy = true)
            } else {
                UnlockScreen(unlock, modifier, isBusy = true)
            }

        is SessionState.Unlocked -> UnlockedGraph(
            session = session,
            state = current,
            route = route,
            codes = codes,
            nowSeconds = nowSeconds,
            sortOrder = sortOrder,
            clipboard = clipboard,
            isEntryBusy = isEntryBusy,
            listError = listError,
            entryError = entryError,
            modifier = modifier,
            onRoute = {
                // A destination opens on nothing the previous one left behind.
                entryError = null
                route = it
            },
            onSortOrderChange = { sortOrder = it },
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

// One destination at a time, all of them inside the unlocked vault: leaving it is the routing above.
@Composable
private fun UnlockedGraph(
    session: VaultSession,
    state: SessionState.Unlocked,
    route: Route,
    codes: Map<String, TotpCode>,
    nowSeconds: Long,
    sortOrder: SortOrder,
    clipboard: ClipboardCopy,
    isEntryBusy: Boolean,
    listError: VaultError?,
    entryError: VaultError?,
    modifier: Modifier,
    onRoute: (Route) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
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
            sortOrder = sortOrder,
            clipboardClearSeconds = state.policy.clipboardClearSeconds,
            clipboard = clipboard,
            error = listError,
            onSortOrderChange = onSortOrderChange,
            onVisibleChange = onVisibleChange,
            onGenerate = { id -> session.generateHotpCode(id) },
            onDiscloseUri = { id, password -> session.discloseUri(id, password) },
            onMove = { id, index -> onListWork { session.moveEntry(id, index) } },
            onDelete = { id -> onListWork { session.deleteEntry(id) } },
            onEdit = { id -> onRoute(Route.Edit(id)) },
            onAdd = { onRoute(Route.Add) },
            onLock = { session.lock(LockReason.Manual) },
        )

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
