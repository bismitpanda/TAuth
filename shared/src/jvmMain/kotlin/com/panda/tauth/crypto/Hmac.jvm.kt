package com.panda.tauth.crypto

import com.panda.tauth.totp.HashAlgorithm
import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private fun jceName(algorithm: HashAlgorithm): String = when (algorithm) {
    HashAlgorithm.SHA1 -> "HmacSHA1"
    HashAlgorithm.SHA256 -> "HmacSHA256"
    HashAlgorithm.SHA512 -> "HmacSHA512"
}

actual fun hmac(algorithm: HashAlgorithm, key: ByteArray, message: ByteArray): ByteArray {
    require(key.isNotEmpty()) { "HMAC key must not be empty" }
    val name = jceName(algorithm)
    // The JDK mandates all three, so an absence is a broken runtime.
    return try {
        val mac = Mac.getInstance(name)
        mac.init(SecretKeySpec(key, name))
        mac.doFinal(message)
    } catch (e: GeneralSecurityException) {
        throw IllegalStateException("$name is unavailable in this JVM", e)
    }
}
