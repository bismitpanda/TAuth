package com.panda.tauth

import com.panda.tauth.session.LockReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// The toolkit's stream, one listener at a time. Events reach it by id and the monitor's own answer
// decides which of them is a person, so a case delivers what the desktop delivers.
private class RecordingInputMonitor : InputMonitor {
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

    fun fireEvent(eventId: Int) {
        if (isUserInput(eventId)) listener?.invoke()
    }
}

// What the shell writes to its window and reads back off it for the relock collector. Which source a
// raise reports is not written here: it is the answer under test.
private class RaisedWindow {
    var isVisible = false
        private set

    var shownBy = ShowSource.USER
        private set

    fun raise() {
        isVisible = true
    }

    // The close request and the tray both take the window off the screen this way.
    fun hide() {
        isVisible = false
    }

    fun shownBy(source: ShowSource) {
        shownBy = source
    }

    fun presence(isFocused: Boolean) =
        WindowPresence(isVisible = isVisible, isMinimized = false, isFocused = isFocused, shownBy = shownBy)
}

class WindowRaiseTest {

    private val monitor = RecordingInputMonitor()
    private val raise = WindowRaise(monitor)
    private val requests = MutableStateFlow(0L)
    private val window = RaisedWindow()

    // Every source the raise reported, in order, so a raise that reported none and a raise that
    // reported the wrong one are different failures.
    private val sources = mutableListOf<ShowSource>()

    private val scheduled = mutableListOf<LockReason>()
    private var cancels = 0

    // Both calls in the order the collector made them, since what a raise costs is a cancel that
    // follows a schedule.
    private val calls = mutableListOf<String>()

