package com.panda.tauth

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.panda.tauth.settings.WindowGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Two geometries differing in width, height and whether they carry a position, so no assertion below
// rests on one fixture's members.
private val PLACED = WindowGeometry(width = 1024, height = 800, x = 12, y = -34)
private val UNPLACED = WindowGeometry(width = 640, height = 480)

private fun stateOf(
    width: Int,
    height: Int,
    position: WindowPosition = WindowPosition.PlatformDefault,
    isMinimized: Boolean = false,
    placement: WindowPlacement = WindowPlacement.Floating,
): WindowState = WindowState(
    placement = placement,
    isMinimized = isMinimized,
    position = position,
    size = DpSize(width.dp, height.dp),
)

class ShellWindowTest {

    @Test
    fun `a window that opens on the screen is visible`() {
        assertTrue(isVisibleAtStartup(StartupWindow.VISIBLE))
    }

    @Test
    fun `a window that opens iconified is visible`() {
        // Iconified is a window on the taskbar, which the shell shows and the platform minimises.
        assertTrue(isVisibleAtStartup(StartupWindow.ICONIFIED))
    }

    @Test
    fun `a window that opens in the tray is not visible`() {
        assertFalse(isVisibleAtStartup(StartupWindow.HIDDEN_TO_TRAY))
    }

    @Test
    fun `the window opens at the stored size`() {
        assertEquals(DpSize(1024.dp, 800.dp), windowStateFor(PLACED, StartupWindow.VISIBLE).size)
    }

    @Test
    fun `a second geometry opens at its own size`() {
        assertEquals(DpSize(640.dp, 480.dp), windowStateFor(UNPLACED, StartupWindow.VISIBLE).size)
    }

    @Test
    fun `the window opens at the stored position`() {
        val position = assertIs<WindowPosition.Absolute>(windowStateFor(PLACED, StartupWindow.VISIBLE).position)

        assertEquals(WindowPosition(12.dp, (-34).dp), position)
    }

    @Test
    fun `a geometry with no position leaves the placement to the platform`() {
        assertEquals(WindowPosition.PlatformDefault, windowStateFor(UNPLACED, StartupWindow.VISIBLE).position)
    }

    @Test
    fun `a window opening iconified starts minimised`() {
        assertTrue(windowStateFor(PLACED, StartupWindow.ICONIFIED).isMinimized)
    }

    @Test
    fun `a window opening on the screen does not start minimised`() {
        assertFalse(windowStateFor(PLACED, StartupWindow.VISIBLE).isMinimized)
    }

    @Test
    fun `a window opening in the tray does not start minimised`() {
        // Minimised and hidden are different states, and a hidden window that was also minimised
        // would come back to the taskbar rather than to the screen.
        assertFalse(windowStateFor(UNPLACED, StartupWindow.HIDDEN_TO_TRAY).isMinimized)
    }

    @Test
    fun `a resized window records its size`() {
        val recorded = recordedGeometry(stateOf(700, 500, WindowPosition(80.dp, 60.dp)), PLACED)

        assertEquals(WindowGeometry(width = 700, height = 500, x = 80, y = 60), recorded)
    }

    @Test
    fun `a moved window records its position`() {
        val recorded = recordedGeometry(stateOf(1024, 800, WindowPosition((-5).dp, 300.dp)), UNPLACED)

        assertEquals(WindowGeometry(width = 1024, height = 800, x = -5, y = 300), recorded)
    }

    @Test
    fun `a window the platform has yet to place keeps the stored position`() {
        val recorded = recordedGeometry(stateOf(1024, 800), PLACED)

        assertEquals(WindowGeometry(width = 1024, height = 800, x = 12, y = -34), recorded)
    }

    @Test
    fun `a window with no stored position and none of its own records none`() {
        val recorded = assertNotNull(recordedGeometry(stateOf(640, 480), UNPLACED))

        assertNull(recorded.x)
    }

    @Test
    fun `a minimised window records nothing`() {
        assertNull(recordedGeometry(stateOf(200, 200, isMinimized = true), PLACED))
    }

    @Test
    fun `a maximised window records nothing`() {
        val recorded = recordedGeometry(
            stateOf(3840, 2160, WindowPosition(0.dp, 0.dp), placement = WindowPlacement.Maximized),
            UNPLACED,
        )

        assertNull(recorded)
    }

    @Test
    fun `a full screen window records nothing`() {
        val recorded = recordedGeometry(
            stateOf(3840, 2160, WindowPosition(0.dp, 0.dp), placement = WindowPlacement.Fullscreen),
            PLACED,
        )

        assertNull(recorded)
    }

    @Test
    fun `a width past any display records the ceiling`() {
        val recorded = assertNotNull(recordedGeometry(stateOf(99_999, 800), PLACED))

        assertEquals(16384, recorded.width)
    }

    @Test
    fun `a height below the smallest usable one records that height`() {
        val recorded = assertNotNull(recordedGeometry(stateOf(640, 12), UNPLACED))

        assertEquals(360, recorded.height)
    }

    @Test
    fun `a position no display could hold is recorded as none`() {
        val recorded = assertNotNull(recordedGeometry(stateOf(640, 480, WindowPosition(900_000.dp, 40.dp)), UNPLACED))

        assertNull(recorded.x)
    }
}
