package com.panda.tauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LAUNCHER = "/opt/tauth/bin/TAuth"

private const val SPACED_LAUNCHER = "/Applications/T Auth.app/Contents/MacOS/TAuth"
private const val AWKWARD_LAUNCHER = """/opt/t & a"uth/TAuth"""

class AutostartRecordTest {
    @Test
    fun `a packaged launcher is one to write a record for`() {
        assertTrue(isPackagedLauncher(LAUNCHER))
    }

    @Test
    fun `a run reporting the JVM has no launcher to name`() {
        assertFalse(isPackagedLauncher("/usr/lib/jvm/temurin-25/bin/java"))
    }

    @Test
    fun `a windows JVM has no launcher to name either`() {
        assertFalse(isPackagedLauncher("""C:\Program Files\Java\bin\javaw.exe"""))
    }

    @Test
    fun `a run reporting no command at all has no launcher to name`() {
        assertFalse(isPackagedLauncher(null))
    }

    @Test
    fun `the setting on with no record writes one`() {
        assertEquals(AutostartAction.WRITE, autostartAction(isEnabled = true, current = null, wanted = LAUNCHER))
    }

    @Test
    fun `a record naming another path is rewritten`() {
        assertEquals(
            AutostartAction.WRITE,
            autostartAction(isEnabled = true, current = "/old/TAuth", wanted = LAUNCHER),
        )
    }

    @Test
    fun `a record naming the current path is left alone`() {
        assertEquals(AutostartAction.LEAVE, autostartAction(isEnabled = true, current = LAUNCHER, wanted = LAUNCHER))
    }

    @Test
    fun `the setting off with a record removes it`() {
        assertEquals(AutostartAction.REMOVE, autostartAction(isEnabled = false, current = LAUNCHER, wanted = LAUNCHER))
    }

    @Test
    fun `the setting off with no record does nothing`() {
        assertEquals(AutostartAction.LEAVE, autostartAction(isEnabled = false, current = null, wanted = LAUNCHER))
    }

    @Test
    fun `a desktop entry declares itself an application`() {
        assertTrue("Type=Application" in desktopEntry(LAUNCHER))
    }

    @Test
    fun `a desktop entry survives the GNOME autostart key being read`() {
        assertTrue("X-GNOME-Autostart-enabled=true" in desktopEntry(LAUNCHER))
    }

    @Test
    fun `a desktop entry reads back the path it was given`() {
        assertEquals(LAUNCHER, commandInDesktopEntry(desktopEntry(LAUNCHER)))
    }

    @Test
    fun `a desktop entry reads back a path holding a space`() {
        assertEquals(SPACED_LAUNCHER, commandInDesktopEntry(desktopEntry(SPACED_LAUNCHER)))
    }

    @Test
    fun `a desktop entry reads back a path holding a quote`() {
        assertEquals(AWKWARD_LAUNCHER, commandInDesktopEntry(desktopEntry(AWKWARD_LAUNCHER)))
    }

    // Read against the rendered text: a render and a parse that are wrong in the same way agree.
    @Test
    fun `a desktop entry quotes the path it launches`() {
        assertTrue("""Exec="/opt/tauth/bin/TAuth"""" in desktopEntry(LAUNCHER))
    }

    @Test
    fun `a desktop entry escapes a quote inside the path`() {
        assertTrue("""a\"uth""" in desktopEntry(AWKWARD_LAUNCHER))
    }

    @Test
    fun `a file holding no Exec field names no command`() {
        assertNull(commandInDesktopEntry("[Desktop Entry]\nType=Application\n"))
    }

    @Test
    fun `a launch agent asks to be run at load`() {
        assertTrue("<key>RunAtLoad</key>" in launchAgent(LAUNCHER))
    }

    @Test
    fun `a launch agent reads back the path it was given`() {
        assertEquals(LAUNCHER, commandInLaunchAgent(launchAgent(LAUNCHER)))
    }

    @Test
    fun `a launch agent reads back a path holding a space`() {
        assertEquals(SPACED_LAUNCHER, commandInLaunchAgent(launchAgent(SPACED_LAUNCHER)))
    }

    @Test
    fun `a launch agent reads back a path holding an ampersand`() {
        assertEquals(AWKWARD_LAUNCHER, commandInLaunchAgent(launchAgent(AWKWARD_LAUNCHER)))
    }

    @Test
    fun `a launch agent names the executable rather than its label`() {
        assertEquals(LAUNCHER, commandInLaunchAgent(launchAgent(LAUNCHER)))
    }

    @Test
    fun `a launch agent escapes an ampersand inside the path`() {
        val rendered = launchAgent(AWKWARD_LAUNCHER)

        assertTrue("&amp;" in rendered, rendered)
    }

    @Test
    fun `a launch agent leaves no bare ampersand in the document`() {
        val rendered = launchAgent(AWKWARD_LAUNCHER).replace("&amp;", "")

        assertFalse("&" in rendered, rendered)
    }

    @Test
    fun `a plist holding no program arguments names no command`() {
        assertNull(commandInLaunchAgent("<plist><dict><key>Label</key><string>x</string></dict></plist>"))
    }

    @Test
    fun `the registry write names the run key`() {
        assertTrue(AUTOSTART_REGISTRY_KEY in registryAddCommand(LAUNCHER))
    }

    @Test
    fun `the registry write carries the path as its own argument`() {
        assertTrue(SPACED_LAUNCHER in registryAddCommand(SPACED_LAUNCHER))
    }

    @Test
    fun `the registry read takes the path off the value line`() {
        val output = "\r\nHKEY_CURRENT_USER\\...\\Run\r\n    TAuth    REG_SZ    $LAUNCHER\r\n\r\n"

        assertEquals(LAUNCHER, commandInRegistryOutput(output))
    }

    @Test
    fun `the registry read keeps a path holding spaces whole`() {
        val output = "    TAuth    REG_SZ    $SPACED_LAUNCHER\r\n"

        assertEquals(SPACED_LAUNCHER, commandInRegistryOutput(output))
    }

    @Test
    fun `a registry read finding nothing names no command`() {
        assertNull(commandInRegistryOutput("ERROR: The system was unable to find the specified value.\r\n"))
    }
}
