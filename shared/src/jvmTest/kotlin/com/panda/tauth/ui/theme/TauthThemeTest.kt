package com.panda.tauth.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Reads the same value out of the theme in both modes, so a token the theme fails to switch on
// darkTheme shows up as two equal readings.
private fun <T : Any> ComposeContentTestRule.readBothModes(read: @Composable () -> T): Pair<T, T> {
    var light: T? = null
    var dark: T? = null
    setContent {
        TauthTheme(darkTheme = false) { light = read() }
        TauthTheme(darkTheme = true) { dark = read() }
    }
    waitForIdle()
    return checkNotNull(light) to checkNotNull(dark)
}

private fun isAmber(color: Color) = color.red > color.green && color.green > color.blue

class TauthThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the dark surface is darker than the light surface`() {
        val (light, dark) = compose.readBothModes { MaterialTheme.colorScheme.surface }

        assertTrue(dark.luminance() < light.luminance())
    }

    // The theme paints a background and sets the content color to match it. Without that surface
    // LocalContentColor falls back to black in both modes, which is black text on a dark background.
    @Test
    fun `the content color follows the mode`() {
        val (light, dark) = compose.readBothModes { LocalContentColor.current }

        assertTrue(dark.luminance() > light.luminance())
    }

    @Test
    fun `the dark text color is lighter than the light text color`() {
        val (light, dark) = compose.readBothModes { MaterialTheme.colorScheme.onSurface }

        assertTrue(dark.luminance() > light.luminance())
    }

    @Test
    fun `the primary color differs between light and dark`() {
        val (light, dark) = compose.readBothModes { MaterialTheme.colorScheme.primary }

        assertNotEquals(light, dark)
    }

    @Test
    fun `the countdown color differs between light and dark`() {
        val (light, dark) = compose.readBothModes { LocalTauthColors.current.countdown }

        assertNotEquals(light, dark)
    }

    @Test
    fun `the expiring countdown color differs between light and dark`() {
        val (light, dark) = compose.readBothModes { LocalTauthColors.current.countdownExpiring }

        assertNotEquals(light, dark)
    }

    @Test
    fun `an expiring countdown is not the color of a running one`() {
        val (light, dark) = compose.readBothModes { LocalTauthColors.current }

        assertNotEquals(light.countdown, light.countdownExpiring)
        assertNotEquals(dark.countdown, dark.countdownExpiring)
    }

    @Test
    fun `the expiring countdown color is amber in the light theme`() {
        val (light, _) = compose.readBothModes { LocalTauthColors.current.countdownExpiring }

        assertTrue(isAmber(light))
    }

    @Test
    fun `the expiring countdown color is amber in the dark theme`() {
        val (_, dark) = compose.readBothModes { LocalTauthColors.current.countdownExpiring }

        assertTrue(isAmber(dark))
    }

    @Test
    fun `the spacing steps ascend`() {
        val (spacing, _) = compose.readBothModes { LocalSpacing.current }

        assertTrue(
            spacing.extraSmall < spacing.small &&
                spacing.small < spacing.medium &&
                spacing.medium < spacing.large &&
                spacing.large < spacing.extraLarge,
        )
    }

    @Test
    fun `the spacing scale does not change with the theme mode`() {
        val (light, dark) = compose.readBothModes { LocalSpacing.current }

        assertEquals(light, dark)
    }

    @Test
    fun `the code readout is set in a different family from the body text`() {
        val (families, _) = compose.readBothModes {
            MaterialTheme.typography.displaySmall.fontFamily to MaterialTheme.typography.bodyLarge.fontFamily
        }

        assertNotEquals(families.second, families.first)
    }

    @Test
    fun `every text slot is set in a bundled family`() {
        val (families, _) = compose.readBothModes {
            with(MaterialTheme.typography) {
                listOf(displaySmall, titleMedium, bodyLarge, labelMedium, headlineSmall).map { it.fontFamily }
            }
        }

        assertTrue(families.none { it == null || it == FontFamily.Default }, "$families")
    }
}
