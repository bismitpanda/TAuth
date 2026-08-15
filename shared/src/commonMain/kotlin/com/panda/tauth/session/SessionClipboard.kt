package com.panda.tauth.session

// A lock clears the clipboard through this call rather than through a collector: locking for an exit
// is followed by the process ending, and no collector gets a turn before that.
fun interface SessionClipboard {
    fun clearIfHoldsOwnValue()
}
