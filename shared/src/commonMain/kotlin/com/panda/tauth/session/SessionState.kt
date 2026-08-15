package com.panda.tauth.session

import com.panda.tauth.settings.SecurityPolicy

sealed interface SessionState {
    // No file at the resolved path, which is the create flow's entry condition.
    data object NoVault : SessionState

    data class Locked(val lastReason: LockReason? = null) : SessionState

    // A derivation is running. It costs enough that the screen has to say so.
    data object Unlocking : SessionState

    // The policy travels with the state because it lives in the encrypted body and is unreadable
    // outside an open vault.
    data class Unlocked(val entries: List<UnlockedEntry>, val policy: SecurityPolicy) : SessionState
}
