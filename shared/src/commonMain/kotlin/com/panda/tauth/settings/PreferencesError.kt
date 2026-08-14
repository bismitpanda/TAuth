package com.panda.tauth.settings

// Never thrown; the store returns it. Separate from VaultError because a preferences failure says
// nothing about the vault and must not reach a message written about one.
sealed interface PreferencesError {
    data class Io(val cause: Throwable) : PreferencesError
}
