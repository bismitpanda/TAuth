package com.panda.tauth

import com.panda.tauth.settings.Preferences

enum class CloseAction {
    HIDE_TO_TRAY,
    EXIT,
}

enum class StartupWindow {
    VISIBLE,

    ICONIFIED,

    HIDDEN_TO_TRAY,
}

// Minimizing is not among the answers: a minimize that hid the window would leave
// `WindowState.isMinimized` never observably true, so the minimize lock trigger would never fire.
data class WindowLifecycle(
    val startup: StartupWindow,
    val onCloseRequest: CloseAction,
    val isTrayShown: Boolean,
    val canConfigureTray: Boolean,
) {
    companion object {
        fun of(isTraySupported: Boolean, preferences: Preferences): WindowLifecycle {
            // The window may leave the screen only while a tray icon stands to bring it back:
            // without one, a hidden window leaves the application running, invisible and unquittable.
            val hidesToTray = isTraySupported && preferences.minimizeToTray
            return WindowLifecycle(
                startup = when {
                    !preferences.startMinimized -> StartupWindow.VISIBLE

                    hidesToTray -> StartupWindow.HIDDEN_TO_TRAY

                    // Starting minimized is a request to open out of the way, which the taskbar
                    // grants without the window becoming unreachable.
                    else -> StartupWindow.ICONIFIED
                },
                onCloseRequest = if (hidesToTray) CloseAction.HIDE_TO_TRAY else CloseAction.EXIT,
                isTrayShown = hidesToTray,
                // Availability alone: a desktop with a tray offers both preferences however they
                // stand, and a desktop without one offers neither.
                canConfigureTray = isTraySupported,
            )
        }
    }
}
