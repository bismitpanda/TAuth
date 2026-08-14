package com.panda.tauth.totp

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.valueOrNull
import com.panda.tauth.vault.VaultError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

private fun decoded(input: String): ByteArray {
    val outcome = Base32.decode(input)
    assertIs<Outcome.Success<ByteArray>>(outcome)
    return outcome.value
}

private fun ascii(text: String): ByteArray = text.encodeToByteArray()

class Base32Test {
    @Test
    fun `decodes the empty string to no bytes`() {
        assertContentEquals(ByteArray(0), decoded(""))
    }

    @Test
    fun `decodes MY====== to f`() {
        assertContentEquals(ascii("f"), decoded("MY======"))
    }

    @Test
    fun `decodes MZXQ==== to fo`() {
        assertContentEquals(ascii("fo"), decoded("MZXQ===="))
    }

    @Test
    fun `decodes MZXW6=== to foo`() {
        assertContentEquals(ascii("foo"), decoded("MZXW6==="))
    }

    @Test
    fun `decodes MZXW6YQ= to foob`() {
        assertContentEquals(ascii("foob"), decoded("MZXW6YQ="))
    }

    @Test
    fun `decodes MZXW6YTB to fooba`() {
        assertContentEquals(ascii("fooba"), decoded("MZXW6YTB"))
    }

    @Test
    fun `decodes MZXW6YTBOI====== to foobar`() {
        assertContentEquals(ascii("foobar"), decoded("MZXW6YTBOI======"))
    }

    @Test
    fun `encodes the empty array to the empty string`() {
        assertEquals("", Base32.encode(ByteArray(0)))
    }

    @Test
    fun `encodes f without padding`() {
        assertEquals("MY", Base32.encode(ascii("f")))
    }

    @Test
    fun `encodes fo without padding`() {
        assertEquals("MZXQ", Base32.encode(ascii("fo")))
    }

    @Test
    fun `encodes foo without padding`() {
        assertEquals("MZXW6", Base32.encode(ascii("foo")))
    }

    @Test
    fun `encodes foob without padding`() {
        assertEquals("MZXW6YQ", Base32.encode(ascii("foob")))
    }

    @Test
    fun `encodes fooba without padding`() {
        assertEquals("MZXW6YTB", Base32.encode(ascii("fooba")))
    }

    @Test
    fun `encodes foobar without padding`() {
        assertEquals("MZXW6YTBOI", Base32.encode(ascii("foobar")))
    }

    @Test
    fun `decodes unpadded input`() {
        assertContentEquals(ascii("foobar"), decoded("MZXW6YTBOI"))
    }

    @Test
    fun `decodes lowercase input`() {
        assertContentEquals(ascii("foobar"), decoded("mzxw6ytboi"))
    }

    @Test
    fun `ignores embedded whitespace`() {
        assertContentEquals(ascii("foobar"), decoded(" MZXW 6YTB\tOI\n"))
    }

    @Test
    fun `carriage return is ignored`() {
        assertContentEquals(ascii("foobar"), decoded("MZXW6YTB\r\nOI"))
    }

    @Test
    fun `only the four conventional whitespace characters are skipped`() {
        // '=' is excluded: it is padding, not whitespace, and has rules of its own.
        val skipped = (0..0x7F).filter { it != '='.code }
            .filter { Base32.decode("MZXW6YTB" + it.toChar()) is Outcome.Success }
        assertContentEquals(listOf(0x09, 0x0A, 0x0D, 0x20), skipped)
    }

    @Test
    fun `rejects a character outside the alphabet`() {
        val error = Base32.decode("MZXW6YT1").errorOrNull
        assertIs<VaultError.InvalidSecret>(error)
    }

    @Test
    fun `rejects data following padding`() {
        val error = Base32.decode("MY======MY").errorOrNull
        assertIs<VaultError.InvalidSecret>(error)
    }

    @Test
    fun `rejects too few padding characters`() {
        // Two data symbols need six pad characters to reach the eight-character group. One means
        // characters were lost on the way, which is exactly what padding exists to reveal.
        assertIs<VaultError.InvalidSecret>(Base32.decode("MY=").errorOrNull)
    }

    @Test
    fun `rejects padding on a group that needs none`() {
        assertIs<VaultError.InvalidSecret>(Base32.decode("MZXW6YTB=").errorOrNull)
    }

    @Test
    fun `rejects too many padding characters`() {
        assertIs<VaultError.InvalidSecret>(Base32.decode("MY=======").errorOrNull)
    }

    @Test
    fun `accepts a group with no padding at all`() {
        // An otpauth:// secret is unpadded, so absent padding is not wrong padding.
        assertContentEquals("f".encodeToByteArray(), Base32.decode("MY").valueOrNull)
    }

    @Test
    fun `rejects a symbol count that cannot end an encoding`() {
        // One leftover symbol carries five bits, which is fewer than a byte.
        val error = Base32.decode("MZXW6YTBO").errorOrNull
        assertIs<VaultError.InvalidSecret>(error)
    }

    @Test
    fun `rejects a trailing group of three symbols`() {
        // Fifteen bits: one byte and seven left over, which no whole number of bytes produces.
        assertIs<VaultError.InvalidSecret>(Base32.decode("MZX").errorOrNull)
    }

    @Test
    fun `rejects a trailing group of six symbols`() {
        // Thirty bits: three bytes and six left over.
        assertIs<VaultError.InvalidSecret>(Base32.decode("MZXW6Y").errorOrNull)
    }

    @Test
    fun `an invalid character does not appear in the error detail`() {
        val error = Base32.decode("SECRET1")
        assertIs<Outcome.Failure<VaultError.InvalidSecret>>(error)
        assertFalse("SECRET1" in error.error.detail)
    }

    @Test
    fun `a latin small letter long s is rejected`() {
        // U+017F uppercases to ASCII 'S', so case-folding before the alphabet check would let it
        // through and contribute the bits of 'S'.
        assertIs<VaultError.InvalidSecret>(Base32.decode("MZXW6YTſ").errorOrNull)
    }

    @Test
    fun `a dotless small i is rejected`() {
        // U+0131 uppercases to ASCII 'I'.
        assertIs<VaultError.InvalidSecret>(Base32.decode("MZXW6YTı").errorOrNull)
    }

    @Test
    fun `a character folding onto the alphabet does not alias to a real symbol`() {
        assertNotEquals(decoded("MZXW6YTS").toList(), Base32.decode("MZXW6YTſ").valueOrNull?.toList())
    }

    @Test
    fun `no character outside the ASCII alphabet decodes`() {
        for (code in 0x80..0x2FFF) {
            val candidate = "MZXW6YT" + code.toChar()
            assertIs<VaultError.InvalidSecret>(Base32.decode(candidate).errorOrNull, "U+${code.toString(16)}")
        }
    }

    @Test
    fun `round-trips a 20-byte secret`() {
        val bytes = ByteArray(20) { it.toByte() }
        assertContentEquals(bytes, decoded(Base32.encode(bytes)))
    }
}
