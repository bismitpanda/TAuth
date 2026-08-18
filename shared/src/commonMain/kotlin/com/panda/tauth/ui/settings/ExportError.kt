package com.panda.tauth.ui.settings

import com.panda.tauth.vault.VaultReadError

// Why a copy was not made. An export reads the vault and writes somewhere else, and the two failures
// are about different files: a message naming the wrong one sends the user to the wrong place.
sealed interface ExportError {
    // The vault could not be read, so there was nothing to copy. It was not written to and is
    // unchanged.
    data class VaultUnreadable(val cause: VaultReadError) : ExportError

    // The destination took the file but not the restriction that keeps it to its owner, so no
    // ciphertext was written into it.
    data object NotRestricted : ExportError

    data class Io(val cause: Throwable) : ExportError
}
