package com.panda.tauth

import com.panda.tauth.settings.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

// Three booleans, so every configuration is a case below rather than a sample. Each expected
// lifecycle is written out member by member rather than through the expression that produced it.
private fun lifecycleOf(isTraySupported: Boolean, minimiseToTray: Boolean, startMinimised: Boolean): WindowLifecycle =
    WindowLifecycle.of(
        isTraySupported,
        Preferences(minimiseToTray = minimiseToTray, startMinimised = startMinimised),
    )

class WindowLifecycleTest {

    @Test
    fun `a tray in use takes the window on close`() {
        assertEquals(
            WindowLifecycle(
                startup = StartupWindow.VISIBLE,
                onCloseRequest = CloseAction.HIDE_TO_TRAY,
                isTrayShown = true,
                canConfigureTray = true,
            ),
            lifecycleOf(isTraySupported = true, minimiseToTray = true, startMinimised = false),
        )
    }

    @Test
    fun `a tray in use starts the window hidden in the tray`() {
        assertEquals(
            WindowLifecycle(
                startup = StartupWindow.HIDDEN_TO_TRAY,
                onCloseRequest = CloseAction.HIDE_TO_TRAY,
                isTrayShown = true,
                canConfigureTray = true,
            ),
            lifecycleOf(isTraySupported = true, minimiseToTray = true, startMinimised = true),
        )
    }

    @Test
    fun `a tray the user turned off exits on close`() {
        assertEquals(
            WindowLifecycle(
                startup = StartupWindow.VISIBLE,
                onCloseRequest = CloseAction.EXIT,
                isTrayShown = false,
                canConfigureTray = true,
            ),
            lifecycleOf(isTraySupported = true, minimiseToTray = false, startMinimised = false),
        )
    }

    @Test
    fun `a tray the user turned off starts the window iconified rather than hidden`() {
        assertEquals(
            WindowLifecycle(
                startup = StartupWindow.ICONIFIED,
                onCloseRequest = CloseAction.EXIT,
                isTrayShown = false,
                canConfigureTray = true,
            ),
            lifecycleOf(isTraySupported = true, minimiseToTray = false, startMinimised = true),
        )
    }

    @Test
    fun `a desktop with no tray exits on close though the tray preference is on`() {
        assertEquals(
            WindowLifecycle(
                startup = StartupWindow.VISIBLE,
                onCloseRequest = CloseAction.EXIT,
                isTrayShown = false,
                canConfigureTray = false,
            ),
            lifecycleOf(isTraySupported = false, minimiseToTray = true, startMinimised = false),
        )
    }

    @Test
    fun `a desktop with no tray starts the window iconified rather than hidden`() {
        assertEquals(
            WindowLifecycle(
                startup = StartupWindow.ICONIFIED,
                onCloseRequest = CloseAction.EXIT,
                isTrayShown = false,
                canConfigureTray = false,
            ),
            lifecycleOf(isTraySupported = false, minimiseToTray = true, startMinimised = true),
        )
    }

    @Test
    fun `no tray and the tray preference off exits on close`() {
        assertEquals(
            WindowLifecycle(
                startup = StartupWindow.VISIBLE,
                onCloseRequest = CloseAction.EXIT,
                isTrayShown = false,
                canConfigureTray = false,
            ),
            lifecycleOf(isTraySupported = false, minimiseToTray = false, startMinimised = false),
        )
    }

    @Test
    fun `no tray and the tray preference off starts the window iconified`() {
        assertEquals(
            WindowLifecycle(
                startup = StartupWindow.ICONIFIED,
                onCloseRequest = CloseAction.EXIT,
                isTrayShown = false,
                canConfigureTray = false,
            ),
            lifecycleOf(isTraySupported = false, minimiseToTray = false, startMinimised = true),
        )
    }
}
