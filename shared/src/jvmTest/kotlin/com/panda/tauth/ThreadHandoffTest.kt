package com.panda.tauth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.fail

// Long enough that the wait under test reaches its own limit first on any machine, and reached only
// when that wait does not end at all.
private val REPORT_LIMIT_MILLIS = TimeUnit.SECONDS.toMillis(30)

private val SPIN_LIMIT_NANOS = TimeUnit.MILLISECONDS.toNanos(50)

private val PARK_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(30)

// Returns once the thread is parked. The wait under test ends on BLOCKED, which a thread can pass
// through while it starts, so the case is about a parked target or about nothing.
private fun awaitParked(thread: Thread) {
    val deadline = System.nanoTime() + PARK_LIMIT_NANOS
    while (thread.state != Thread.State.WAITING) {
        // Subtracted rather than compared, so the reading survives the wrap nanoTime is allowed.
        if (System.nanoTime() - deadline >= 0) fail("$thread is ${thread.state}: it never parked")
        Thread.onSpinWait()
    }
}

class ThreadHandoffTest {
    @Test
    fun `a thread that neither enters the monitor nor finishes is reported rather than spun on`() {
        val release = CountDownLatch(1)
        // WAITING, which is what a target contending for anything other than a monitor parks in.
        val parked = thread(isDaemon = true) { release.await() }
        awaitParked(parked)
        val reported = AtomicReference<Throwable>()
        // On its own thread so an unbounded spin is this assertion failing rather than a suite that
        // never ends.
        val waiter = thread(isDaemon = true) {
            reported.set(
                runCatching { awaitBlockedOrFinished(parked, AtomicBoolean(false), SPIN_LIMIT_NANOS) }
                    .exceptionOrNull(),
            )
        }
        waiter.join(REPORT_LIMIT_MILLIS)
        release.countDown()
        parked.join()
        assertIs<AssertionError>(reported.get())
    }
}
