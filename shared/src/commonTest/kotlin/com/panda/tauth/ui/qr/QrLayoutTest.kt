package com.panda.tauth.ui.qr

import kotlin.test.Test
import kotlin.test.assertEquals

// A version 1 symbol under the quiet zone the encoder carries.
private const val MODULES = 25

class QrLayoutTest {
    @Test
    fun `a module is a whole number of pixels`() {
        assertEquals(11f, qrLayout(canvasPx = 288f, moduleCount = MODULES).modulePx)
    }

    @Test
    fun `what the modules do not fill is split between the two sides`() {
        assertEquals(6.5f, qrLayout(canvasPx = 288f, moduleCount = MODULES).originPx)
    }

    @Test
    fun `a canvas the modules fill exactly starts at its own edge`() {
        assertEquals(0f, qrLayout(canvasPx = 275f, moduleCount = MODULES).originPx)
    }

    @Test
    fun `a canvas the modules fill exactly divides into whole modules`() {
        assertEquals(11f, qrLayout(canvasPx = 275f, moduleCount = MODULES).modulePx)
    }

    // Below one pixel a module the symbol has no whole-module rendering, and half a module drawn is
    // worse than none: it scans as a different payload rather than as nothing.
    @Test
    fun `a canvas smaller than the symbol lays down no module`() {
        assertEquals(0f, qrLayout(canvasPx = 20f, moduleCount = MODULES).modulePx)
    }
}
