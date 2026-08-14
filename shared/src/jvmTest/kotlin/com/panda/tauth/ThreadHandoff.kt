package com.panda.tauth

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.fail

// A monitor handoff takes microseconds, so this is reached only by a target that waits somewhere
// other than the monitor or never runs at all. The passing path leaves the loop on the target's
// state and never on the clock.
private val HANDOFF_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(30)

// Returns once the thread is blocked entering a monitor or has finished, whichever comes first. The
// target's only wait must be that monitor entry: a target that waits elsewhere is reported by name
// at the limit rather than spun on for ever, and one that never contends finishes and fails its
// caller's assertion.
internal fun awaitBlockedOrFinished(thread: Thread, finished: AtomicBoolean, limitNanos: Long = HANDOFF_LIMIT_NANOS) {
    val deadline = System.nanoTime() + limitNanos
    while (!finished.get() && thread.state != Thread.State.BLOCKED) {
        // Subtracted rather than compared, so the reading survives the wrap nanoTime is allowed.
        if (System.nanoTime() - deadline >= 0) {
            fail("$thread is ${thread.state}: it neither entered the monitor nor finished")
        }
        Thread.onSpinWait()
    }
}
