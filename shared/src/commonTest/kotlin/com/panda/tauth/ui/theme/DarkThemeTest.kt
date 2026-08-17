package com.panda.tauth.ui.theme

import com.panda.tauth.settings.Theme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DarkThemeTest {

    @Test
    fun `the system theme is dark on a dark desktop`() {
        assertTrue(Theme.SYSTEM.isDark(isSystemDark = true))
    }

    @Test
    fun `the system theme is light on a light desktop`() {
        assertFalse(Theme.SYSTEM.isDark(isSystemDark = false))
    }

    @Test
    fun `the light theme is light on a dark desktop`() {
        assertFalse(Theme.LIGHT.isDark(isSystemDark = true))
    }

    @Test
    fun `the light theme is light on a light desktop`() {
        assertFalse(Theme.LIGHT.isDark(isSystemDark = false))
    }

    @Test
    fun `the dark theme is dark on a light desktop`() {
        assertTrue(Theme.DARK.isDark(isSystemDark = false))
    }

    @Test
    fun `the dark theme is dark on a dark desktop`() {
        assertTrue(Theme.DARK.isDark(isSystemDark = true))
    }
}
