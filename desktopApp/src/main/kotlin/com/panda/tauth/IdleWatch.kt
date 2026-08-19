package com.panda.tauth

import com.panda.tauth.session.LockReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import kotlin.time.Duration.Companion.minutes

// Pointer and key events go to the component under them, and a composition sees only its own nodes,
// so idleness is read off the toolkit's event stream and the handle is what takes the listener off.
internal fun interface InputMonitor {
    fun listen(onInput: () -> Unit): InputSubscription
}

internal fun interface InputSubscription {
    fun detach()
}

// A window mapped, raised or moved under a stationary pointer enters and exits it, so those two ids
// report the window arriving rather than a person. Real movement arrives as MOUSE_MOVED.
internal fun isUserInput(eventId: Int): Boolean =
    eventId != MouseEvent.MOUSE_ENTERED && eventId != MouseEvent.MOUSE_EXITED

// A mask names event masks and not ids, so MOUSE_EVENT_MASK carries the two above with the presses
// and the clicks: what the callback hears is decided here rather than by what is subscribed to.
internal fun inputListener(onInput: () -> Unit): AWTEventListener =
    AWTEventListener { if (isUserInput(it.id)) onInput() }

internal object AwtInputMonitor : InputMonitor {
    private const val INPUT_EVENTS = AWTEvent.MOUSE_EVENT_MASK or
        AWTEvent.MOUSE_MOTION_EVENT_MASK or
        AWTEvent.MOUSE_WHEEL_EVENT_MASK or
        AWTEvent.KEY_EVENT_MASK

    override fun listen(onInput: () -> Unit): InputSubscription {
        val toolkit = Toolkit.getDefaultToolkit()
        val listener = inputListener(onInput)
        toolkit.addAWTEventListener(listener, INPUT_EVENTS)
        return InputSubscription { toolkit.removeAWTEventListener(listener) }
    }
}

internal fun interface IdleDelay {
    suspend fun elapse(minutes: Int)
}

internal object SuspendingIdleDelay : IdleDelay {
    override suspend fun elapse(minutes: Int) = delay(minutes.minutes)
}

class IdleWatch internal constructor(private val monitor: InputMonitor, private val delay: IdleDelay) {
    constructor() : this(AwtInputMonitor, SuspendingIdleDelay)

    // Reports an interval that passed with no input and nothing beyond it; whether that locks the
    // vault is the session's answer.
    suspend fun awaitIdle(
        isVisible: Boolean,
        timeoutMinutes: Int,
        isSuppressed: Boolean,
        report: (LockReason) -> Unit,
    ) {
        // A window off the screen belongs to the hide trigger, whose own timer is already standing,
        // and an interval of no length leaves no stretch of quiet to observe.
        if (!isVisible || timeoutMinutes <= 0) return
        // Quiet is what this reads, and a screen being read rather than typed at is quiet: §9.7's
        // symbol is scanned with both hands on a phone.
        if (isSuppressed) return
        val input = Channel<Unit>(Channel.CONFLATED)
        val subscription = monitor.listen { input.trySend(Unit) }
        try {
            var isIdle = false
            while (!isIdle) {
                isIdle = elapsedWithoutInput(input, timeoutMinutes)
            }
            report(LockReason.Idle)
        } finally {
            subscription.detach()
            input.close()
        }
    }

    // True when the interval ran out first, false when input landed inside it. Whichever lands first
    // answers, and the scope this opens takes the other with it.
    private suspend fun elapsedWithoutInput(input: ReceiveChannel<Unit>, timeoutMinutes: Int): Boolean =
        coroutineScope {
            val elapsed = CompletableDeferred<Boolean>()
            val timer = launch {
                delay.elapse(timeoutMinutes)
                elapsed.complete(true)
            }
            val activity = launch {
                input.receive()
                elapsed.complete(false)
            }
            elapsed.await().also {
                timer.cancel()
                activity.cancel()
            }
        }
}
