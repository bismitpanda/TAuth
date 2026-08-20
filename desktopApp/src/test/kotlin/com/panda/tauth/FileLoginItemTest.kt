package com.panda.tauth

import com.panda.tauth.vault.OperatingSystem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LAUNCHER = "/opt/tauth/bin/TAuth"
private const val OTHER_LAUNCHER = "/opt/tauth-1.0/bin/TAuth"

private const val HOME = "/home/somebody"

class FileLoginItemTest {
    private val directory: Path = createTempDirectory("tauth-login-item")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun `a record that was never written names no command`() {
        assertNull(itemAt("autostart/tauth.desktop").read().valueOrNull)
    }

    @Test
    fun `a written record reads back the launcher it was given`() {
        val item = itemAt("autostart/tauth.desktop")

        item.write(LAUNCHER)

        assertEquals(LAUNCHER, item.read().valueOrNull)
    }

    // The freedesktop directory is not there on a machine that has never had an autostart entry.
    @Test
    fun `a write creates the directory the record belongs in`() {
        itemAt("autostart/tauth.desktop").write(LAUNCHER)

        assertTrue(Files.exists(directory.resolve("autostart")))
    }

    @Test
    fun `a second write replaces the launcher the record named`() {
        val item = itemAt("autostart/tauth.desktop")

        item.write(OTHER_LAUNCHER)
        item.write(LAUNCHER)

        assertEquals(LAUNCHER, item.read().valueOrNull)
    }

    @Test
    fun `removing takes the file away`() {
        val item = itemAt("autostart/tauth.desktop")
        item.write(LAUNCHER)

        item.remove()

        assertFalse(Files.exists(directory.resolve("autostart/tauth.desktop")))
    }

    @Test
    fun `removing a record that is not there reports success`() {
        assertTrue(itemAt("autostart/tauth.desktop").remove() is Outcome.Success)
    }

    @Test
    fun `a launch agent round-trips through the file too`() {
        val item = FileLoginItem(directory.resolve("agent.plist"), ::launchAgent, ::commandInLaunchAgent)

        item.write(LAUNCHER)

        assertEquals(LAUNCHER, item.read().valueOrNull)
    }

    @Test
    fun `the linux record is the freedesktop autostart entry`() {
        val path = pathOf(OperatingSystem.LINUX, environment = { null })

        assertEquals("$HOME/.config/autostart/tauth.desktop", path)
    }

    @Test
    fun `an XDG config home is where the linux record goes`() {
        val path = pathOf(OperatingSystem.LINUX, environment = { "/somewhere/config" })

        assertEquals("/somewhere/config/autostart/tauth.desktop", path)
    }

    // XDG Base Directory Specification, "Basics": a relative path is invalid and ignored.
    @Test
    fun `a relative XDG config home is passed over`() {
        val path = pathOf(OperatingSystem.LINUX, environment = { "relative/config" })

        assertEquals("$HOME/.config/autostart/tauth.desktop", path)
    }

    @Test
    fun `the macos record is a launch agent under the user's library`() {
        val path = pathOf(OperatingSystem.MACOS, environment = { null })

        assertEquals("$HOME/Library/LaunchAgents/com.panda.tauth.plist", path)
    }

    // Each branch has to be given the pair that reads what it writes, so a record written as one
    // format and read as the other would come back as nothing.
    @Test
    fun `the linux branch reads back what the linux branch writes`() {
        assertRoundTrips(OperatingSystem.LINUX)
    }

    @Test
    fun `the macos branch reads back what the macos branch writes`() {
        assertRoundTrips(OperatingSystem.MACOS)
    }

    @Test
    fun `the windows branch goes to the registry rather than to a file`() {
        val item = loginItemFor(OperatingSystem.WINDOWS, environment = { null }, userHome = HOME)

        assertTrue(item is RegistryLoginItem)
    }

    private fun assertRoundTrips(os: OperatingSystem) {
        val item = loginItemFor(os, environment = { null }, userHome = directory.toString())

        item.write(LAUNCHER)

        assertEquals(LAUNCHER, item.read().valueOrNull, "$os did not read back what it wrote")
    }

    private fun pathOf(os: OperatingSystem, environment: (String) -> String?): String {
        val item = loginItemFor(os, environment = environment, userHome = HOME)

        return (item as FileLoginItem).path.toString()
    }

    private fun itemAt(relative: String) =
        FileLoginItem(directory.resolve(relative), ::desktopEntry, ::commandInDesktopEntry)
}
