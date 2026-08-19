package com.panda.tauth

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import com.panda.tauth.resources.Res
import com.panda.tauth.resources.tauth
import org.jetbrains.compose.resources.painterResource

// §4.1's packaged icons are cut from the drawable this reads, so the title bar and the installer
// cannot disagree about the mark.
@Composable
internal fun tauthIcon(): Painter = ToFit(painterResource(Res.drawable.tauth))

// The drawable carries its viewBox as an intrinsic size, and a window icon slot smaller than that
// draws a corner of it. Claiming no size is what makes it scale to the square it is given.
private class ToFit(private val mark: Painter) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        with(mark) { draw(size) }
    }
}
