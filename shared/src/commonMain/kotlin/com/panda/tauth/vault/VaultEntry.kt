package com.panda.tauth.vault

import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpCore
import com.panda.tauth.totp.OtpType
import com.panda.tauth.totp.Totp
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// `period` belongs to totp and `counter` to hotp. A hotp entry with no counter would generate codes
// from position zero, which the server has long since passed.
@Serializable
data class VaultEntry(
    val id: String,
    val type: OtpType,
    val accountName: String,
    val secret: String,
    val createdAt: Instant,
    val issuer: String? = null,
    val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    val digits: Int = OtpCore.DIGITS_DEFAULT,
    val period: Int? = null,
    val counter: ULong? = null,
    val orderIndex: Int = 0,
) {
    init {
        validate()?.let { throw IllegalArgumentException(it) }
    }

    override fun toString(): String =
        "VaultEntry(id=$id, type=$type, issuer=$issuer, accountName=$accountName, algorithm=$algorithm, " +
            "digits=$digits, period=$period, counter=$counter, orderIndex=$orderIndex, secret=<redacted>)"

    companion object {
        // RFC 9562 §5.7: v7 sorts by creation time.
        @OptIn(ExperimentalUuidApi::class)
        fun newId(): String = Uuid.generateV7().toString()
    }
}

internal fun VaultEntry.validate(): String? = validateFields() ?: validateMovingFactor()

private fun VaultEntry.validateFields(): String? = when {
    id.isEmpty() -> "entry id must not be empty"

    accountName.isEmpty() -> "account name must not be empty"

    secret.isEmpty() -> "secret must not be empty"

    orderIndex < 0 -> "orderIndex must not be negative"

    digits !in OtpCore.DIGITS_MIN..OtpCore.DIGITS_MAX ->
        "digits must be ${OtpCore.DIGITS_MIN}..${OtpCore.DIGITS_MAX}"

    else -> null
}

private fun VaultEntry.validateMovingFactor(): String? = when (type) {
    OtpType.TOTP -> when {
        period == null -> "a totp entry requires a period"
        period < Totp.PERIOD_MIN -> "period must be at least ${Totp.PERIOD_MIN}"
        counter != null -> "a totp entry carries no counter"
        else -> null
    }

    OtpType.HOTP -> when {
        counter == null -> "a hotp entry requires a counter"
        period != null -> "a hotp entry carries no period"
        else -> null
    }
}
