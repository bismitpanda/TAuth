package com.panda.tauth.settings

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun read(json: String): Preferences = preferencesJson.decodeFromString<Preferences>(json)

private fun write(preferences: Preferences): String = preferencesJson.encodeToString(preferences)

// Expected values are written as literals rather than through the constants that define them, so a
// changed default names itself here.
class PreferencesTest {
    // One default per test, so loosening any one of them names itself in the failure.

    @Test
    fun `the theme defaults to following the system`() {
        assertEquals(Theme.SYSTEM, Preferences().theme)
    }

    @Test
    fun `the sort order defaults to manual`() {
        assertEquals(SortOrder.MANUAL, Preferences().sortOrder)
    }

    @Test
    fun `starting minimised defaults to off`() {
        assertFalse(Preferences().startMinimised)
    }

    @Test
    fun `starting at login defaults to off`() {
        assertFalse(Preferences().startAtLogin)
    }

    @Test
    fun `starting at login takes the window out of the way with it`() {
        assertTrue(Preferences().withStartAtLogin(true).startMinimised)
    }

    @Test
    fun `starting at login is what the setting records`() {
        assertTrue(Preferences().withStartAtLogin(true).startAtLogin)
    }

    @Test
    fun `no longer starting at login leaves the window choice where it stood`() {
        val stored = Preferences(startAtLogin = true, startMinimised = true)

        assertTrue(stored.withStartAtLogin(false).startMinimised)
    }

    @Test
    fun `no longer starting at login clears the setting`() {
        val stored = Preferences(startAtLogin = true, startMinimised = true)

        assertFalse(stored.withStartAtLogin(false).startAtLogin)
    }

    @Test
    fun `no longer starting at login leaves a window that was never out of the way alone`() {
        val stored = Preferences(startAtLogin = true, startMinimised = false)

        assertFalse(stored.withStartAtLogin(false).startMinimised)
    }

    @Test
    fun `minimising to the tray defaults to on`() {
        assertTrue(Preferences().minimiseToTray)
    }

    @Test
    fun `the window defaults to 560 wide`() {
        assertEquals(560, Preferences().window.width)
    }

    @Test
    fun `the window defaults to 720 high`() {
        assertEquals(720, Preferences().window.height)
    }

    @Test
    fun `a window that has never been placed carries no position`() {
        // Nothing writes a position until a window exists to report one, and the platform places a
        // window that has none.
        assertNull(Preferences().window.x)
        assertNull(Preferences().window.y)
    }

    @Test
    fun `an empty object yields the full defaults`() {
        assertEquals(Preferences(), read("{}"))
    }

    @Test
    fun `an unknown key is ignored`() {
        assertEquals(Preferences(), read("""{"windowOpacity":0.5}"""))
    }

    @Test
    fun `a theme naming nothing reads as the default theme`() {
        assertEquals(Theme.SYSTEM, read("""{"theme":"mauve"}""").theme)
    }

    @Test
    fun `a theme of null reads as the default theme`() {
        assertEquals(Theme.SYSTEM, read("""{"theme":null}""").theme)
    }

    @Test
    fun `a sort order naming nothing reads as the default sort order`() {
        assertEquals(SortOrder.MANUAL, read("""{"sortOrder":"byColour"}""").sortOrder)
    }

    @Test
    fun `a window of null reads as the default geometry`() {
        assertEquals(WindowGeometry(), read("""{"window":null}""").window)
    }

    @Test
    fun `a negative window width reads as the smallest usable width`() {
        assertEquals(480, read("""{"window":{"width":-1}}""").window.width)
    }

    @Test
    fun `a window width past the widest the window opens at reads as that width`() {
        assertEquals(720, read("""{"window":{"width":2147483647}}""").window.width)
    }

    @Test
    fun `a window height below the smallest usable one reads as that height`() {
        assertEquals(360, read("""{"window":{"height":12}}""").window.height)
    }

    @Test
    fun `a window height past the tallest the window opens at reads as that height`() {
        assertEquals(900, read("""{"window":{"height":2147483647}}""").window.height)
    }

    @Test
    fun `a stored position within reach of a display is kept`() {
        assertEquals(WindowGeometry(560, 720, 120, 40), read("""{"window":{"x":120,"y":40}}""").window)
    }

    @Test
    fun `a position no display could hold is dropped`() {
        // Clamping it would open the window at the edge of the coordinate space; dropping it hands
        // the placement back to the platform.
        assertNull(read("""{"window":{"x":900000,"y":40}}""").window.x)
    }

    @Test
    fun `a position missing a coordinate is dropped`() {
        assertNull(read("""{"window":{"x":120}}""").window.x)
    }

    @Test
    fun `an array where the object belongs fails to decode`() {
        assertFailsWith<SerializationException> { read("[]") }
    }

    @Test
    fun `a truncated document fails to decode`() {
        assertFailsWith<SerializationException> { read("""{"theme":"da""") }
    }

    @Test
    fun `an empty document fails to decode`() {
        assertFailsWith<SerializationException> { read("") }
    }

    @Test
    fun `preferences round-trip through JSON`() {
        val preferences = Preferences(
            theme = Theme.DARK,
            sortOrder = SortOrder.RECENTLY_ADDED,
            startMinimised = true,
            startAtLogin = true,
            minimiseToTray = false,
            window = WindowGeometry(width = 640, height = 800, x = 12, y = -34),
        )
        assertEquals(preferences, read(write(preferences)))
    }

    @Test
    fun `the written document carries every preference`() {
        val keys = preferencesJson.parseToJsonElement(write(Preferences())).jsonObject.keys
        assertEquals(
            setOf(
                "theme",
                "sortOrder",
                "sortDescending",
                "startMinimised",
                "startAtLogin",
                "minimiseToTray",
                "window",
            ),
            keys,
        )
    }

    @Test
    fun `the written window carries every member of the geometry`() {
        // The file's window object is the geometry's own member list, so a member added to the model
        // appears here and a member dropped from the file disappears from it.
        val json = write(Preferences(window = WindowGeometry(x = 1, y = 2)))
        val window = preferencesJson.parseToJsonElement(json).jsonObject.getValue("window")
        assertEquals(setOf("width", "height", "x", "y"), window.jsonObject.keys)
    }

    @Test
    fun `the light theme is written by its lowercase name`() {
        assertTrue("\"light\"" in write(Preferences(theme = Theme.LIGHT)))
    }

    @Test
    fun `the recently added sort order is written as recentlyAdded`() {
        assertTrue("\"recentlyAdded\"" in write(Preferences(sortOrder = SortOrder.RECENTLY_ADDED)))
    }

    @Test
    fun `a locking setting in the file changes nothing`() {
        // Locking is governed inside the encrypted body. A plaintext copy would be a file an
        // attacker rewrites to switch the control off.
        val json = """{"idleTimeoutMinutes":0,"lockOnMinimise":false,"clipboardClearSeconds":0}"""
        assertEquals(Preferences(), read(json))
    }

    @Test
    fun `the written file carries no locking setting`() {
        assertFalse("lockOn" in write(Preferences()))
    }
}
