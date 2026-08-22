package com.panda.tauth.ui.list

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.panda.tauth.ui.theme.TauthIcons

internal const val DRAG_SLOT_TAG = "drag-slot"

// Drawn rather than bordered: Compose's border modifier has no dash.
private val OUTLINE_WIDTH = 2.dp
private val DASH_LENGTH = 6.dp
private val CORNER_RADIUS = 12.dp

@Composable
internal fun DragSlot(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .testTag(DRAG_SLOT_TAG)
            .drawBehind {
                val dash = DASH_LENGTH.toPx()
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(CORNER_RADIUS.toPx()),
                    style = Stroke(
                        width = OUTLINE_WIDTH.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(TauthIcons.reorder, contentDescription = null, tint = color)
    }
}
