package com.panda.tauth.crypto

import java.security.SecureRandom

private val RANDOM = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray {
    require(size > 0) { "size must be positive" }
    val bytes = ByteArray(size)
    RANDOM.nextBytes(bytes)
    return bytes
}
