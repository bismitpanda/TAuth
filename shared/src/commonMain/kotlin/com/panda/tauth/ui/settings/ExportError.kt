package com.panda.tauth.ui.settings

import com.panda.tauth.vault.VaultReadError

// Why something the user asked for was not placed where they asked for it. A copy of the vault reads
// the vault first and a saved QR code does not, so the two report through different views of this.
sealed interface ExportError {
    // The vault could not be read, so there was nothing to copy. It was not written to and is
    // unchanged.
    data class VaultUnreadable(val cause: VaultReadError) : VaultExportError

    // The destination took the file but not the restriction that keeps it to its owner, so nothing
    // was written into it.
    data object NotRestricted : FileWriteError

    data class Io(val cause: Throwable) : FileWriteError
}

sealed interface VaultExportError : ExportError

// Placing bytes in a file only its owner can read, which is every destination outside the vault
// directory: a copy of the vault and a saved QR code both end here.
sealed interface FileWriteError : VaultExportError
