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
            // The shutdown is already under way, and the throw is the whole answer.
        }
    }
}

// The key is zeroed on a shutdown no window saw: a signal, a session logout, a quit the platform
// makes. An exit routed through the tray's Quit therefore locks once there and once here.
class ExitLock internal constructor(private val hooks: ShutdownHooks, private val lock: (LockReason) -> Unit) {
    constructor(lock: (LockReason) -> Unit) : this(JvmShutdownHooks, lock)

    private val hook = Thread({ lock(LockReason.Exit) }, HOOK_NAME)

    fun install() = hooks.add(hook)
}
