package com.panda.tauth.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.panda.tauth.ui.theme.TauthTheme
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertTrue

private const val CONTAINER_TAG = "choice-row-container"

private const val LABEL = "Lock after this long without input"

private val OPTIONS = listOf("Off", "1 minute", "5 minutes", "15 minutes", "30 minutes")

private val NARROW = 320.dp

private val WIDE = 900.dp

// requiredWidth, not width: the test root's constraint is unbounded, and a preferred width under it
// leaves the row free to lay its options out past the box.
class ChoiceRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every option is inside the width the row is given`() {
        show()

        val limit = container().left + NARROW
        OPTIONS.forEach { option ->
            val edge = optionRight(option)
            assertTrue(edge <= limit, "$option ends at $edge, past $limit")
        }
    }

    @Test
    fun `options that do not fit on one line move to the next`() {
        show()

        val tops = OPTIONS.map { optionTop(it) }.toSet()

        assertTrue(tops.size > 1, "every option sits on one line at $NARROW")
    }

    @Test
    fun `a width that fits them all keeps the options on one line`() {
        show(width = WIDE)

        val tops = OPTIONS.map { optionTop(it) }.toSet()

        assertTrue(tops.size == 1, "the options wrapped at $WIDE across $tops")
    }

    private fun container() = compose.onNodeWithTag(CONTAINER_TAG).getUnclippedBoundsInRoot()

    private fun optionRight(option: String): Dp = compose.onNodeWithText(option).getUnclippedBoundsInRoot().right

    private fun optionTop(option: String): Dp = compose.onNodeWithText(option).getUnclippedBoundsInRoot().top

    private fun show(width: Dp = NARROW) {
        compose.setContent {
            TauthTheme {
                Box(Modifier.requiredWidth(width).testTag(CONTAINER_TAG)) {
                    ChoiceRow(
                        label = LABEL,
                        options = OPTIONS,
                        selected = OPTIONS.first(),
                        optionLabel = { it },
                        onSelect = {},
                    )
                }
            }
        }
    }
}
