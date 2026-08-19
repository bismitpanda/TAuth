package com.panda.tauth

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.panda.tauth.session.CodeTicker
import com.panda.tauth.session.LockReason
import com.panda.tauth.session.SessionClipboard
import com.panda.tauth.session.VaultSession
import com.panda.tauth.settings.Preferences
import com.panda.tauth.settings.PreferencesError
import com.panda.tauth.settings.PreferencesState
import com.panda.tauth.settings.PreferencesStore
import com.panda.tauth.ui.ClipboardCopy
import com.panda.tauth.ui.CopyResult
import com.panda.tauth.ui.TAuthApp
import com.panda.tauth.ui.settings.ShellSettings
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.ui.theme.isDark
import com.panda.tauth.vault.VaultPaths
import com.panda.tauth.vault.VaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val APPLICATION_NAME = "TAuth"

private val LOGGER = System.getLogger("com.panda.tauth.Main")

// The role is claimed before any window, session or vault exists, so a launch a running instance
// acknowledges hands its show request over and opens none of them.
fun main() = startUnlessSuperseded(SingleInstance().claim(), ::runTAuth)

private fun runTAuth(role: InstanceRole) = application {
    // The scope belongs to the application composition, so shutting the application down cancels the
    // clipboard's pending clear and any lock the session has scheduled.
    val scope = rememberCoroutineScope()
    val clipboard = remember(scope) { ClipboardService(scope) }
    val paths = remember { VaultPaths() }
    val session = remember(scope, paths) {
        VaultSession(VaultStore(paths), SessionClipboard { clipboard.clearIfHoldsOwnValue() }, scope)
    }
    val ticker = remember(session) { CodeTicker(session) }
    val store = remember(paths) { PreferencesStore(paths) }
    // The document as the file held it at launch. The window opens on this and stays where the user
    // puts it, whatever is chosen in settings afterwards.
    val opening = remember(store) { store.load() }
    val preferences = remember(store, opening) { PreferencesState(opening) { store.record(it) } }
    val isTraySupported = remember { isSystemTraySupported() }
    val startupLifecycle = remember(isTraySupported, opening) { WindowLifecycle.of(isTraySupported, opening) }
    // Read live, so a tray preference chosen in settings governs what closing the window does without
    // waiting for a restart.
    val lifecycle = WindowLifecycle.of(isTraySupported, preferences.value)
    val windowState = remember(opening, startupLifecycle) { windowStateFor(opening.window, startupLifecycle.startup) }
    var isVisible by remember(startupLifecycle) { mutableStateOf(isVisibleAtStartup(startupLifecycle.startup)) }
    var shownBy by remember { mutableStateOf(ShowSource.USER) }
    val quit = { lockThenExit(session::lock, ::exitApplication) }
    // Whether the tray settings are offered is the lifecycle's answer, so the screen and the window
    // read one answer rather than each asking the toolkit.
    val shell = remember(paths, lifecycle.canConfigureTray) { shellSettings(paths, lifecycle.canConfigureTray) }
    val idleWatch = remember { IdleWatch() }
    val exitLock = remember(session) { ExitLock(session::lock) }
    val primary = role as? InstanceRole.Primary
    val windowRaise = remember { WindowRaise() }

    // A shutdown the window never sees — a signal, a session logout — reaches the key through the
    // runtime rather than through this composition, and the hook outlives the composition on purpose.
    LaunchedEffect(exitLock) { exitLock.install() }

    // Closing gives the lock back and takes the port file with it, so the launch that follows this
    // one finds no port naming a socket nobody holds.
    DisposableEffect(primary) { onDispose { primary?.close() } }

    RecordGeometry(scope, windowState, preferences)

    TAuthTray(
        isShown = lifecycle.isTrayShown,
        onShow = { isVisible = true },
        onLock = { session.lock(LockReason.Manual) },
        onQuit = quit,
    )

    Window(
        onCloseRequest = { applyCloseRequest(lifecycle.onCloseRequest, hide = { isVisible = false }, quit = quit) },
        visible = isVisible,
        state = windowState,
        title = APPLICATION_NAME,
        icon = tauthIcon(),
    ) {
        val windowInfo = LocalWindowInfo.current
        val sessionState by session.state.collectAsState()
        val idleMinutes = idleTimeoutMinutes(sessionState)
        var isIdleLockSuppressed by remember { mutableStateOf(false) }

        WatchPresence(session, windowState, windowInfo) {
            WindowPresence(isVisible, windowState.isMinimized, windowInfo.isWindowFocused, shownBy)
        }

        LaunchedEffect(windowRaise, primary) {
            val requests = primary?.showRequests ?: return@LaunchedEffect
            windowRaise.raiseOnRequest(
                requests = requests,
                onRaise = {
                    isVisible = true
                    windowState.isMinimized = false
                    window.raiseToFront()
                },
                onShownBy = { shownBy = it },
            )
        }

        // Keyed on the interval and the hold as well as the window, so a lock, an unlock or a hold
        // lifted each begin a whole interval against the policy the session publishes.
        LaunchedEffect(idleWatch, isVisible, idleMinutes, isIdleLockSuppressed) {
            idleWatch.awaitIdle(isVisible, idleMinutes, isIdleLockSuppressed, session::scheduleLock)
        }

        TauthTheme(darkTheme = preferences.value.theme.isDark(isSystemInDarkTheme())) {
            TAuthApp(
                session = session,
                ticker = ticker,
                clipboard = clipboard.asCopy(),
                preferences = preferences,
                qrEncoding = QrEncoder,
                shell = shell,
                isSingleInstanceUnprotected = role is InstanceRole.Unprotected,
                onIdleLockSuppressed = { isIdleLockSuppressed = it },
                scanning = { readQrImage { chooseQrImage() } },
                onSaveQrImage = { symbol ->
                    saveQrImage(symbol) { chooseSaveDestination(QR_IMAGE_TITLE, QR_IMAGE_FILE_NAME) }
                },
            )
        }
    }
}

