package com.panda.tauth

import com.panda.tauth.session.LockReason
import com.panda.tauth.session.SessionState

// Who put the window on the screen. A SHOW arrives over loopback from any process on the machine and
// carries no evidence that anyone is at the window.
enum class ShowSource {
    USER,
    SHOW_REQUEST,
}

// What the window layer observed. None of it says what it means: whether a trigger is armed and how
// long its grace runs are the session's to answer from the policy in the encrypted body.
data class WindowPresence(
    val isVisible: Boolean,
    val isMinimised: Boolean,
    val isFocused: Boolean,
    val shownBy: ShowSource,
)

sealed interface WindowReport {
    data class Trigger(val reason: LockReason) : WindowReport

    data object Returned : WindowReport

    data object ReturnedUnfocused : WindowReport

    data object Raised : WindowReport
}

// A hidden window and a minimised one are both unfocused, so the state that took the window off the
// screen names the reason rather than the focus loss that comes with it.
fun windowReport(presence: WindowPresence): WindowReport = when {
    !presence.isVisible -> WindowReport.Trigger(LockReason.HiddenToTray)
    presence.isMinimised -> WindowReport.Trigger(LockReason.Minimised)
    presence.shownBy == ShowSource.SHOW_REQUEST -> WindowReport.Raised
    !presence.isFocused -> WindowReport.ReturnedUnfocused
    else -> WindowReport.Returned
}

fun applyWindowPresence(presence: WindowPresence, schedule: (LockReason) -> Unit, cancel: () -> Unit) {
    when (val report = windowReport(presence)) {
        is WindowReport.Trigger -> schedule(report.reason)

        WindowReport.Returned -> cancel()

        WindowReport.ReturnedUnfocused -> {
            // The window standing on the screen takes back the relock the hide scheduled, whatever
            // holds the focus; the focus loss is a trigger of its own for the session to judge.
            cancel()
            schedule(LockReason.FocusLost)
        }

        // A raise nobody at the machine asked for keeps the relock the window was already under.
        WindowReport.Raised -> Unit
    }
}

// The interval the session publishes with the unlocked body, which is where a trigger reads it from
// rather than from the plaintext preferences. A vault that is not open has no key left to take away.
fun idleTimeoutMinutes(state: SessionState): Int = when (state) {
    is SessionState.Unlocked -> state.policy.idleTimeoutMinutes
    is SessionState.Locked, SessionState.NoVault, SessionState.Unlocking -> 0
}
