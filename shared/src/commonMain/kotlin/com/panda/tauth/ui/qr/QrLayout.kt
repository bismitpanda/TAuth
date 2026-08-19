package com.panda.tauth.ui.qr

import kotlin.math.floor

// Where a symbol sits on a square canvas, in pixels.
data class QrLayout(val modulePx: Float, val originPx: Float)

// The module size is a whole number of pixels and the remainder becomes quiet zone, so no module
// straddles a fractional boundary: those edges blur under scaling and scanners refuse them.
fun qrLayout(canvasPx: Float, moduleCount: Int): QrLayout {
    val module = floor(canvasPx / moduleCount)
    return QrLayout(module, (canvasPx - module * moduleCount) / 2)
}
