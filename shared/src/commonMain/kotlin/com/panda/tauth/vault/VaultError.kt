package com.panda.tauth.vault

// `detail` strings never carry secret material: a VaultError reaches log output and the screen.
sealed interface VaultError {
    // Absence established by looking. A failure to look is Io: this sends a caller to the create flow.
    data object NoVaultFile : VaultReadError

    // Creation refuses a path that already holds a vault, because the write would replace every
    // secret in it with those of a vault whose password the file's owner may never have entered.
    data object VaultFileExists : VaultCreateError

    // The unwrap did not authenticate: a wrong password, or a salt or wrap block rewritten by
    // someone who repaired the unkeyed checksum too. The two are not distinguishable.
    data object WrongPassword : VaultOpenError, PasswordCheckError

    // This and WrongPassword never share a user-facing message: one means retype, the other means
    // the file is damaged.
    data object IntegrityFailure : VaultOpenError

    data class Corrupt(val detail: String) :
        VaultReadError,
        VaultOpenError,
        PasswordCheckError,
        VaultAdoptError,
        ImportReadError,
        ImageReadError

    data class UnsupportedVersion(val found: Int, val supported: Int) :
        VaultOpenError,
        VaultAssembleError

    data class InvalidSecret(val detail: String) :
        VaultAdoptError,
        EntryAddError,
        UriParseError

    data class MalformedUri(val detail: String) : UriParseError

    // The vault holds no entry under the id an operation named: the row was deleted between the
    // click and the call. This never shares a message with Corrupt, which means a damaged file.
    data object NoSuchEntry : EntryLookupError

    // The values an operation would store are ones it refuses. `detail` states the rule rather than
    // the value it refused.
    data class InvalidEntry(val detail: String) :
        EntryAddError,
        EntryChangeError,
        DraftError

    // No live key: the vault was locked, or a lock overtook an unlock and the key it derived was
    // zeroed rather than installed.
    data object VaultClosed :
        VaultAdoptError,
        VaultEncodeError,
        PasswordGateError,
        EntryLookupError,
        ImportReadError

    // The reader refuses a file past a ceiling, so the writer refuses to produce one.
    data class TooLarge(val size: Int, val limit: Int) : VaultAssembleError

    data class Io(val cause: Throwable) :
        VaultReadError,
        VaultWriteError,
        ImportReadError,
        ImageReadError

    data class LockedByAnotherProcess(val path: String) : VaultWriteError
}

// An operation's view holds the cases it produces; a step's view holds what that step produces and
// names the operations it reaches, so an operation's cases are the union of its steps.
sealed interface VaultCreateError : VaultError

sealed interface VaultUnlockError : VaultError

sealed interface VaultRewriteError : VaultError

sealed interface EntryWriteError : VaultError

// Storing an entry the vault does not hold yet, and changing one it does.
sealed interface EntryAddError : EntryWriteError

sealed interface EntryChangeError : EntryWriteError

sealed interface DiscloseError : VaultError

sealed interface DraftError : VaultError

sealed interface UriParseError : DraftError

// Reading a file the user offers for import, which is a document this did not necessarily write:
// the file being something other than an export is the damage a read reports.
sealed interface ImportReadError : VaultError

// Reading an image the user offers to scan. It names no vault, so a vault closed under it is not one
// of its cases: what it reads is a picture, and the account it finds is not stored by finding it.
sealed interface ImageReadError : VaultError

sealed interface VaultReadError :
    VaultCreateError,
    VaultUnlockError,
    VaultRewriteError

// Encoding a body and putting it on disk, which is every write the session makes through an open vault.
sealed interface VaultCommitError :
    VaultCreateError,
    VaultRewriteError,
    EntryAddError,
    EntryChangeError

sealed interface VaultWriteError : VaultCommitError

// Reopening the file and writing it back out: what a password change and a key rotation report.
sealed interface VaultReencodeError : VaultRewriteError

sealed interface VaultOpenError :
    VaultCreateError,
    VaultUnlockError,
    VaultReencodeError

sealed interface VaultEncodeError :
    VaultCommitError,
    VaultReencodeError

// Building a file from a body and a key, which needs no open vault and so cannot find one closed.
sealed interface VaultAssembleError : VaultEncodeError

sealed interface VaultAdoptError :
    VaultCreateError,
    VaultUnlockError,
    VaultRewriteError

// The check at the disclosure gate, and finding the entry it releases once the check has passed.
sealed interface PasswordGateError : DiscloseError

sealed interface EntryLookupError :
    DiscloseError,
    EntryChangeError

sealed interface PasswordCheckError : PasswordGateError
