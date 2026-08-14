package com.panda.tauth.totp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnumParsingTest {
    @Test
    fun `an algorithm name is matched without regard to case`() {
        assertEquals(HashAlgorithm.SHA256, HashAlgorithm.parse("sha256"))
    }

    @Test
    fun `a type name is matched without regard to case`() {
        assertEquals(OtpType.HOTP, OtpType.parse("hOtP"))
    }

    @Test
    fun `a long s does not stand in for the S of an algorithm name`() {
        // U+017F LATIN SMALL LETTER LONG S. Unicode case folding maps it onto ASCII 'S', so a
        // folding comparison reads this as SHA-256 while the ABNF has no room for the character.
        assertNull(HashAlgorithm.parse("ſHA256"))
    }

    @Test
    fun `a name longer than the constant does not match it`() {
        assertNull(HashAlgorithm.parse("SHA2560"))
    }
}
