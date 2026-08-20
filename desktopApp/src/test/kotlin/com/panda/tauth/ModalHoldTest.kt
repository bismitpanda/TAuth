package com.panda.tauth

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModalHoldTest {
    private val hold = ModalHold()

    @Test
    fun `nothing is held before a chooser opens`() {
        assertFalse(hold.isHeld)
    }

    @Test
    fun `a chooser holds while it runs`() = runBlocking {
        hold.around { assertTrue(hold.isHeld) }
    }

    @Test
    fun `a chooser that closes drops the hold`() = runBlocking {
        hold.around { }

        assertFalse(hold.isHeld)
    }

    @Test
    fun `a chooser that throws drops the hold`() = runBlocking {
        assertFailsWith<IOException> { hold.around { throw IOException("no chooser") } }

        assertFalse(hold.isHeld)
    }

    @Test
    fun `the inner chooser closing leaves the outer one holding`() = runBlocking {
        hold.around {
            hold.around { }
            assertTrue(hold.isHeld)
        }
    }

    @Test
    fun `both closing drops the hold`() = runBlocking {
        hold.around { hold.around { } }

        assertFalse(hold.isHeld)
    }

    @Test
    fun `the chooser's answer is what comes back`() = runBlocking {
        assertEquals("chosen", hold.around { "chosen" })
    }
}
