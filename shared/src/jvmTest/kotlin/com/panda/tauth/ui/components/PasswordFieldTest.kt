package com.panda.tauth.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.panda.tauth.ui.theme.TauthTheme
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

private const val TYPED = "correct horse"
private const val MASK = '•'

class PasswordFieldTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = PasswordFieldState()

    @Test
    fun `typing into the field fills the holder`() {
        compose.setContent { TauthTheme { PasswordField(state) } }

        compose.onNode(hasSetTextAction()).performTextInput(TYPED)

        compose.runOnIdle { assertContentEquals(TYPED.toCharArray(), state.copyValue()) }
    }

    @Test
    fun `the hidden field shows one mask character per character typed`() {
        compose.setContent { TauthTheme { PasswordField(state) } }

        compose.onNode(hasSetTextAction()).performTextInput(TYPED)

        compose.onNodeWithText(MASK.toString().repeat(TYPED.length)).assertIsDisplayed()
    }

    @Test
    fun `the characters are absent from the hidden field`() {
        compose.setContent { TauthTheme { PasswordField(state) } }

        compose.onNode(hasSetTextAction()).performTextInput(TYPED)

        compose.onNodeWithText(TYPED).assertDoesNotExist()
    }

    @Test
    fun `the reveal toggle shows the characters`() {
        compose.setContent { TauthTheme { PasswordField(state) } }
        compose.onNode(hasSetTextAction()).performTextInput(TYPED)

        compose.onNodeWithContentDescription("Show").performClick()

        compose.onNodeWithText(TYPED).assertIsDisplayed()
    }

    @Test
    fun `a field put back on screen takes characters again`() {
        var isOnScreen by mutableStateOf(true)
        compose.setContent { TauthTheme { if (isOnScreen) PasswordField(state) } }
        compose.onNode(hasSetTextAction()).performTextInput(TYPED)

        compose.runOnIdle { isOnScreen = false }
        compose.runOnIdle { isOnScreen = true }
        compose.onNode(hasSetTextAction()).performTextInput("second")

        compose.runOnIdle { assertContentEquals("second".toCharArray(), state.copyValue()) }
    }

    @Test
    fun `leaving composition zeroes the array`() {
        state.replace(0, 0, TYPED)
        var isOnScreen by mutableStateOf(true)
        compose.setContent { TauthTheme { if (isOnScreen) PasswordField(state) } }
        val array = state.chars

        compose.runOnIdle { isOnScreen = false }
        compose.waitForIdle()

        assertTrue(array.all { it == Char.MIN_VALUE })
    }
}
