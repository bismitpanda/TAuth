package com.panda.tauth.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.panda.tauth.password.MIN_MASTER_PASSWORD_LENGTH
import com.panda.tauth.settings.Preferences
import com.panda.tauth.settings.SecurityPolicy
import com.panda.tauth.settings.Theme
import com.panda.tauth.ui.components.ChoiceRow
import com.panda.tauth.ui.components.PasswordField
import com.panda.tauth.ui.components.PasswordFieldState
import com.panda.tauth.ui.components.ToggleRow
import com.panda.tauth.ui.theme.ButtonLabel
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.ui.theme.TauthIcons
import com.panda.tauth.vault.ImportReadError
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultReadError
import com.panda.tauth.vault.VaultRewriteError

internal const val SETTINGS_TITLE = "Settings"
internal const val SETTINGS_BACK_LABEL = "Back to accounts"

// The plaintext-versus-vault distinction is stated here and nowhere else on the screen.
internal const val SETTINGS_HEADER =
    "Appearance and tray settings are kept in a plain file that any program running as you can " +
        "change. Settings that control when the vault locks are kept inside the vault, and changing " +
        "one needs your password."

internal const val SECURITY_HEADING = "Password"
internal const val ENCRYPTION_HEADING = "Encryption key"
internal const val CURRENT_PASSWORD_LABEL = "Current password"
internal const val NEW_PASSWORD_LABEL = "New password"
internal const val CONFIRM_PASSWORD_LABEL = "Confirm new password"
internal const val CHANGE_PASSWORD_LABEL = "Change password"
internal const val ROTATE_PASSWORD_LABEL = "Password"
internal const val ROTATE_LABEL = "Re-encrypt vault"
internal const val ROTATE_NOTE =
    "Draws a new key and rewrites the file under it, so a leaked key opens only older copies."

internal const val LOCKING_HEADING = "Locking"
internal const val IDLE_LABEL = "Lock after this long without input"
internal const val MINIMIZE_LOCK_LABEL = "Lock when the window is minimized"
internal const val GRACE_LABEL = "Wait this long before locking a hidden window"
internal const val FOCUS_LOSS_LABEL = "Lock when the window loses focus"

internal const val CLIPBOARD_HEADING = "Clipboard"
internal const val CLIPBOARD_LABEL = "Clear a copied code after"

internal const val APPEARANCE_HEADING = "Appearance"
internal const val THEME_LABEL = "Theme"
internal const val THEME_SYSTEM_LABEL = "System"
internal const val THEME_LIGHT_LABEL = "Light"
internal const val THEME_DARK_LABEL = "Dark"

internal const val TRAY_HEADING = "Tray"
internal const val MINIMIZE_TO_TRAY_LABEL = "Close to the tray instead of quitting"
internal const val NO_TRAY_NOTE =
    "This desktop offers no system tray, so a window sent to one would leave TAuth running with " +
        "nothing to bring it back."

internal const val STARTUP_HEADING = "Startup"
internal const val START_AT_LOGIN_LABEL = "Start TAuth when I log in"
internal const val START_MINIMIZED_LABEL = "Start with the window out of the way"
internal const val NO_LAUNCHER_NOTE =
    "Starting at login needs an installed copy of TAuth to point at, which a build run from source " +
        "does not provide."

internal const val DATA_HEADING = "Data"
internal const val LOCATION_LABEL = "Vault file"
internal const val REVEAL_LABEL = "Show in file manager"
internal const val EXPORT_LABEL = "Export an encrypted copy"
internal const val PLAINTEXT_EXPORT_LABEL = "Export accounts unencrypted"
internal const val PLAINTEXT_EXPORT_NOTE =
    "Anything that can read the file it writes can generate the codes."

internal const val SETTINGS_PLAINTEXT_PROBLEM_TAG = "settings-plaintext-problem"

internal const val IMPORT_LABEL = "Import accounts"
internal const val IMPORT_NOTE =
    "Reads an unencrypted export, a list of otpauth:// URIs, or another authenticator's export QR " +
        "code, and shows what it found first."

internal const val SETTINGS_IMPORT_PROBLEM_TAG = "settings-import-problem"

internal const val SCAN_IMPORT_LABEL = "Scan an export code"

internal const val EXPORT_NOTE =
    "The copy is the vault file itself, and the same password opens it."

internal const val ABOUT_HEADING = "About"
internal const val VERSION_LABEL = "Version"
internal const val LICENSE_LABEL = "License"
internal const val PROTECTS_NOTE =
    "The vault file is encrypted whole, so a copy taken from a disk, a backup or a synced folder is " +
        "unreadable without your password, and any tampering is detected rather than decrypted. " +
        "Locking wipes the key from memory."
