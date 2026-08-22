package com.panda.tauth.settings

import kotlinx.serialization.Serializable

// Carried inside the encrypted body so an edit is detected rather than obeyed: a plaintext idle
// timeout is a file an attacker rewrites to switch off the control it names.
@Serializable
data class SecurityPolicy(
    val idleTimeoutMinutes: Int = DEFAULT_IDLE_TIMEOUT_MINUTES,
    val lockOnMinimize: Boolean = true,
    val lockOnFocusLoss: Boolean = false,
    val hideGraceSeconds: Int = 0,
    val clipboardClearSeconds: Int = DEFAULT_CLIPBOARD_CLEAR_SECONDS,
) {
    // Zero disables a control and is the user's to choose; a negative would disable one in a body
    // that appears to set it.
    init {
        require(idleTimeoutMinutes >= 0) { "idleTimeoutMinutes must not be negative" }
        require(hideGraceSeconds >= 0) { "hideGraceSeconds must not be negative" }
        require(clipboardClearSeconds >= 0) { "clipboardClearSeconds must not be negative" }
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MINUTES = 5
        const val DEFAULT_CLIPBOARD_CLEAR_SECONDS = 20
    }
}