    // Unconfined runs each resumption on the thread that causes it, so every assertion below sees a
    // settled collection without joining anything.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    private fun startRaising(): Job = scope.launch {
        raise.raiseOnRequest(
            requests = requests,
            onRaise = { window.raise() },
            onShownBy = {
                sources += it
                window.shownBy(it)
            },
        )
    }

    private fun applyPresence(isFocused: Boolean) = applyWindowPresence(
        window.presence(isFocused),
        schedule = {
            scheduled += it
            calls += "schedule $it"
        },
        cancel = {
            cancels++
            calls += "cancel"
        },
    )

    @Test
    fun `a show request puts the window on the screen`() {
        startRaising()

        requests.value = 1

        assertEquals(true, window.isVisible)
    }

    @Test
    fun `a launch nobody else made raises nothing`() {
        startRaising()

        assertEquals(false, window.isVisible)
    }

    // A count rather than a signal: the window has to come back for the second launch as well as for
    // the first, and it is already on the screen by then.
    @Test
    fun `a second show request raises the window again`() {
        startRaising()
        requests.value = 1
        monitor.fireEvent(MouseEvent.MOUSE_MOVED)

        requests.value = 2

        assertEquals(ShowSource.SHOW_REQUEST, window.shownBy)
    }

    @Test
    fun `a show request that landed before the collection began raises the window`() {
        requests.value = 1

        startRaising()

        assertEquals(true, window.isVisible)
    }

    @Test
    fun `a raised window says a show request put it there`() {
        startRaising()

        requests.value = 1

        assertEquals(ShowSource.SHOW_REQUEST, window.shownBy)
    }

    @Test
    fun `a raise stands until input reports the user`() {
        startRaising()

        requests.value = 1

        assertEquals(listOf(ShowSource.SHOW_REQUEST), sources)
    }

    @Test
    fun `input after a raise reports the user`() {
        startRaising()
        requests.value = 1

        monitor.fireEvent(MouseEvent.MOUSE_MOVED)

        assertEquals(listOf(ShowSource.SHOW_REQUEST, ShowSource.USER), sources)
    }

    @Test
    fun `the window the user arrived at says the user is there`() {
        startRaising()
        requests.value = 1

        monitor.fireEvent(MouseEvent.MOUSE_MOVED)

        assertEquals(ShowSource.USER, window.shownBy)
    }

    // The raise puts the window under wherever the pointer was resting, and the desktop reports that
    // as the pointer entering it. Ending the raise on it would end every raise on the raise itself.
    @Test
    fun `a window arriving under a resting pointer leaves the raise standing`() {
        startRaising()
        requests.value = 1

        monitor.fireEvent(MouseEvent.MOUSE_ENTERED)

        assertEquals(listOf(ShowSource.SHOW_REQUEST), sources)
    }

    @Test
    fun `a window leaving a resting pointer leaves the raise standing`() {
        startRaising()
        requests.value = 1

        monitor.fireEvent(MouseEvent.MOUSE_EXITED)

        assertEquals(listOf(ShowSource.SHOW_REQUEST), sources)
    }

    @Test
    fun `a key press after a raise reports the user`() {
        startRaising()
        requests.value = 1

        monitor.fireEvent(KeyEvent.KEY_PRESSED)

        assertEquals(listOf(ShowSource.SHOW_REQUEST, ShowSource.USER), sources)
    }

    @Test
    fun `input before any raise reports nothing`() {
        startRaising()

        monitor.fireEvent(MouseEvent.MOUSE_MOVED)

        assertEquals(emptyList(), sources)
    }

    @Test
    fun `nothing listens for input before a raise`() {
        startRaising()

        assertEquals(0, monitor.listenCount)
    }

    @Test
    fun `the user arriving takes the listener off`() {
        startRaising()
        requests.value = 1

        monitor.fireEvent(MouseEvent.MOUSE_MOVED)

        assertEquals(1, monitor.detachCount)
    }

    @Test
    fun `a canceled collection takes the listener off`() {
        val job = startRaising()
        requests.value = 1

        job.cancel()

        assertEquals(1, monitor.detachCount)
    }

    @Test
    fun `a raise that follows the user's arrival waits for input of its own`() {
        startRaising()
        requests.value = 1
        monitor.fireEvent(MouseEvent.MOUSE_MOVED)

        requests.value = 2

        assertEquals(
            listOf(ShowSource.SHOW_REQUEST, ShowSource.USER, ShowSource.SHOW_REQUEST),
            sources,
        )
    }

    @Test
    fun `a show request arriving while a raise stands raises the window again`() {
        startRaising()
        requests.value = 1

        requests.value = 2

        assertEquals(2, monitor.listenCount)
    }

    @Test
    fun `a show request arriving while a raise stands drops the wait the first one left`() {
        startRaising()
        requests.value = 1

        requests.value = 2

        assertEquals(1, monitor.detachCount)
    }

    // The seam: the window is hidden, which schedules the relock, and the show request arrives after
    // it. What follows the hide is the whole subject, so the calls are read in order.
    @Test
    fun `a window a show request raised keeps the relock the hide scheduled`() {
        startRaising()
        applyPresence(isFocused = false)
        requests.value = 1

        applyPresence(isFocused = true)

        assertEquals(listOf("schedule HiddenToTray"), calls)
    }

    @Test
    fun `a window a show request raised onto an unfocused desktop keeps the relock the hide scheduled`() {
        startRaising()
        applyPresence(isFocused = false)
        requests.value = 1

        applyPresence(isFocused = false)

        assertEquals(listOf("schedule HiddenToTray"), calls)
    }

    // A window the desktop leaves unfocused is the case where a raise read as the user's return would
    // report a focus loss of its own on top of taking the relock back.
    @Test
    fun `a window a show request raised onto an unfocused desktop schedules nothing`() {
        startRaising()
        requests.value = 1

        applyPresence(isFocused = false)

        assertEquals(emptyList(), scheduled)
    }

    @Test
    fun `the user arriving at a raised window takes the relock back`() {
        startRaising()
        requests.value = 1
        monitor.fireEvent(MouseEvent.MOUSE_MOVED)

        applyPresence(isFocused = true)

        assertEquals(1, cancels)
    }

    // The user came back to the first raise and hid the window again, so the second launch meets a
    // relock the hide scheduled just as the first did.
    @Test
    fun `a second show request puts the window back under the relock the hide scheduled`() {
        startRaising()
        requests.value = 1
        monitor.fireEvent(MouseEvent.MOUSE_MOVED)
        window.hide()
        applyPresence(isFocused = false)
        requests.value = 2

        applyPresence(isFocused = true)

        assertEquals(listOf("schedule HiddenToTray"), calls)
    }
}
