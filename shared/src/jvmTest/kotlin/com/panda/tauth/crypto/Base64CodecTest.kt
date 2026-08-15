package com.panda.tauth.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun ascii(text: String) = text.encodeToByteArray()

// RFC 4648 §10.
class Base64CodecTest {
    @Test
    fun `encodes the empty array`() {
        assertEquals("", base64Encode(ByteArray(0)))
    }

    @Test
    fun `encodes f`() {
        assertEquals("Zg==", base64Encode(ascii("f")))
    }

    @Test
    fun `encodes fo`() {
        assertEquals("Zm8=", base64Encode(ascii("fo")))
    }

    @Test
    fun `encodes foo`() {
        assertEquals("Zm9v", base64Encode(ascii("foo")))
    }

    @Test
    fun `encodes foob`() {
        assertEquals("Zm9vYg==", base64Encode(ascii("foob")))
    }

    @Test
    fun `encodes fooba`() {
        assertEquals("Zm9vYmE=", base64Encode(ascii("fooba")))
    }

    @Test
    fun `encodes foobar`() {
        assertEquals("Zm9vYmFy", base64Encode(ascii("foobar")))
    }

    @Test
    fun `decodes foobar`() {
        assertContentEquals(ascii("foobar"), base64Decode("Zm9vYmFy"))
    }

    @Test
    fun `decodes padded input`() {
        assertContentEquals(ascii("foob"), base64Decode("Zm9vYg=="))
    }

    @Test
    fun `rejects a character outside the alphabet`() {
        assertNull(base64Decode("Zm9v!mFy"))
    }

    @Test
    fun `encodes the two characters that separate the alphabets`() {
        // RFC 4648 §4 ends the alphabet with + and /, where §5's URL-safe variant puts - and _.
        // These three bytes are the shortest input that produces both.
        assertEquals("++/+", base64Encode(byteArrayOf(0xFB.toByte(), 0xEF.toByte(), 0xFE.toByte())))
    }

    @Test
    fun `decodes the two characters that separate the alphabets`() {
        assertContentEquals(
            byteArrayOf(0xFB.toByte(), 0xEF.toByte(), 0xFE.toByte()),
            base64Decode("++/+"),
        )
    }

    @Test
    fun `rejects the URL-safe alphabet`() {
        assertNull(base64Decode("--_-"))
    }

    @Test
    fun `round-trips every byte value`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertContentEquals(bytes, base64Decode(base64Encode(bytes)))
    }
}
