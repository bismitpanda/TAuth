package com.panda.tauth.vault

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val HOME: Path = Path.of("/synthetic-home")

private fun paths(os: OperatingSystem, env: Map<String, String> = emptyMap(), home: Path = HOME) =
    VaultPaths(os, { env[it] }, home.toString())

// Expected values are built with Path.of rather than written as "/"-separated literals, so the
// assertions hold on a Windows JDK where the separator differs.
class VaultPathsTest {
    @Test
    fun `Linux honours XDG_DATA_HOME when it is set`() {
        val directory = paths(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to Path.of("/data").toString())).directory
        assertEquals(Path.of("/data", "tauth"), directory)
    }

    @Test
    fun `Linux falls back to local share when XDG_DATA_HOME is unset`() {
        assertEquals(HOME.resolve(Path.of(".local", "share", "tauth")), paths(OperatingSystem.LINUX).directory)
    }

    @Test
    fun `Linux ignores a blank XDG_DATA_HOME`() {
        val directory = paths(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to "   ")).directory
        assertEquals(HOME.resolve(Path.of(".local", "share", "tauth")), directory)
    }

    @Test
    fun `Linux ignores a relative XDG_DATA_HOME`() {
        // XDG Base Directory Specification: a relative path in one of these variables is invalid and
        // must be ignored. Resolving it would put the vault under the working directory.
        val directory = paths(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to Path.of("data").toString())).directory
        assertEquals(HOME.resolve(Path.of(".local", "share", "tauth")), directory)
    }

    @Test
    fun `Windows ignores a relative APPDATA`() {
        val directory = paths(OperatingSystem.WINDOWS, mapOf("APPDATA" to Path.of("roaming").toString())).directory
        assertEquals(HOME.resolve(Path.of("AppData", "Roaming", "TAuth")), directory)
    }

    @Test
    fun `Windows ignores a blank APPDATA`() {
        val directory = paths(OperatingSystem.WINDOWS, mapOf("APPDATA" to "   ")).directory
        assertEquals(HOME.resolve(Path.of("AppData", "Roaming", "TAuth")), directory)
    }

    @Test
    fun `a resolved location is absolute`() {
        assertTrue(paths(OperatingSystem.LINUX).isResolved)
    }

    @Test
    fun `an empty home leaves the location unresolved`() {
        // Every branch is relative to the working directory once the home is gone, which would put
        // the vault wherever the application happened to be launched from.
        assertFalse(VaultPaths(OperatingSystem.LINUX, { null }, "").isResolved)
    }

    @Test
    fun `an empty home with an absolute XDG_DATA_HOME still resolves`() {
        assertTrue(VaultPaths(OperatingSystem.LINUX, { Path.of("/data").toString() }, "").isResolved)
    }

    @Test
    fun `macOS resolves under Application Support`() {
        val expected = HOME.resolve(Path.of("Library", "Application Support", "TAuth"))
        assertEquals(expected, paths(OperatingSystem.MACOS).directory)
    }

    @Test
    fun `macOS ignores XDG_DATA_HOME`() {
        val expected = HOME.resolve(Path.of("Library", "Application Support", "TAuth"))
        assertEquals(expected, paths(OperatingSystem.MACOS, mapOf("XDG_DATA_HOME" to "/data")).directory)
    }

    @Test
    fun `Windows honours APPDATA`() {
        val appData = Path.of("/roaming")
        val directory = paths(OperatingSystem.WINDOWS, mapOf("APPDATA" to appData.toString())).directory
        assertEquals(appData.resolve("TAuth"), directory)
    }

    @Test
    fun `Windows falls back to the roaming profile when APPDATA is unset`() {
        val expected = HOME.resolve(Path.of("AppData", "Roaming", "TAuth"))
        assertEquals(expected, paths(OperatingSystem.WINDOWS).directory)
    }

    @Test
    fun `the vault file sits in the resolved directory`() {
        val resolved = paths(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to Path.of("/data").toString()))
        assertEquals(Path.of("/data", "tauth", "vault.tauth"), resolved.vaultFile)
    }

    @Test
    fun `the temp file sits beside the vault`() {
        val resolved = paths(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to Path.of("/data").toString()))
        assertEquals(Path.of("/data", "tauth", "vault.tauth.tmp"), resolved.tempFile)
    }

    @Test
    fun `the lock file sits beside the vault`() {
        val resolved = paths(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to Path.of("/data").toString()))
        assertEquals(Path.of("/data", "tauth", "vault.lock"), resolved.lockFile)
    }

    @Test
    fun `the preferences file sits beside the vault`() {
        val resolved = paths(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to Path.of("/data").toString()))
        assertEquals(Path.of("/data", "tauth", "preferences.json"), resolved.preferencesFile)
    }

    @Test
    fun `the instance lock file sits beside the vault`() {
        val resolved = paths(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to Path.of("/data").toString()))
        assertEquals(Path.of("/data", "tauth", "instance.lock"), resolved.instanceLockFile)
    }

    @Test
    fun `the instance port file sits beside the vault`() {
        val resolved = paths(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to Path.of("/data").toString()))
        assertEquals(Path.of("/data", "tauth", "instance.port"), resolved.instancePortFile)
    }

    @Test
    fun `the instance lock is not the lock a write takes`() {
        // One lock is held for the life of a running instance and the other only across a write, so
        // a single file would let a second launch believe no instance is running.
        val resolved = paths(OperatingSystem.LINUX)
        assertNotEquals(resolved.lockFile, resolved.instanceLockFile)
    }

    @Test
    fun `a Mac OS X os_name is detected as macOS`() {
        assertEquals(OperatingSystem.MACOS, OperatingSystem.detect("Mac OS X"))
    }

    @Test
    fun `a Windows 11 os_name is detected as Windows`() {
        assertEquals(OperatingSystem.WINDOWS, OperatingSystem.detect("Windows 11"))
    }

    @Test
    fun `an unrecognized os_name is treated as Linux`() {
        assertEquals(OperatingSystem.LINUX, OperatingSystem.detect("FreeBSD"))
    }
}