internal const val PROTECTS_NOT_NOTE =
    "It protects nothing against code running as you while the vault is open, a keylogger, a screen " +
        "capture, or a vault file replaced with an older copy of itself. Your password cannot be " +
        "recovered, and losing it loses every code in the vault."
internal const val BACKUP_NOTE =
    "TAuth keeps no backup of its own. A deleted account is recovered from an export or not at all."
internal const val CLOCK_NOTE =
    "Codes come from this computer's clock, which TAuth does not set for you. If the clock is off by " +
        "more than a code's lifetime, the codes will be rejected."

internal const val SETTINGS_HEADER_TAG = "settings-header"
internal const val SETTINGS_PROBLEM_TAG = "settings-problem"
internal const val SETTINGS_EXPORT_PROBLEM_TAG = "settings-export-problem"
internal const val SETTINGS_LOCATION_TAG = "settings-location"
internal const val CURRENT_PASSWORD_TAG = "settings-current-password"
internal const val NEW_PASSWORD_TAG = "settings-new-password"
internal const val CONFIRM_PASSWORD_TAG = "settings-confirm-password"
internal const val ROTATE_PASSWORD_TAG = "settings-rotate-password"
internal const val MINIMIZE_LOCK_TAG = "settings-lock-on-minimize"
internal const val FOCUS_LOSS_TAG = "settings-lock-on-focus-loss"
internal const val MINIMIZE_TO_TRAY_TAG = "settings-minimize-to-tray"
internal const val START_MINIMIZED_TAG = "settings-start-minimized"
internal const val START_AT_LOGIN_TAG = "settings-start-at-login"

// Minutes, and zero for a vault that never locks on its own.
private val IDLE_OPTIONS = listOf(0, 1, 5, 15)

// Seconds between the window leaving the screen and the key being zeroed.
private val GRACE_OPTIONS = listOf(0, 30, 120)

private val CLIPBOARD_OPTIONS = listOf(0, 10, 20, 60)

private const val GRACE_IMMEDIATE_LABEL = "Immediately"
private const val GRACE_LONG_LABEL = "2 min"
private const val IDLE_OFF_LABEL = "Off"
private const val CLIPBOARD_OFF_LABEL = "Never clear"

private const val SECONDS_PER_LONG_GRACE = 120

private fun idleOptionLabel(minutes: Int): String = if (minutes == 0) IDLE_OFF_LABEL else "$minutes min"

private fun graceOptionLabel(seconds: Int): String = when (seconds) {
    0 -> GRACE_IMMEDIATE_LABEL
    SECONDS_PER_LONG_GRACE -> GRACE_LONG_LABEL
    else -> "$seconds s"
}

private fun clipboardOptionLabel(seconds: Int): String = if (seconds == 0) CLIPBOARD_OFF_LABEL else "$seconds s"

private fun themeLabel(theme: Theme): String = when (theme) {
    Theme.SYSTEM -> THEME_SYSTEM_LABEL
    Theme.LIGHT -> THEME_LIGHT_LABEL
    Theme.DARK -> THEME_DARK_LABEL
}

