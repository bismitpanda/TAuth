package com.panda.tauth

import com.panda.tauth.settings.Preferences

// What a close request does.
enum class CloseAction {
    HIDE_TO_TRAY,
    EXIT,
}

// Where the window is when the application opens.
enum class StartupWindow {
    VISIBLE,

    // On the taskbar, minimised: out of the way and one click from being back.
    ICONIFIED,

    HIDDEN_TO_TRAY,
}

// What tray availability and the tray preferences mean for the window. The window layer reads this
// rather than working the same answer out at each of the places that acts on it.
//
// Minimising is not among the answers: it is the platform's own on every desktop, and leaving the
// screen belongs to the close request alone. A minimise that hid the window would leave
// `WindowState.isMinimized` never observably true wherever a tray is in use, so the minimise lock
// trigger would never fire and the policy governing it would govern nothing.
data class WindowLifecycle(
    val startup: StartupWindow,
    val onCloseRequest: CloseAction,
    val isTrayShown: Boolean,
    val canConfigureTray: Boolean,
) {
    companion object {
        fun of(isTraySupported: Boolean, preferences: Preferences): WindowLifecycle {
            // The window may leave the screen only while a tray icon stands to bring it back.
            // Without one, a hidden window leaves the application running, invisible and
            // unquittable short of killing the process. A desktop with no tray and a user who
            // turned the tray off both arrive here, and both take the same fallback.
            val hidesToTray = isTraySupported && preferences.minimiseToTray
            return WindowLifecycle(
                startup = when {
                    !preferences.startMinimised -> StartupWindow.VISIBLE

                    hidesToTray -> StartupWindow.HIDDEN_TO_TRAY

                    // Starting minimised is a request to open out of the way, which the taskbar
                    // grants without the window becoming unreachable.
                    else -> StartupWindow.ICONIFIED
                },
                onCloseRequest = if (hidesToTray) CloseAction.HIDE_TO_TRAY else CloseAction.EXIT,
                isTrayShown = hidesToTray,
                // Availability alone: the two tray preferences are the controls that set
                // minimiseToTray and startMinimised, so a desktop with a tray offers both however
                // they stand, and a desktop without one offers neither.
                canConfigureTray = isTraySupported,
            )
        }
    }
}
