package com.panda.tauth

import java.awt.Desktop
import kotlin.test.Test
import kotlin.test.assertEquals

private fun supporting(vararg actions: Desktop.Action): (Desktop.Action) -> Boolean = { it in actions }

class FileManagerRevealTest {
    @Test
    fun `a desktop that selects a file in its manager is asked to`() {
        val supported = supporting(Desktop.Action.BROWSE_FILE_DIR, Desktop.Action.OPEN)

        assertEquals(RevealAction.SELECT_FILE, revealActionFor(supported))
    }

    // Several Linux desktops answer false for BROWSE_FILE_DIR and true for OPEN.
    @Test
    fun `a desktop that only opens a directory is asked to`() {
        assertEquals(RevealAction.OPEN_DIRECTORY, revealActionFor(supporting(Desktop.Action.OPEN)))
    }

    @Test
    fun `a desktop that does neither is asked for nothing`() {
        assertEquals(RevealAction.NONE, revealActionFor(supporting(Desktop.Action.PRINT)))
    }
}
