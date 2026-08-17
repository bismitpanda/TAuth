package com.panda.tauth

import java.awt.Canvas
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals

// A source an event needs and nothing else: no window is opened and no toolkit is asked for one.
private val SOURCE = Canvas()

private fun mouseEvent(id: Int): MouseEvent = MouseEvent(SOURCE, id, 0L, 0, 0, 0, 0, false)

private fun keyEvent(id: Int): KeyEvent = KeyEvent(SOURCE, id, 0L, 0, KeyEvent.VK_A, 'a')

// The listener the toolkit is handed, driven the way the toolkit drives it: an event goes in and what
// comes out is whether the callback heard it.
class InputListenerTest {

    private var heard = 0

    private val listener = inputListener { heard++ }

    @Test
    fun `a pointer movement reaches the callback`() {
        listener.eventDispatched(mouseEvent(MouseEvent.MOUSE_MOVED))

        assertEquals(1, heard)
    }

    @Test
    fun `a key press reaches the callback`() {
        listener.eventDispatched(keyEvent(KeyEvent.KEY_PRESSED))

        assertEquals(1, heard)
    }

    @Test
    fun `a pointer press reaches the callback`() {
        listener.eventDispatched(mouseEvent(MouseEvent.MOUSE_PRESSED))

        assertEquals(1, heard)
    }

    // The window arriving under a pointer nobody moved. It comes through the same mask as the press
    // above, so what keeps it from the callback is the listener rather than what was subscribed to.
    @Test
    fun `a pointer entering reaches nothing`() {
        listener.eventDispatched(mouseEvent(MouseEvent.MOUSE_ENTERED))

        assertEquals(0, heard)
    }

    @Test
    fun `a pointer exiting reaches nothing`() {
        listener.eventDispatched(mouseEvent(MouseEvent.MOUSE_EXITED))

        assertEquals(0, heard)
    }
}
