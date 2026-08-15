package com.panda.tauth.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import kotlin.test.Test

// Covers the harness rather than a screen: it fails when jvmTest loses the ability to
// compose, render and drive a node off-screen, which every screen test rests on.
class ComposeHarnessTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a composed node is reachable by its text`() {
        compose.setContent { Counter() }

        compose.onNodeWithText("count 0").assertIsDisplayed()
    }

    @Test
    fun `a click recomposes the node it changed`() {
        compose.setContent { Counter() }

        compose.onNodeWithText("count 0").performClick()

        compose.onNodeWithText("count 1").assertIsDisplayed()
    }
}

@Composable
private fun Counter() {
    var count by remember { mutableStateOf(0) }
    Text("count $count", modifier = Modifier.clickable { count++ })
}
