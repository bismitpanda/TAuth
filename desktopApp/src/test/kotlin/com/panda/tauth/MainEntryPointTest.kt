package com.panda.tauth

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The packaged launcher resolves `compose.desktop.application.mainClass` only at startup. The test
// task passes it in `tauth.mainClass`, so a rename that misses either side is a failure here.
class MainEntryPointTest {

    @Test
    fun `the class named as the application entry point is on the runtime classpath`() {
        val declared = declaredMainClassName()
        assertNotNull(entryPointClass(declared), "$declared is not on the classpath")
    }

    @Test
    fun `the entry point declares a main method the JVM launcher can invoke`() {
        val declared = declaredMainClassName()
        val entryPoint = assertNotNull(entryPointClass(declared), "$declared is not on the classpath")
        val main = entryPoint.declaredMethods.firstOrNull { method ->
            method.name == "main" && method.parameterTypes.contentEquals(arrayOf(Array<String>::class.java))
        }
        assertNotNull(main, "$declared declares no main(String[])")
        assertTrue(
            Modifier.isPublic(main.modifiers) && Modifier.isStatic(main.modifiers),
            "$declared.main(String[]) is not public static",
        )
    }

    // Absent means the test is running outside the Gradle test task and has nothing to
    // check against, which fails rather than passes.
    private fun declaredMainClassName(): String = assertNotNull(
        System.getProperty(MAIN_CLASS_PROPERTY),
        "$MAIN_CLASS_PROPERTY is unset; the Gradle test task supplies it",
    )

    // `false` skips static initialization: naming the class must not start the
    // application or require a display.
    private fun entryPointClass(name: String): Class<*>? = try {
        Class.forName(name, false, MainEntryPointTest::class.java.classLoader)
    } catch (_: ClassNotFoundException) {
        null
    }

    private companion object {
        const val MAIN_CLASS_PROPERTY = "tauth.mainClass"
    }
}
