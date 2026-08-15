package com.panda.tauth.crypto

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

// The generator standing at the end of the call path, read by name off the file class holding it.
// This is the only way to see the one the process runs on.
private fun processRandomnessSource(): Any {
    val field = Class.forName("com.panda.tauth.crypto.SecureRandom_jvmKt").getDeclaredField("randomnessSource")
    field.isAccessible = true
    return checkNotNull(field.get(null)) { "the jvm actual holds no randomness source" }
}

// A byte pattern the process generator would repeat over sixteen bytes once in 2^128 draws, so bytes
// carrying it came through this instance and from nowhere else.
private class FixedBytes(private val fill: Byte) : SecureRandom() {
    override fun nextBytes(bytes: ByteArray) = bytes.fill(fill)
}

private class CountingBytes : SecureRandom() {
    var draws = 0
        private set

    override fun nextBytes(bytes: ByteArray) {
        draws++
        super.nextBytes(bytes)
    }
}

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

    @Test
    fun `the bytes returned are the ones the randomness source wrote`() {
        // Substituting the draw for any other generator leaves the returned bytes unrelated to the
        // source standing here, whether the substitute is imported, fully qualified or inlined.
        val drawn = withRandomnessSource(FixedBytes(0x5A)) { secureRandomBytes(16) }
        assertContentEquals(ByteArray(16) { 0x5A }, drawn)
    }

    @Test
    fun `a draw consumes the randomness source`() {
        val source = CountingBytes()
        withRandomnessSource(source) { secureRandomBytes(32) }
        assertEquals(1, source.draws)
    }

    @Test
    fun `the draws come from a java security SecureRandom`() {
        // A seeded java.util.Random satisfies every other case here while handing every vault on
        // earth the same key, salt and nonce sequence. This holds the type if the declaration widens.
        assertIs<SecureRandom>(processRandomnessSource())
    }
}
