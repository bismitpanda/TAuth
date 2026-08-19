package com.panda.tauth

import com.panda.tauth.session.LockReason
import com.panda.tauth.session.SessionState
import com.panda.tauth.settings.SecurityPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// One listener at a time, taken off when the subscription is detached, so input reaching the watch
// after a detach is observable as input reaching nothing.
private class FakeInputMonitor : InputMonitor {
    private var listener: (() -> Unit)? = null

    var listenCount = 0
        private set

    var detachCount = 0
        private set

    override fun listen(onInput: () -> Unit): InputSubscription {
        listenCount++
        listener = onInput
        return InputSubscription {
            detachCount++
            listener = null
        }
    }

    fun fireInput() {
        fireEvent(MouseEvent.MOUSE_MOVED)
    }

    // Events reach the monitor by id and its own answer decides which of them is a person, so a case
    // delivers what the desktop delivers.
    fun fireEvent(eventId: Int) {
        if (isUserInput(eventId)) listener?.invoke()
    }
}

// No clock and no sleep: an interval ends when the test ends it, and never otherwise.
private class FakeIdleDelay : IdleDelay {
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    val intervals = mutableListOf<Int>()

    override suspend fun elapse(minutes: Int) {
        intervals += minutes
        val gate = CompletableDeferred<Unit>()
        gates += gate
        gate.await()
    }

    fun finish(index: Int) {
        gates[index].complete(Unit)
    }
}

class IdleWatchTest {

    private val monitor = FakeInputMonitor()
    private val delay = FakeIdleDelay()
    private val watch = IdleWatch(monitor, delay)
    private val reported = mutableListOf<LockReason>()

    // Unconfined runs each resumption on the thread that causes it, so every assertion below sees a
    // settled watch without joining anything.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    private fun watchFor(timeoutMinutes: Int, isVisible: Boolean = true, isSuppressed: Boolean = false): Job =
        scope.launch {
            watch.awaitIdle(isVisible, timeoutMinutes, isSuppressed) { reported += it }
        }

    @Test
    fun `an interval that passes with no input reports the idle trigger`() {
        watchFor(5)

        delay.finish(0)

        assertEquals(listOf(LockReason.Idle), reported)
    }

    @Test
    fun `nothing is reported before the interval passes`() {
        watchFor(5)

        assertEquals(emptyList(), reported)
    }

    @Test
    fun `input inside the interval starts it again`() {
        watchFor(5)

        monitor.fireInput()
        delay.finish(0)

        assertEquals(emptyList(), reported)
    }

    // A window mapped or raised under a pointer nobody moved enters it, and an interval restarted on
    // that would hold the vault open for as long as a window keeps arriving.
    @Test
    fun `a window arriving under a resting pointer does not start the interval again`() {
        watchFor(5)

        monitor.fireEvent(MouseEvent.MOUSE_ENTERED)
        delay.finish(0)

        assertEquals(listOf(LockReason.Idle), reported)
    }

    @Test
    fun `a window leaving a resting pointer does not start the interval again`() {
        watchFor(5)

        monitor.fireEvent(MouseEvent.MOUSE_EXITED)
        delay.finish(0)

        assertEquals(listOf(LockReason.Idle), reported)
    }

    @Test
    fun `a key press inside the interval starts it again`() {
        watchFor(5)

        monitor.fireEvent(KeyEvent.KEY_PRESSED)
        delay.finish(0)

        assertEquals(emptyList(), reported)
    }

    @Test
    fun `the interval that follows input reports on its own`() {
        watchFor(5)

        monitor.fireInput()
        delay.finish(1)

        assertEquals(listOf(LockReason.Idle), reported)
    }

    @Test
    fun `the interval watched is the one supplied`() {
        watchFor(5)

        assertEquals(listOf(5), delay.intervals)
    }

    @Test
    fun `a second watch takes its own interval`() {
        watchFor(7)

        assertEquals(listOf(7), delay.intervals)
    }

    // The shell reads the interval off the state the session publishes and hands it straight to the
    // watch, so the two are crossed here rather than the interval being supplied to each in turn.
    @Test
    fun `the interval watched is the one the unlocked policy carries`() {
        val state = SessionState.Unlocked(emptyList(), SecurityPolicy(idleTimeoutMinutes = 9))

        scope.launch {
            watch.awaitIdle(isVisible = true, idleTimeoutMinutes(state), isSuppressed = false) { reported += it }
        }

        assertEquals(listOf(9), delay.intervals)
    }

    @Test
    fun `a locked vault leaves nothing being watched`() {
        val state = SessionState.Locked(LockReason.HiddenToTray)

        scope.launch {
            watch.awaitIdle(isVisible = true, idleTimeoutMinutes(state), isSuppressed = false) { reported += it }
        }

        assertEquals(0, monitor.listenCount)
    }

    @Test
    fun `a window off the screen is not watched`() {
        watchFor(5, isVisible = false)

        assertEquals(0, monitor.listenCount)
    }

    @Test
    fun `a window off the screen starts no interval`() {
        watchFor(5, isVisible = false)

        assertEquals(emptyList(), delay.intervals)
    }

    @Test
    fun `an idle timeout switched off is not watched`() {
        watchFor(0)

        assertEquals(0, monitor.listenCount)
    }

    // A symbol on screen is read off it rather than typed at, so the quiet this watches for is what
    // scanning one looks like.
    @Test
    fun `a suppressed watch is not watched`() {
        watchFor(5, isSuppressed = true)

        assertEquals(0, monitor.listenCount)
    }

    @Test
    fun `a suppressed watch starts no interval`() {
        watchFor(5, isSuppressed = true)

        assertEquals(emptyList(), delay.intervals)
    }

    // The suppression is lifted by starting the watch again rather than by reaching into a standing
    // one, so what a watch begun after it does is the whole of the hold ending.
    @Test
    fun `a watch begun once the suppression is lifted reports on its own`() {
        watchFor(5, isSuppressed = true)

        watchFor(5)
        delay.finish(0)

        assertEquals(listOf(LockReason.Idle), reported)
    }

    @Test
    fun `a watch that ends in a report takes its listener off`() {
        watchFor(5)

        delay.finish(0)

        assertEquals(1, monitor.detachCount)
    }

    @Test
    fun `a cancelled watch takes its listener off`() {
        val job = watchFor(5)

        job.cancel()

        assertEquals(1, monitor.detachCount)
    }

    @Test
    fun `a report ends the watch rather than starting another interval`() {
        watchFor(5)

        delay.finish(0)

        assertEquals(listOf(5), delay.intervals)
    }
}
