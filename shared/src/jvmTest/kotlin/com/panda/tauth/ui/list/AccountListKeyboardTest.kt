package com.panda.tauth.ui.list

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.settings.SortOrder
import com.panda.tauth.totp.TotpCode
import com.panda.tauth.ui.ClipboardCopy
import com.panda.tauth.ui.CopyResult
import com.panda.tauth.ui.hotpRow
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.ui.totpRow
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val TOTP_CODE = "287082"

private val FIRST = hotpRow(orderIndex = 0)
private val SECOND = totpRow(orderIndex = 1)

class AccountListKeyboardTest {
    @get:Rule
    val compose = createComposeRule()

    private val copied = mutableListOf<String>()
    private var moved: Pair<String, Int>? = null

    @Test
    fun `the search field takes focus as the screen opens`() {
        show()

        compose.onNodeWithTag(SEARCH_TAG).assertIsFocused()
    }

    @Test
    fun `the first account is selected before any key is pressed`() {
        show()

        compose.onNodeWithTag(accountRowTag(FIRST.id)).assertIsSelected()
    }

    @Test
    fun `the down arrow moves the selection to the next account`() {
        show()

        press(Key.DirectionDown)

        compose.onNodeWithTag(accountRowTag(SECOND.id)).assertIsSelected()
    }

    @Test
    fun `the up arrow moves the selection back`() {
        show()

        press(Key.DirectionDown)
        press(Key.DirectionUp)

        compose.onNodeWithTag(accountRowTag(FIRST.id)).assertIsSelected()
    }

    @Test
    fun `the down arrow stops at the last account`() {
        show()

        repeat(3) { press(Key.DirectionDown) }

        compose.onNodeWithTag(accountRowTag(SECOND.id)).assertIsSelected()
    }

    @Test
    fun `enter copies the selected account's code`() {
        show()

        press(Key.DirectionDown)
        press(Key.Enter)

        compose.runOnIdle { assertEquals(listOf(TOTP_CODE), copied) }
    }

    @Test
    fun `escape clears the query`() {
        show()
        compose.onNodeWithTag(SEARCH_TAG).performTextInput("git")

        press(Key.Escape)

        compose.onNodeWithTag(SEARCH_TAG).assertTextContains(SEARCH_PLACEHOLDER)
    }

    @Test
    fun `alt and the down arrow move the selected account`() {
        show()

        press(Key.DirectionDown, isAlt = true)

        compose.runOnIdle { assertEquals(FIRST.id to 1, moved) }
    }

    @Test
    fun `alt and an arrow move nothing while a query is filtering the list`() {
        show()
        compose.onNodeWithTag(SEARCH_TAG).performTextInput("git")

        press(Key.DirectionDown, isAlt = true)

        compose.runOnIdle { assertNull(moved) }
    }

    @Test
    fun `alt and an arrow move nothing in an ordering that is not the stored one`() {
        show(sortOrder = SortOrder.ISSUER)

        press(Key.DirectionDown, isAlt = true)

        compose.runOnIdle { assertNull(moved) }
    }

    private fun press(key: Key, isAlt: Boolean = false) {
        compose.onNodeWithTag(SEARCH_TAG).performKeyInput {
            if (isAlt) withKeyDown(Key.AltLeft) { pressKey(key) } else pressKey(key)
        }
    }

    private fun show(entries: List<UnlockedEntry> = listOf(FIRST, SECOND), sortOrder: SortOrder = SortOrder.MANUAL) {
        compose.setContent {
            TauthTheme {
                AccountListScreen(
                    entries = entries,
                    codes = mapOf(SECOND.id to TotpCode(TOTP_CODE, 30, 30)),
                    modifier = Modifier.fillMaxSize(),
                    sortOrder = sortOrder,
                    clipboard = ClipboardCopy { text, _ ->
                        copied += text
                        CopyResult.COPIED
                    },
                    onMove = { id, index -> moved = id to index },
                )
            }
        }
    }
}