// The policy shown is the one the session publishes, which moves only once the vault file holds it,
// so a refused write leaves the control where it stood.
@Composable
fun SettingsScreen(
    policy: SecurityPolicy,
    preferences: Preferences,
    modifier: Modifier = Modifier,
    shell: ShellSettings = ShellSettings(),
    isBusy: Boolean = false,
    error: VaultRewriteError? = null,
    exportError: VaultExportError? = null,
    plaintextError: FileWriteError? = null,
    importError: ImportReadError? = null,
    onPolicyChange: (SecurityPolicy) -> Unit = {},
    onThemeChange: (Theme) -> Unit = {},
    onMinimizeToTrayChange: (Boolean) -> Unit = {},
    onStartMinimizedChange: (Boolean) -> Unit = {},
    onStartAtLoginChange: (Boolean) -> Unit = {},
    onChangePassword: (CharArray, CharArray) -> Unit = { _, _ -> },
    onRotate: (CharArray) -> Unit = {},
    onExport: () -> Unit = {},
    onPlaintextExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onScanImport: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val isEnabled = !isBusy
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            Text(SETTINGS_TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack, enabled = isEnabled) { ButtonLabel(TauthIcons.back, SETTINGS_BACK_LABEL) }
        }
        Text(
            SETTINGS_HEADER,
            modifier = Modifier.testTag(SETTINGS_HEADER_TAG),
            style = MaterialTheme.typography.bodyMedium,
        )
        error?.let { failure ->
            Text(
                messageFor(failure),
                modifier = Modifier.testTag(SETTINGS_PROBLEM_TAG),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (isBusy) {
            CircularProgressIndicator()
        }
        SecurityGroup(isEnabled = isEnabled, onChangePassword = onChangePassword, onRotate = onRotate)
        LockingGroup(policy = policy, isEnabled = isEnabled, onPolicyChange = onPolicyChange)
        ClipboardGroup(policy = policy, isEnabled = isEnabled, onPolicyChange = onPolicyChange)
        AppearanceGroup(preferences = preferences, isEnabled = isEnabled, onThemeChange = onThemeChange)
        TrayGroup(
            preferences = preferences,
            canConfigureTray = shell.canConfigureTray,
            isEnabled = isEnabled,
            onMinimizeToTrayChange = onMinimizeToTrayChange,
        )
        StartupGroup(
            preferences = preferences,
            canConfigureTray = shell.canConfigureTray,
            canStartAtLogin = shell.canStartAtLogin,
            isEnabled = isEnabled,
            onStartAtLoginChange = onStartAtLoginChange,
            onStartMinimizedChange = onStartMinimizedChange,
        )
        DataGroup(
            shell = shell,
            isEnabled = isEnabled,
            exportError = exportError,
            plaintextError = plaintextError,
            importError = importError,
            onExport = onExport,
            onPlaintextExport = onPlaintextExport,
            onImport = onImport,
            onScanImport = onScanImport,
        )
        AboutGroup(shell = shell)
    }
}

@Composable
private fun Group(heading: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Text(
            heading,
            modifier = Modifier.padding(horizontal = spacing.small),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(spacing.medium),
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
                content = content,
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SecurityGroup(
    isEnabled: Boolean,
    onChangePassword: (CharArray, CharArray) -> Unit,
    onRotate: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = remember { PasswordFieldState() }
    val next = remember { PasswordFieldState() }
    val confirmation = remember { PasswordFieldState() }
    val rotation = remember { PasswordFieldState() }

    // destroy() is the owner's: it zeroes what each holder carries and stops it taking another
    // character.
    DisposableEffect(Unit) {
        onDispose {
            current.destroy()
            next.destroy()
            confirmation.destroy()
            rotation.destroy()
        }
    }

    val isMatched = remember(next.revision, confirmation.revision) { next.matches(confirmation) }
    val canChange = isEnabled &&
        current.length > 0 &&
        next.length >= MIN_MASTER_PASSWORD_LENGTH &&
        isMatched
    val canRotate = isEnabled && rotation.length > 0

    val change: () -> Unit = {
        if (canChange) {
            onChangePassword(current.copyValue(), next.copyValue())
            // No field holds the password across the derivation; the two arrays handed over are the
            // caller's to zero.
            current.clear()
            next.clear()
            confirmation.clear()
        }
    }
    val rotate: () -> Unit = {
        if (canRotate) {
            onRotate(rotation.copyValue())
            rotation.clear()
        }
    }

    val spacing = LocalSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        Group(SECURITY_HEADING) {
            MaskedField(CURRENT_PASSWORD_LABEL, current, CURRENT_PASSWORD_TAG, isEnabled, change)
            MaskedField(NEW_PASSWORD_LABEL, next, NEW_PASSWORD_TAG, isEnabled, change)
            MaskedField(CONFIRM_PASSWORD_LABEL, confirmation, CONFIRM_PASSWORD_TAG, isEnabled, change)
            Button(onClick = change, enabled = canChange) { ButtonLabel(TauthIcons.password, CHANGE_PASSWORD_LABEL) }
        }
        Group(ENCRYPTION_HEADING) {
            Note(ROTATE_NOTE)
            MaskedField(ROTATE_PASSWORD_LABEL, rotation, ROTATE_PASSWORD_TAG, isEnabled, rotate)
            Button(onClick = rotate, enabled = canRotate) { ButtonLabel(TauthIcons.generate, ROTATE_LABEL) }
        }
    }
}

@Composable
private fun MaskedField(
    label: String,
    state: PasswordFieldState,
    tag: String,
    isEnabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.extraSmall),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        PasswordField(
            state = state,
            modifier = Modifier.fillMaxWidth().testTag(tag),
            enabled = isEnabled,
            onSubmit = onSubmit,
        )
    }
}

