package com.panda.tauth.totp

import com.panda.tauth.crypto.hmac

// The HMAC of RFC 4226 §5.2 and the dynamic truncation of §5.3, over a moving factor. HOTP and TOTP
// differ only in where that factor comes from.
internal object OtpCore {
    const val DIGITS_MIN = 6
    const val DIGITS_MAX = 8
    const val DIGITS_DEFAULT = 6

    private const val MOVING_FACTOR_BYTES = 8
    private const val TRUNCATION_BYTES = 4
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
    private const val OFFSET_MASK = 0x0F
    private const val SIGN_MASK = 0x7F

    private val MODULUS = intArrayOf(1_000_000, 10_000_000, 100_000_000)

    fun code(secret: ByteArray, movingFactor: ULong, algorithm: HashAlgorithm, digits: Int): String {
        require(digits in DIGITS_MIN..DIGITS_MAX) { "digits must be $DIGITS_MIN..$DIGITS_MAX" }
        val mac = hmac(algorithm, secret, movingFactorBytes(movingFactor))
        val offset = mac[mac.size - 1].toInt() and OFFSET_MASK
        var binary = mac[offset].toInt() and SIGN_MASK
        for (i in 1 until TRUNCATION_BYTES) {
            binary = (binary shl BITS_PER_BYTE) or (mac[offset + i].toInt() and BYTE_MASK)
        }
        return (binary % MODULUS[digits - DIGITS_MIN]).toString().padStart(digits, '0')
    }

    // RFC 4226 §5.1: the moving factor is an unsigned 64-bit big-endian value.
    fun movingFactorBytes(movingFactor: ULong): ByteArray {
        val bytes = ByteArray(MOVING_FACTOR_BYTES)
        var remaining = movingFactor
        for (i in MOVING_FACTOR_BYTES - 1 downTo 0) {
            bytes[i] = (remaining.toInt() and BYTE_MASK).toByte()
            remaining = remaining shr BITS_PER_BYTE
        }
        return bytes
    }
}
