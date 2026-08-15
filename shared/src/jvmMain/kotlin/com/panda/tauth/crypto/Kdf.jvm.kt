package com.panda.tauth.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

actual fun argon2id(password: CharArray, salt: ByteArray, outputBytes: Int): ByteArray {
    require(salt.isNotEmpty()) { "salt must not be empty" }
    require(outputBytes >= ARGON2_MIN_OUTPUT_BYTES) { "outputBytes must be at least $ARGON2_MIN_OUTPUT_BYTES" }
    val specification = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(ARGON2_VERSION)
        .withMemoryAsKB(ARGON2_MEMORY_KIB)
        .withIterations(ARGON2_ITERATIONS)
        .withParallelism(ARGON2_PARALLELISM)
        .withSalt(salt)
        .build()
    val generator = Argon2BytesGenerator()
    generator.init(specification)
    val derived = ByteArray(outputBytes)
    // Argon2BytesGenerator 1.85 leaves the UTF-8 password, the H0 prehash seeds and its scratch
    // block on the heap. All three are its own locals, unreachable from here.
    generator.generateBytes(password, derived)
    return derived
}