@Composable
private fun LockingGroup(
    policy: SecurityPolicy,
    isEnabled: Boolean,
    onPolicyChange: (SecurityPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    Group(LOCKING_HEADING, modifier) {
        ChoiceRow(
            label = IDLE_LABEL,
            options = IDLE_OPTIONS,
            selected = policy.idleTimeoutMinutes,
            optionLabel = ::idleOptionLabel,
            onSelect = { onPolicyChange(policy.copy(idleTimeoutMinutes = it)) },
            enabled = isEnabled,
        )
        ToggleRow(
            label = MINIMIZE_LOCK_LABEL,
            isChecked = policy.lockOnMinimize,
            onCheckedChange = { onPolicyChange(policy.copy(lockOnMinimize = it)) },
            tag = MINIMIZE_LOCK_TAG,
            enabled = isEnabled,
        )
        ChoiceRow(
            label = GRACE_LABEL,
            options = GRACE_OPTIONS,
            selected = policy.hideGraceSeconds,
            optionLabel = ::graceOptionLabel,
            onSelect = { onPolicyChange(policy.copy(hideGraceSeconds = it)) },
            enabled = isEnabled,
        )
        ToggleRow(
            label = FOCUS_LOSS_LABEL,
            isChecked = policy.lockOnFocusLoss,
            onCheckedChange = { onPolicyChange(policy.copy(lockOnFocusLoss = it)) },
            tag = FOCUS_LOSS_TAG,
            enabled = isEnabled,
        )
    }
}

@Composable
private fun ClipboardGroup(
    policy: SecurityPolicy,
    isEnabled: Boolean,
    onPolicyChange: (SecurityPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    Group(CLIPBOARD_HEADING, modifier) {
        ChoiceRow(
            label = CLIPBOARD_LABEL,
            options = CLIPBOARD_OPTIONS,
            selected = policy.clipboardClearSeconds,
            optionLabel = ::clipboardOptionLabel,
            onSelect = { onPolicyChange(policy.copy(clipboardClearSeconds = it)) },
            enabled = isEnabled,
        )
    }
}

@Composable
private fun AppearanceGroup(
    preferences: Preferences,
    isEnabled: Boolean,
    onThemeChange: (Theme) -> Unit,
    modifier: Modifier = Modifier,
) {
    Group(APPEARANCE_HEADING, modifier) {
        ChoiceRow(
            label = THEME_LABEL,
            options = Theme.entries,
            selected = preferences.theme,
            optionLabel = ::themeLabel,
            onSelect = onThemeChange,
            enabled = isEnabled,
        )
    }
}

@Composable
private fun TrayGroup(
    preferences: Preferences,
    canConfigureTray: Boolean,
    isEnabled: Boolean,
    onMinimizeToTrayChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Group(TRAY_HEADING, modifier) {
        if (!canConfigureTray) {
            Note(NO_TRAY_NOTE)
        }
        ToggleRow(
            label = MINIMIZE_TO_TRAY_LABEL,
            isChecked = preferences.minimizeToTray,
            onCheckedChange = onMinimizeToTrayChange,
            tag = MINIMIZE_TO_TRAY_TAG,
            enabled = isEnabled && canConfigureTray,
        )
    }
}

@Composable
private fun StartupGroup(
    preferences: Preferences,
    canConfigureTray: Boolean,
    canStartAtLogin: Boolean,
    isEnabled: Boolean,
    onStartAtLoginChange: (Boolean) -> Unit,
    onStartMinimizedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Group(STARTUP_HEADING, modifier) {
        if (!canStartAtLogin) {
            Note(NO_LAUNCHER_NOTE)
        }
        ToggleRow(
            label = START_AT_LOGIN_LABEL,
            isChecked = preferences.startAtLogin,
            onCheckedChange = onStartAtLoginChange,
            tag = START_AT_LOGIN_TAG,
            enabled = isEnabled && canStartAtLogin,
        )
        ToggleRow(
            label = START_MINIMIZED_LABEL,
            isChecked = preferences.startMinimized,
            onCheckedChange = onStartMinimizedChange,
            tag = START_MINIMIZED_TAG,
            enabled = isEnabled && canConfigureTray && !preferences.startAtLogin,
        )
    }
}

@Composable
private fun DataGroup(
    shell: ShellSettings,
    isEnabled: Boolean,
    exportError: VaultExportError?,
    plaintextError: FileWriteError?,
    importError: ImportReadError?,
    onExport: () -> Unit,
    onPlaintextExport: () -> Unit,
    onImport: () -> Unit,
    onScanImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Group(DATA_HEADING, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
            Text(LOCATION_LABEL, style = MaterialTheme.typography.labelLarge)
            Text(
                shell.vaultLocation,
                modifier = Modifier.testTag(SETTINGS_LOCATION_TAG),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = shell.onReveal, enabled = isEnabled) {
                ButtonLabel(TauthIcons.reveal, REVEAL_LABEL)
            }
        }
        HorizontalDivider()
        Action(EXPORT_NOTE, exportError?.let(::messageFor), SETTINGS_EXPORT_PROBLEM_TAG) {
            Button(onClick = onExport, enabled = isEnabled) { ButtonLabel(TauthIcons.export, EXPORT_LABEL) }
        }
        Action(PLAINTEXT_EXPORT_NOTE, plaintextError?.let(::plaintextMessageFor), SETTINGS_PLAINTEXT_PROBLEM_TAG) {
            TextButton(onClick = onPlaintextExport, enabled = isEnabled) {
                ButtonLabel(TauthIcons.warning, PLAINTEXT_EXPORT_LABEL)
            }
        }
        // Two ways into one import, so they share the line that reports what the last of them read.
        Action(IMPORT_NOTE, importError?.let(::importMessageFor), SETTINGS_IMPORT_PROBLEM_TAG) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                TextButton(onClick = onImport, enabled = isEnabled) { ButtonLabel(TauthIcons.import, IMPORT_LABEL) }
                TextButton(onClick = onScanImport, enabled = isEnabled) {
                    ButtonLabel(TauthIcons.qr, SCAN_IMPORT_LABEL)
                }
            }
        }
    }
}

