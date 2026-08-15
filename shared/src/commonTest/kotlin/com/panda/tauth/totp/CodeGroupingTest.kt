package com.panda.tauth.totp

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeGroupingTest {
    @Test
    fun `a six-digit code splits into two groups of three`() {
        assertEquals("755 224", groupedCode("755224"))
    }

    @Test
    fun `a seven-digit code takes the odd digit into the left group`() {
        assertEquals("4755 224", groupedCode("4755224"))
    }

    @Test
    fun `an eight-digit code splits into two groups of four`() {
        assertEquals("8475 5224", groupedCode("84755224"))
    }

    // Leading zeros are digits of the code and a group that drops one is a different code.
    @Test
    fun `a code padded with a leading zero keeps it`() {
        assertEquals("000 224", groupedCode("000224"))
    }

    @Test
    fun `an empty code is left alone`() {
        assertEquals("", groupedCode(""))
    }
}
