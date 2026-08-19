package com.panda.tauth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// jpackage reads the icon each platform declares only when it packages for that platform, and two of
// the three cannot be packaged on any one machine. The test task passes the declarations across, so a
// file renamed or emptied under `icons/` is a failure here rather than at a release nobody can
// rehearse.
class PackagedIconTest {

    @Test
    fun `the icon the linux package declares is there to package`() {
        assertPackagable("tauth.icon.linux")
    }

    @Test
    fun `the icon the macos package declares is there to package`() {
        assertPackagable("tauth.icon.macos")
    }

    @Test
    fun `the icon the windows package declares is there to package`() {
        assertPackagable("tauth.icon.windows")
    }

    // Emptiness is checked as well as existence: a zero-length file packages without complaint and
    // installs an application with no mark at all.
    private fun assertPackagable(property: String) {
        val declared = assertNotNull(
            System.getProperty(property),
            "$property is unset; the Gradle test task supplies it",
        )
        val icon = File(declared)
        assertTrue(icon.isFile, "$declared is not a file")
        assertTrue(icon.length() > 0, "$declared is empty")
    }
}