// Each failure sits under the control that asked for it: the three write to destinations the user
// picked separately, so one says nothing about the others'.
@Composable
private fun Action(note: String, problem: String?, tag: String, control: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.extraSmall)) {
        control()
        Note(note)
        problem?.let {
            Text(
                it,
                modifier = Modifier.testTag(tag),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// The file is one TAuth did not necessarily write, so what is damaged here is that file, not the vault.
private fun importMessageFor(error: ImportReadError): String = when (error) {
    is VaultError.Corrupt -> "Nothing was imported: ${error.detail}."
    is VaultError.Io -> "That file could not be read, so nothing was imported."
    is VaultError.VaultClosed -> "The vault locked before the file was read, so nothing was imported."
}

// Nothing about the vault reaches this: the accounts were read out of an open one.
private fun plaintextMessageFor(error: FileWriteError): String = when (error) {
    is ExportError.NotRestricted ->
        "That location cannot keep the accounts to you alone, so nothing was written there."

    is ExportError.Io -> "The accounts could not be written to that location."
}

@Composable
private fun AboutGroup(shell: ShellSettings, modifier: Modifier = Modifier) {
    Group(ABOUT_HEADING, modifier) {
        Text("$VERSION_LABEL: ${shell.version}", style = MaterialTheme.typography.bodyMedium)
        Text("$LICENSE_LABEL: ${shell.license}", style = MaterialTheme.typography.bodyMedium)
        Note(PROTECTS_NOTE)
        Note(PROTECTS_NOT_NOTE)
        Note(BACKUP_NOTE)
        Note(CLOCK_NOTE)
    }
}

// An export writes nothing to the vault, so no branch here reports one: the vault was read, and the
// copy was going somewhere else.
private fun messageFor(error: VaultExportError): String = when (error) {
    is ExportError.VaultUnreadable -> "No copy was made: " + readProblem(error.cause)

    is ExportError.NotRestricted ->
        "That location cannot keep the copy to you alone, so nothing was written there. " +
            "The vault is unchanged."

    is ExportError.Io -> "The copy could not be written to that location. The vault is unchanged."
}

private fun readProblem(error: VaultReadError): String = when (error) {
    is VaultError.NoVaultFile -> "there is no vault file at this location."
    is VaultError.Corrupt -> "the vault file is damaged."
    is VaultError.Io -> "the vault file could not be read."
}

private fun messageFor(error: VaultRewriteError): String = when (error) {
    // Kept apart from the damage cases below: this one means retype, those mean the file.
    is VaultError.WrongPassword -> "That password is not correct, so nothing was changed."

    is VaultError.VaultClosed -> "The vault locked during the change. Unlock to see where it stands."

    is VaultError.LockedByAnotherProcess -> "Another TAuth process is holding the vault file."

    // A rewrite reads the file and writes it back, so naming one half of that would be wrong about
    // the change having landed whenever the other half is the half that failed.
    is VaultError.Io -> "The vault file could not be read or written."

    is VaultError.TooLarge -> "The vault is larger than the file format allows."

    is VaultError.IntegrityFailure, is VaultError.Corrupt, is VaultError.InvalidSecret ->
        "The vault file is damaged."

    is VaultError.UnsupportedVersion -> "This vault was made by a newer version of TAuth."

    is VaultError.NoVaultFile -> "There is no vault file at this location."
}
