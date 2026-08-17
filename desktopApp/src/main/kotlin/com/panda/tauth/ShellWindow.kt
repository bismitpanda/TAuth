package com.panda.tauth

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.panda.tauth.settings.WindowGeometry
import com.panda.tauth.settings.clamped
import kotlin.math.roundToInt

fun isVisibleAtStartup(startup: StartupWindow): Boolean = startup != StartupWindow.HIDDEN_TO_TRAY

// A window opening in the tray is hidden rather than minimised, so the first raise puts it back where
// it stood instead of on the taskbar.
fun windowStateFor(geometry: WindowGeometry, startup: StartupWindow): WindowState = WindowState(
    isMinimized = startup == StartupWindow.ICONIFIED,
    position = positionOf(geometry),
    size = DpSize(geometry.width.dp, geometry.height.dp),
)

private fun positionOf(geometry: WindowGeometry): WindowPosition {
    val x = geometry.x
    val y = geometry.y
    return if (x != null && y != null) WindowPosition(x.dp, y.dp) else WindowPosition.PlatformDefault
}

// What the file should hold for a window in this state, and null for a state that records nothing: a
// minimised, maximised or full screen window carries that state's extent, not the one it returns to.
fun recordedGeometry(state: WindowState, stored: WindowGeometry): WindowGeometry? {
    if (state.isMinimized || state.placement != WindowPlacement.Floating) return null
    val position = state.position as? WindowPosition.Absolute
    return WindowGeometry(
        width = state.size.width.value.roundToInt(),
        height = state.size.height.value.roundToInt(),
        // A window the platform has yet to place reports no position of its own.
        x = position?.x?.value?.roundToInt() ?: stored.x,
        y = position?.y?.value?.roundToInt() ?: stored.y,
    ).clamped()
}
