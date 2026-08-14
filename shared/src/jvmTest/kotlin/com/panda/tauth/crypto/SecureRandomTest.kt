package com.panda.tauth.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecureRandomTest {
    @Test
    fun `returns the requested number of bytes`() {
        assertEquals(AEAD_NONCE_BYTES, secureRandomBytes(AEAD_NONCE_BYTES).size)
    }

    @Test
    fun `two draws differ`() {
        assertFalse(secureRandomBytes(32).contentEquals(secureRandomBytes(32)))
    }

    @Test
    fun `a size of zero is rejected`() {
        assertFailsWith<IllegalArgumentException> { secureRandomBytes(0) }
    }
}
