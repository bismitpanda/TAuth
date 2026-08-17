package com.panda.tauth

import com.panda.tauth.session.LockReason
import kotlin.test.Test
import kotlin.test.assertEquals

// The runtime is reached through this call and no other, so the test registers nothing with the JVM
// it is running in.
private class FakeShutdownHooks : ShutdownHooks {
    val added = mutableListOf<Thread>()

    override fun add(hook: Thread) {
        added += hook
    }
}

class ExitLockTest {

    private val hooks = FakeShutdownHooks()
    private val reasons = mutableListOf<LockReason>()
    private val exitLock = ExitLock(hooks) { reasons += it }

    @Test
    fun `installing registers one hook with the runtime`() {
        exitLock.install()

        assertEquals(1, hooks.added.size)
    }

    @Test
    fun `nothing is registered before the install`() {
        assertEquals(emptyList(), hooks.added)
    }

    @Test
    fun `the install locks nothing of its own`() {
        exitLock.install()

        assertEquals(emptyList(), reasons)
    }

    // Run on this thread rather than started, so the assertion needs no join and no sleep.
    @Test
    fun `the registered hook locks the vault for the exit`() {
        exitLock.install()

        hooks.added.single().run()

        assertEquals(listOf(LockReason.Exit), reasons)
    }
}
