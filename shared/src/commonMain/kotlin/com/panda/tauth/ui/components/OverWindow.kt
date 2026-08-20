package com.panda.tauth.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.ui.theme.isCompact

const val OVER_WINDOW_DIALOG_TAG = "over-window-dialog"

@Composable
fun OverWindow(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (isCompact(maxWidth)) {
            content(Modifier.fillMaxSize())
        } else {
            val inset = LocalSpacing.current.large
            val tallest = maxHeight - inset * 2
            Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(
                    modifier = Modifier
                        .padding(inset)
                        .widthIn(max = DIALOG_MAX_WIDTH)
                        .heightIn(max = tallest)
                        .testTag(OVER_WINDOW_DIALOG_TAG),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = DIALOG_ELEVATION,
                ) {
                    content(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private val DIALOG_MAX_WIDTH = 520.dp
private val DIALOG_ELEVATION = 6.dp
