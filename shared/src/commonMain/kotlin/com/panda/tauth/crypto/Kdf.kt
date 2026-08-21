package com.panda.tauth.crypto

// Fixed by the format version and absent from the file: a cost read from a plaintext header would
// be an allocation of the attacker's choosing.
const val ARGON2_VERSION = 19
const val ARGON2_PARALLELISM = 1
const val ARGON2_MEMORY_KIB = 65536
const val ARGON2_ITERATIONS = 3
const val ARGON2_SALT_BYTES = 16

// Argon2's own floor. BouncyCastle reports less with an IllegalStateException.
const val ARGON2_MIN_OUTPUT_BYTES = 4

// The caller zeroes the password it passes.
expect fun argon2id(password: CharArray, salt: ByteArray, outputBytes: Int): ByteArray
