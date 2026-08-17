package com.panda.tauth

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter

// The tray and the title bar are the desktop's own surfaces, which TauthTheme does not reach, and
// AWT draws the image as given, so the glyph carries the background it has to read against.
private val BADGE = Color(0xFF2F5DA8)
private val GLYPH = Color.White

// The macOS menu bar draws a tray icon at 22 logical points. Every shape below is a fraction of the
// square it is given, so the same drawing serves a taskbar asking for more.
private const val EXTENT = 22f

private const val SHACKLE_RADIUS = 0.13f
private const val SHACKLE_CENTRE_X = 0.5f
private const val SHACKLE_CENTRE_Y = 0.44f
private const val SHACKLE_WIDTH = 0.055f
private const val SHACKLE_START_DEGREES = 180f
private const val SHACKLE_SWEEP_DEGREES = 180f

private const val BODY_LEFT = 0.30f
private const val BODY_TOP = 0.44f
private const val BODY_WIDTH = 0.40f
private const val BODY_HEIGHT = 0.30f
private const val BODY_CORNER = 0.05f

internal object TAuthIcon : Painter() {

    override val intrinsicSize: Size = Size(EXTENT, EXTENT)

    override fun DrawScope.onDraw() {
        val extent = size.minDimension
        val origin = Offset(center.x - extent / 2f, center.y - extent / 2f)
        drawCircle(color = BADGE, radius = extent / 2f, center = center)
        drawArc(
            color = GLYPH,
            startAngle = SHACKLE_START_DEGREES,
            sweepAngle = SHACKLE_SWEEP_DEGREES,
            useCenter = false,
            topLeft = origin + Offset(
                (SHACKLE_CENTRE_X - SHACKLE_RADIUS) * extent,
                (SHACKLE_CENTRE_Y - SHACKLE_RADIUS) * extent,
            ),
            size = Size(SHACKLE_RADIUS * 2f * extent, SHACKLE_RADIUS * 2f * extent),
            style = Stroke(width = SHACKLE_WIDTH * extent, cap = StrokeCap.Round),
        )
        drawRoundRect(
            color = GLYPH,
            topLeft = origin + Offset(BODY_LEFT * extent, BODY_TOP * extent),
            size = Size(BODY_WIDTH * extent, BODY_HEIGHT * extent),
            cornerRadius = CornerRadius(BODY_CORNER * extent),
        )
    }
}
