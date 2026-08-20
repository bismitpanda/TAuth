package com.panda.tauth.settings

import com.panda.tauth.settings.WindowGeometry.Companion.DEFAULT_HEIGHT
import com.panda.tauth.settings.WindowGeometry.Companion.DEFAULT_WIDTH
import com.panda.tauth.settings.WindowGeometry.Companion.MAX_HEIGHT
import com.panda.tauth.settings.WindowGeometry.Companion.MAX_WIDTH
import com.panda.tauth.settings.WindowGeometry.Companion.MIN_HEIGHT
import com.panda.tauth.settings.WindowGeometry.Companion.MIN_WIDTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowBoundsTest {
    @Test
    fun `the default width is inside the bounds the window opens at`() {
        assertTrue(DEFAULT_WIDTH in MIN_WIDTH..MAX_WIDTH, "$DEFAULT_WIDTH is outside $MIN_WIDTH..$MAX_WIDTH")
    }

    @Test
    fun `the default height is inside the bounds the window opens at`() {
        assertTrue(DEFAULT_HEIGHT in MIN_HEIGHT..MAX_HEIGHT, "$DEFAULT_HEIGHT is outside $MIN_HEIGHT..$MAX_HEIGHT")
    }

    @Test
    fun `the window's floor is below its ceiling`() {
        assertTrue(MIN_WIDTH < MAX_WIDTH && MIN_HEIGHT < MAX_HEIGHT)
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
