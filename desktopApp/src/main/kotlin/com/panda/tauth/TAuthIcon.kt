package com.panda.tauth

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import com.panda.tauth.resources.Res
import com.panda.tauth.resources.tauth
import org.jetbrains.compose.resources.painterResource

// The mark is drawn once, in the drawable this reads, and §4.3's packaged icons are cut from the
// same file, so the tray, the title bar and the installer cannot disagree about it.
@Composable
internal fun tauthIcon(): Painter = ToFit(painterResource(Res.drawable.tauth))

// The drawable carries the size of its own viewBox, and a tray asking for a smaller square draws
// that many pixels of it rather than the whole. Claiming no size of its own is what makes the
// drawing scale to the square it is given instead of being cropped to the corner of one.
private class ToFit(private val mark: Painter) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        with(mark) { draw(size) }
    }
}
