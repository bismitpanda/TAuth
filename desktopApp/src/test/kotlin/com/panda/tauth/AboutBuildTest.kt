package com.panda.tauth

import kotlin.test.Test
import kotlin.test.assertEquals

class AboutBuildTest {
    @Test
    fun `a packaged application reports the version jpackage recorded`() {
        assertEquals("2.4.1", applicationVersion { if (it == "jpackage.app-version") "2.4.1" else null })
    }

    @Test
    fun `a run from the build tree says so`() {
        assertEquals(DEVELOPMENT_VERSION, applicationVersion { null })
    }

    @Test
    fun `a blank version says the same`() {
        assertEquals(DEVELOPMENT_VERSION, applicationVersion { "  " })
    }
}
