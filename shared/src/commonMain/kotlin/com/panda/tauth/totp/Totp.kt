package com.panda.tauth.totp

object Totp {
    // No upper bound: RFC 6238 §4.1 makes X a system parameter and sets no maximum, so a provider
    // issuing hourly codes must still import. One is the floor because zero divides by zero.
    const val PERIOD_MIN = 1
    const val PERIOD_DEFAULT = 30

    // RFC 6238 §4.1: T0 is the Unix epoch.
    private const val T0_SECONDS = 0L

    // RFC 6238 §4.2, errata 8672: T is 64-bit. A 32-bit T fails in 2038.
    fun counterAt(epochSeconds: Long, period: Int): Long {
        require(period >= PERIOD_MIN) { "period must be at least $PERIOD_MIN" }
        return (epochSeconds - T0_SECONDS).floorDiv(period.toLong())
    }

    // A full period on a boundary, one second at the last second before the next. Derived from the
    // clock rather than counted down, so a late tick reports the truth instead of a stale remainder.
    fun secondsRemaining(epochSeconds: Long, period: Int): Int {
        require(period >= PERIOD_MIN) { "period must be at least $PERIOD_MIN" }
        return (period - (epochSeconds - T0_SECONDS).mod(period.toLong())).toInt()
    }

    fun generate(
        secret: ByteArray,
        epochSeconds: Long,
        algorithm: HashAlgorithm = HashAlgorithm.SHA1,
        digits: Int = OtpCore.DIGITS_DEFAULT,
        period: Int = PERIOD_DEFAULT,
    ): String = OtpCore.code(secret, counterAt(epochSeconds, period).toULong(), algorithm, digits)
}
