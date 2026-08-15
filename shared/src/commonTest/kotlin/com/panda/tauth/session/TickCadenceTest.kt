package com.panda.tauth.session

import kotlin.test.Test
import kotlin.test.assertEquals

class TickCadenceTest {
    @Test
    fun `a wait from part way through a second ends on the next one`() {
        assertEquals(750, millisToNextSecond(1_000_000_250))
    }

    @Test
    fun `a wait from an exact second lasts a whole one`() {
        assertEquals(1000, millisToNextSecond(1_000_000_000))
    }

    @Test
    fun `a wait from the last millisecond of a second ends on the next one`() {
        assertEquals(1, millisToNextSecond(1_000_000_999))
    }

    @Test
    fun `a wait from before the epoch ends on a second boundary`() {
        // -1500 ms is half a second into the second that ends at -1000 ms.
        assertEquals(500, millisToNextSecond(-1500))
    }
}
