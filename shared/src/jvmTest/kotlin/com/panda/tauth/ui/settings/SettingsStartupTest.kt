package com.panda.tauth.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.panda.tauth.settings.Preferences
import com.panda.tauth.settings.SecurityPolicy
import com.panda.tauth.settings.SortOrder
import com.panda.tauth.settings.Theme
import com.panda.tauth.ui.theme.TauthTheme
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

private val STORED = Preferences(
    theme = Theme.DARK,
    sortOrder = SortOrder.RECENTLY_ADDED,
    startMinimized = true,
    minimizeToTray = false,
    startAtLogin = true,
)

// Start at login holds start-minimized on, so what that control does on its own is read against a
// document with the login setting off.
private val NOT_AT_LOGIN = STORED.copy(startAtLogin = false)

class SettingsStartupTest {
    @get:Rule
    val compose = createComposeRule()

    private var chosenMinimizeToTray: Boolean? = null
    private var chosenStartMinimized: Boolean? = null
    private var chosenStartAtLogin: Boolean? = null

    @Test
    fun `the tray preference it opens with is the one stored`() {
        show()

        compose.onNodeWithTag(MINIMIZE_TO_TRAY_TAG).assertIsOff()
    }

    @Test
    fun `a switched tray preference is handed over`() {
        show()

        toggle(MINIMIZE_TO_TRAY_TAG)

        compose.runOnIdle { assertEquals(true, chosenMinimizeToTray) }
    }

    @Test
    fun `a desktop with no tray cannot switch the tray preference`() {
        show(shell = shellSettings(canConfigureTray = false))

        compose.onNodeWithTag(MINIMIZE_TO_TRAY_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a desktop with no tray says why the tray settings are refused`() {
        show(shell = shellSettings(canConfigureTray = false))

        compose.onNodeWithText(NO_TRAY_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a desktop with a tray offers the tray preference`() {
        show(shell = shellSettings(canConfigureTray = true))

        compose.onNodeWithTag(MINIMIZE_TO_TRAY_TAG).assertIsEnabled()
    }

    @Test
    fun `a desktop with a tray says nothing about a missing one`() {
        show(shell = shellSettings(canConfigureTray = true))

        compose.onNodeWithText(NO_TRAY_NOTE).assertDoesNotExist()
    }

    @Test
    fun `the start preference it opens with is the one stored`() {
        show()

        compose.onNodeWithTag(START_MINIMIZED_TAG).assertIsOn()
    }

    @Test
    fun `a switched start preference is handed over`() {
        show(preferences = NOT_AT_LOGIN)

        toggle(START_MINIMIZED_TAG)

        compose.runOnIdle { assertEquals(false, chosenStartMinimized) }
    }

    @Test
    fun `a desktop with no tray cannot switch the start preference`() {
        show(preferences = NOT_AT_LOGIN, shell = shellSettings(canConfigureTray = false))

        compose.onNodeWithTag(START_MINIMIZED_TAG).assertIsNotEnabled()
    }

    @Test
    fun `the login preference it opens with is the one stored`() {
        show()

        compose.onNodeWithTag(START_AT_LOGIN_TAG).assertIsOn()
    }

    @Test
    fun `a switched login preference is handed over`() {
        show()

        toggle(START_AT_LOGIN_TAG)

        compose.runOnIdle { assertEquals(false, chosenStartAtLogin) }
    }

    @Test
    fun `starting at login holds the window out of the way`() {
        show()

        compose.onNodeWithTag(START_MINIMIZED_TAG).assertIsNotEnabled()
    }

    @Test
    fun `not starting at login leaves that choice free`() {
        show(preferences = NOT_AT_LOGIN)

        compose.onNodeWithTag(START_MINIMIZED_TAG).assertIsEnabled()
    }

    @Test
    fun `a build with no installed launcher cannot switch the login preference`() {
        show(shell = shellSettings(canStartAtLogin = false))

        compose.onNodeWithTag(START_AT_LOGIN_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a build with no installed launcher says why the login preference is refused`() {
        show(shell = shellSettings(canStartAtLogin = false))

        compose.onNodeWithText(NO_LAUNCHER_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an installed build offers the login preference`() {
        show(shell = shellSettings(canStartAtLogin = true))

        compose.onNodeWithTag(START_AT_LOGIN_TAG).assertIsEnabled()
    }

    @Test
    fun `an installed build says nothing about a missing launcher`() {
        show(shell = shellSettings(canStartAtLogin = true))

        compose.onNodeWithText(NO_LAUNCHER_NOTE).assertDoesNotExist()
    }

    private fun toggle(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun shellSettings(canConfigureTray: Boolean = true, canStartAtLogin: Boolean = true) =
        ShellSettings(canConfigureTray = canConfigureTray, canStartAtLogin = canStartAtLogin)

    private fun show(preferences: Preferences = STORED, shell: ShellSettings = shellSettings()) {
        compose.setContent {
            TauthTheme {
                SettingsScreen(
                    policy = SecurityPolicy(),
                    preferences = preferences,
                    shell = shell,
                    onMinimizeToTrayChange = { chosenMinimizeToTray = it },
                    onStartMinimizedChange = { chosenStartMinimized = it },
                    onStartAtLoginChange = { chosenStartAtLogin = it },
                )
            }
        }
    }
}
