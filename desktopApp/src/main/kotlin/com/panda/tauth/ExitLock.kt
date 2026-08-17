package com.panda.tauth

import com.panda.tauth.session.LockReason

private const val HOOK_NAME = "tauth-exit-lock"

internal fun interface ShutdownHooks {
    fun add(hook: Thread)
}

internal object JvmShutdownHooks : ShutdownHooks {
    override fun add(hook: Thread) {
        try {
            Runtime.getRuntime().addShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // The shutdown is already under way, and the throw is the whole answer: a runtime on its
            // way down takes no hook.
        }
    }
}

// The key is zeroed and the clipboard taken back on a shutdown no window saw: a signal, a session
// logout, a quit the platform makes. The hook stands for the life of the process, so an exit routed
// through the tray's Quit locks once there and once here.
class ExitLock internal constructor(private val hooks: ShutdownHooks, private val lock: (LockReason) -> Unit) {
    constructor(lock: (LockReason) -> Unit) : this(JvmShutdownHooks, lock)

    // The hook runs on a JVM thread of its own, outside any composition, and calls the lock the tray
    // and the close request call.
    private val hook = Thread({ lock(LockReason.Exit) }, HOOK_NAME)

    fun install() = hooks.add(hook)
}