// The four facts are sampled together, so a change to any of them is one report rather than four.
// The sample is a lambda because the effect outlives the composition that supplied it.
@Composable
private fun WatchPresence(
    session: VaultSession,
    windowState: WindowState,
    windowInfo: WindowInfo,
    presence: () -> WindowPresence,
) {
    LaunchedEffect(session, windowState, windowInfo) {
        snapshotFlow(presence).collect {
            applyWindowPresence(it, session::scheduleLock, session::cancelScheduledLock)
        }
    }
}

// The window's own state is where its geometry lives, and the file holds what a sample of it settles
// on. The write goes through the preference holder, so it carries whatever else was chosen since.
@Composable
private fun RecordGeometry(scope: CoroutineScope, windowState: WindowState, preferences: PreferencesState) {
    val recorder = remember(scope, preferences) {
        WindowGeometryRecorder(scope, preferences.value.window) { geometry ->
            preferences.update { it.copy(window = geometry) }
        }
    }
    LaunchedEffect(windowState, recorder) {
        snapshotFlow { recordedGeometry(windowState, preferences.value.window) }.collect(recorder::sample)
    }
}

// What the settings screen reports on and reaches the desktop through.
private fun shellSettings(paths: VaultPaths, canConfigureTray: Boolean): ShellSettings = ShellSettings(
    vaultLocation = paths.vaultFile.toString(),
    version = applicationVersion(),
    licence = LICENCE_NOTICE,
    canConfigureTray = canConfigureTray,
    onReveal = { revealInFileManager(paths.vaultFile) },
    onExport = { bytes -> exportVault(bytes) { chooseExportDestination() } },
    onExportPlaintext = { text, format ->
        exportPlaintext(text, format) { name -> chooseSaveDestination(PLAINTEXT_DIALOG_TITLE, name) }
    },
    onChooseImport = { readImportSource { chooseImportSource() } },
)

// A window behind another one, or on the desktop the user has left, is not back on screen for having
// been made visible.
private fun java.awt.Window.raiseToFront() {
    toFront()
    requestFocus()
}

// Every preference reaches the file through here. A refusal costs the next launch what was chosen
// since the last one, so it is logged rather than shown, and returned for a caller that retries.
private suspend fun PreferencesStore.record(preferences: Preferences): Outcome<Unit, PreferencesError> =
    withContext(Dispatchers.IO) {
        val outcome = save(preferences)
        if (outcome is Outcome.Failure) {
            LOGGER.log(System.Logger.Level.WARNING, "preferences were not stored: ${outcome.error::class.simpleName}")
        }
        outcome
    }

// The copied text is a code or a complete credential, so neither the message nor the log line carries
// it; the platform's failure detail stays here.
private fun ClipboardService.asCopy(): ClipboardCopy = ClipboardCopy { text, clearAfterSeconds ->
    when (val outcome = copy(text, clearAfterSeconds)) {
        is Outcome.Success -> CopyResult.COPIED

        is Outcome.Failure -> {
            LOGGER.log(System.Logger.Level.WARNING, "the clipboard refused a copy: ${outcome.error::class.simpleName}")
            CopyResult.REFUSED
        }
    }
}
