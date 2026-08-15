package com.panda.tauth.vault

// `detail` strings never carry secret material: a VaultError reaches log output and the screen.
sealed interface VaultError {
    // Absence established by looking. A failure to look is Io: this sends a caller to the create flow.
    data object NoVaultFile : VaultError

    // Creation refuses a path that already holds a vault, because the write would replace every
    // secret in it with those of a vault whose password the file's owner may never have entered.
    data object VaultFileExists : VaultError

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

    // The vault holds no entry under the id an operation named: the row was deleted between the
    // click and the call. This never shares a message with Corrupt, which means a damaged file.
    data object NoSuchEntry : VaultError

    // The values an operation would store are ones it refuses. `detail` states the rule rather than
    // the value it refused.
    data class InvalidEntry(val detail: String) : VaultError

    // No live key: the vault was locked, or a lock overtook an unlock and the key it derived was
    // zeroed rather than installed.
    data object VaultClosed : VaultError

    // The reader refuses a file past a ceiling, so the writer refuses to produce one.
    data class TooLarge(val size: Int, val limit: Int) : VaultError

    data class Io(val cause: Throwable) : VaultError

    data class LockedByAnotherProcess(val path: String) : VaultError
}
