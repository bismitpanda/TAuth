package com.panda.tauth.ui

// What a copy attempt did. The platform's own failure detail stays in the application shell, because
// a screen's whole response to a refusal is to say the text is not on the clipboard.
enum class CopyResult {
    COPIED,
    REFUSED,
}

// The clear delay arrives per call rather than from a policy held here: the policy lives in the
// encrypted vault body, and a stored copy would answer with a stale value.
fun interface ClipboardCopy {
    fun copy(text: String, clearAfterSeconds: Int): CopyResult
}
