package com.panda.tauth.settings

import com.panda.tauth.settings.WindowGeometry.Companion.DEFAULT_HEIGHT
import com.panda.tauth.settings.WindowGeometry.Companion.DEFAULT_WIDTH
import com.panda.tauth.settings.WindowGeometry.Companion.MAX_HEIGHT
import com.panda.tauth.settings.WindowGeometry.Companion.MAX_WIDTH
import com.panda.tauth.settings.WindowGeometry.Companion.MIN_HEIGHT
import com.panda.tauth.settings.WindowGeometry.Companion.MIN_WIDTH
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowBoundsTest {
    // Read through the clamp rather than off the constants: a comparison between two of those is
    // folded before the test runs and passes whatever they are changed to.
    @Test
    fun `the default width is one the clamp leaves alone`() {
        assertEquals(DEFAULT_WIDTH, WindowGeometry().clamped().width)
    }

    @Test
    fun `the default height is one the clamp leaves alone`() {
        assertEquals(DEFAULT_HEIGHT, WindowGeometry().clamped().height)
    }

    @Test
    fun `a stored width under the floor is brought up to it`() {
        assertEquals(MIN_WIDTH, WindowGeometry(width = 120).clamped().width)
    }

    @Test
    fun `a stored height under the floor is brought up to it`() {
        assertEquals(MIN_HEIGHT, WindowGeometry(height = 90).clamped().height)
    }

    @Test
    fun `a stored width past the ceiling is brought back to it`() {
        assertEquals(MAX_WIDTH, WindowGeometry(width = 1920).clamped().width)
    }

    @Test
    fun `a stored height past the ceiling is brought back to it`() {
        assertEquals(MAX_HEIGHT, WindowGeometry(height = 1080).clamped().height)
    }

    @Test
    fun `a stored size inside the bounds is left where it is`() {
        val geometry = WindowGeometry(width = 640, height = 800).clamped()
        assertEquals(640 to 800, geometry.width to geometry.height)
    }
}
