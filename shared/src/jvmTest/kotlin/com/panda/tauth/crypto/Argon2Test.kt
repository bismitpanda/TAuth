package com.panda.tauth.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import kotlin.test.Test
import kotlin.test.assertEquals

private const val OUTPUT_BYTES = 32

private fun bouncyCastle(
    password: ByteArray,
    salt: ByteArray,
    memoryKib: Int,
    iterations: Int,
    parallelism: Int,
    secret: ByteArray? = null,
    additional: ByteArray? = null,
): String {
    val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(ARGON2_VERSION)
        .withMemoryAsKB(memoryKib)
        .withIterations(iterations)
        .withParallelism(parallelism)
        .withSalt(salt)
    secret?.let(builder::withSecret)
    additional?.let(builder::withAdditional)
    val generator = Argon2BytesGenerator()
    generator.init(builder.build())
    val derived = ByteArray(OUTPUT_BYTES)
    generator.generateBytes(password, derived)
    return derived.toHexString()
}

class Argon2Test {
    @Test
    fun `BouncyCastle matches the phc reference vector`() {
        assertEquals(
            "09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7",
            bouncyCastle("password".encodeToByteArray(), "somesalt".encodeToByteArray(), 65536, 2, 1),
        )
    }

    @Test
    fun `BouncyCastle matches the RFC 9106 section 5_3 vector`() {
        // The RFC's vector carries a secret and associated data, which the vault has no use for.
        assertEquals(
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
            bouncyCastle(
                password = ByteArray(32) { 0x01 },
                salt = ByteArray(16) { 0x02 },
                memoryKib = 32,
                iterations = 3,
                parallelism = 4,
                secret = ByteArray(8) { 0x03 },
                additional = ByteArray(12) { 0x04 },
            ),
        )
    }

    @Test
    fun `argon2id derives at the cost the format fixes`() {
        // The reference side names the cost in literals: the cost is not stored in the file, and a
        // test that fed the constants to both sides would agree with itself whatever they became.
        val salt = ByteArray(16) { 0x05 }
        assertEquals(
            bouncyCastle(
                password = "password".encodeToByteArray(),
                salt = salt,
                memoryKib = 65536,
                iterations = 3,
                parallelism = 1,
            ),
            argon2id("password".toCharArray(), salt, OUTPUT_BYTES).toHexString(),
        )
    }

    // One constant per test, so a change names itself rather than failing a derivation somewhere.

    @Test
    fun `the format fixes 64 MiB of memory`() {
        assertEquals(65536, ARGON2_MEMORY_KIB)
    }

    @Test
    fun `the format fixes three iterations`() {
        assertEquals(3, ARGON2_ITERATIONS)
    }

    @Test
    fun `the format fixes one lane`() {
        assertEquals(1, ARGON2_PARALLELISM)
    }

    @Test
    fun `the format fixes Argon2 version 0x13`() {
        assertEquals(0x13, ARGON2_VERSION)
    }

    @Test
    fun `the format fixes a 16-byte salt`() {
        assertEquals(16, ARGON2_SALT_BYTES)
    }
}
