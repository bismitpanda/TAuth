package com.panda.tauth.ui.settings

import androidx.compose.runtime.Immutable
import com.panda.tauth.Outcome
import com.panda.tauth.vault.ExportFormat
import com.panda.tauth.vault.ImportReadError

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
    val canStartAtLogin: Boolean = false,
    val onReveal: () -> Unit = {},
    // The bytes are the vault's own ciphertext, so what leaves is what the file already holds. A
    // destination the user declines writes nothing and reports no failure.
    val onExport: suspend (ByteArray) -> Outcome<Unit, FileWriteError> = { Outcome.Success(Unit) },
    // The accounts in the clear. The format decides what the destination is named and nothing else;
    // the text is already what the file holds.
    val onExportPlaintext: suspend (String, ExportFormat) -> Outcome<Unit, FileWriteError> = { _, _ ->
        Outcome.Success(Unit)
    },
    // The text of a file the user chose, or nothing where they chose none. What it holds is the
    // shell's to fetch and the vault's to make sense of.
    val onChooseImport: suspend () -> Outcome<String?, ImportReadError> = { Outcome.Success(null) },
    // One code carries many accounts, so what it yields is an import rather than the single account
    // a scan on the add screen offers.
    val onScanImport: suspend () -> Outcome<String?, ImportReadError> = { Outcome.Success(null) },
)
