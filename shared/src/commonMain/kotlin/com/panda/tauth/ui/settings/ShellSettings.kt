package com.panda.tauth.ui.settings

import androidx.compose.runtime.Immutable
import com.panda.tauth.Outcome

// What the application shell knows and no screen can ask for itself: where the vault file sits,
// whether this desktop has a tray, what this build is, and where an exported copy goes.
@Immutable
class ShellSettings(
    val vaultLocation: String = "",
    val version: String = "",
    val licence: String = "",
    // A composition the shell has told nothing offers no tray settings, since a tray it cannot
    // confirm is one the window has no way back from.
    val canConfigureTray: Boolean = false,
    val onReveal: () -> Unit = {},
    // The bytes are the vault's own ciphertext, so what leaves is what the file already holds. A
    // destination the user declines writes nothing and reports no failure.
    val onExport: suspend (ByteArray) -> Outcome<Unit, ExportError> = { Outcome.Success(Unit) },
)
