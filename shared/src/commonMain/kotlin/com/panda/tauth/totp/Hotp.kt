package com.panda.tauth.totp

object Hotp {
    fun generate(
        secret: ByteArray,
        counter: ULong,
        algorithm: HashAlgorithm = HashAlgorithm.SHA1,
        digits: Int = OtpCore.DIGITS_DEFAULT,
    ): String = OtpCore.code(secret, counter, algorithm, digits)
}
