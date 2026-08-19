package com.panda.tauth.ui.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// One dark module, at column 2 of row 0, so a symbol read with its axes swapped answers differently
// from one read as it was written.
private val CORNER = BooleanArray(9).also { it[2] = true }

class QrSymbolTest {
    @Test
    fun `a module is read by its column and its row`() {
        assertTrue(QrSymbol(3, CORNER).isDark(x = 2, y = 0))
    }

    @Test
    fun `the module at the transposed position is not the same module`() {
        assertFalse(QrSymbol(3, CORNER).isDark(x = 0, y = 2))
    }

    // The grid is what a drawing reads on every frame, and a caller keeping its array could move a
    // module under it.
    @Test
    fun `a symbol does not follow the array it was built from`() {
        val modules = CORNER.copyOf()
        val symbol = QrSymbol(3, modules)

        modules[2] = false

        assertTrue(symbol.isDark(x = 2, y = 0))
    }

    @Test
    fun `a grid that is not square is refused`() {
        assertFailsWith<IllegalArgumentException> { QrSymbol(3, BooleanArray(6)) }
    }

    @Test
    fun `a symbol carries the width it was built at`() {
        assertEquals(3, QrSymbol(3, CORNER).width)
    }
}
