package com.panda.tauth

import com.panda.tauth.session.LockReason

fun applyCloseRequest(action: CloseAction, hide: () -> Unit, quit: () -> Unit) = when (action) {
    CloseAction.HIDE_TO_TRAY -> hide()
    CloseAction.EXIT -> quit()
}

// The key is zeroed and the clipboard taken back before the process ends, rather than left to
// whatever the operating system does with an exiting application's heap.
fun lockThenExit(lock: (LockReason) -> Unit, exit: () -> Unit) {
    lock(LockReason.Exit)
    exit()
}
