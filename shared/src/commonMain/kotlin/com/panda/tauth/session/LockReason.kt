package com.panda.tauth.session

import com.panda.tauth.settings.SecurityPolicy

// What put the vault back behind its password.
enum class LockReason {
    Manual,
    HiddenToTray,
    Minimised,
    FocusLost,
    Idle,
    Exit,
}

// Hiding the window, asking for a lock and leaving carry no switch. Every other trigger reads the
// policy in the encrypted body, where an edit is detected rather than obeyed.
internal fun LockReason.isArmedBy(policy: SecurityPolicy): Boolean = when (this) {
    LockReason.Manual, LockReason.HiddenToTray, LockReason.Exit -> true
    LockReason.Minimised -> policy.lockOnMinimise
    LockReason.FocusLost -> policy.lockOnFocusLoss
    LockReason.Idle -> policy.idleTimeoutMinutes > 0
}

// The grace period covers the window leaving the screen, the one trigger the user reverses by
// bringing it back. An idle timeout has already elapsed by the time it reports.
internal fun LockReason.graceSeconds(policy: SecurityPolicy): Int = when (this) {
    LockReason.HiddenToTray, LockReason.Minimised -> policy.hideGraceSeconds
    LockReason.Manual, LockReason.FocusLost, LockReason.Idle, LockReason.Exit -> 0
}
