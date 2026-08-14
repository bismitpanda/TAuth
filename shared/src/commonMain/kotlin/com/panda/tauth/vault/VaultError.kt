package com.panda.tauth.vault

// `detail` strings never carry secret material: a VaultError reaches log output and the screen.
sealed interface VaultError {
    data object NoVaultFile : VaultError

    // The unwrap did not authenticate: a wrong password, or a salt or wrap block rewritten by
    // someone who repaired the unkeyed checksum too. The two are not distinguishable.
    data object WrongPassword : VaultError

    // This and WrongPassword never share a user-facing message: one means retype, the other means
    // the file is damaged.
    data object IntegrityFailure : VaultError

    data class Corrupt(val detail: String) : VaultError

    data class UnsupportedVersion(val found: Int, val supported: Int) : VaultError

    data class InvalidSecret(val detail: String) : VaultError

    data class MalformedUri(val detail: String) : VaultError

    data class Io(val cause: Throwable) : VaultError

    data class LockedByAnotherProcess(val path: String) : VaultError
}
