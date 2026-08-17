package com.panda.tauth

import com.panda.tauth.session.LockReason
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowCloseTest {

    private val calls = mutableListOf<String>()
    private val reasons = mutableListOf<LockReason>()

    @Test
    fun `a close that hides to the tray hides the window`() {
        applyCloseRequest(CloseAction.HIDE_TO_TRAY, hide = { calls += "hide" }, quit = { calls += "quit" })

        assertEquals(listOf("hide"), calls)
    }

    @Test
    fun `a close that exits quits the application`() {
        applyCloseRequest(CloseAction.EXIT, hide = { calls += "hide" }, quit = { calls += "quit" })

        assertEquals(listOf("quit"), calls)
    }

    @Test
    fun `an exit locks the vault for the reason it is`() {
        lockThenExit(lock = { reasons += it }, exit = { })

        assertEquals(listOf(LockReason.Exit), reasons)
    }

    @Test
    fun `an exit locks the vault before it ends the process`() {
        lockThenExit(lock = { calls += "lock" }, exit = { calls += "exit" })

        assertEquals(listOf("lock", "exit"), calls)
    }
}
