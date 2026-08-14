package com.panda.tauth.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

// Published CRC-32/ISO-HDLC values. A structural test ("a flipped bit changes the checksum") would
// pass for a plain byte sum, so every case here pins an exact value instead.
class Crc32Test {
    @Test
    fun `the empty input has a checksum of zero`() {
        assertEquals(0u, crc32(ByteArray(0)))
    }

    @Test
    fun `the standard check value holds`() {
        assertEquals(0xCBF43926u, crc32("123456789".encodeToByteArray()))
    }

    @Test
    fun `a single byte matches its published value`() {
        assertEquals(0xE8B7BE43u, crc32("a".encodeToByteArray()))
    }

    @Test
    fun `abc matches its published value`() {
        assertEquals(0x352441C2u, crc32("abc".encodeToByteArray()))
    }
}
