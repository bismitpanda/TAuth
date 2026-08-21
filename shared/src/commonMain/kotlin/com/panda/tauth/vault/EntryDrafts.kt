package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.totp.Base32
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.OtpCore
import com.panda.tauth.totp.OtpType
import com.panda.tauth.totp.Totp
import com.panda.tauth.totp.toAsciiIntOrNull
import com.panda.tauth.totp.toAsciiULongOrNull

// Numbers are text because a field passes through the empty string and half a number on the way to a
// whole one. The secret is base32 text; the key bytes it stands for are decoded and zeroed elsewhere.
data class EntryDraft(
    val type: OtpType = OtpType.TOTP,
    val issuer: String = "",
    val accountName: String = "",
    val secret: String = "",
    val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    val digits: String = OtpCore.DIGITS_DEFAULT.toString(),
    val period: String = Totp.PERIOD_DEFAULT.toString(),
    val counter: String = "0",
) {
    override fun toString(): String =
        "EntryDraft(type=$type, issuer=$issuer, accountName=$accountName, algorithm=$algorithm, " +
            "digits=$digits, period=$period, counter=$counter, secret=<redacted>)"
}

// The secret and the type are absent for the same reason they are absent from EntryEdit: neither can
// be reached by an edit at all.
data class EntryEditDraft(
    val issuer: String = "",
    val accountName: String = "",
    val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    val digits: String = OtpCore.DIGITS_DEFAULT.toString(),
    val period: String = Totp.PERIOD_DEFAULT.toString(),
    val counter: String = "0",
)

// The secret on its own. The URI model refuses the account name before it reaches the secret, so a
// form checked only through that would answer a base32 mistake with a complaint about another field.
fun EntryDraft.secretProblem(): VaultError.InvalidSecret? =
    if (secret.isEmpty()) null else Base32.validateSecret(secret)

// A paste and a filled-in form arrive at the same OtpAuthUri, so both are previewed and saved by one
// path. Every rule beyond the parsing here is the URI model's own and is reported as it refuses.
fun EntryDraft.resolved(): Outcome<OtpAuthUri, VaultError.InvalidEntry> {
    val digitCount = digits.toAsciiIntOrNull()
        ?: return Outcome.Failure(VaultError.InvalidEntry(DIGITS_RULE))
    val resolved = when (val factor = movingFactor(type, period, counter)) {
        is Outcome.Failure -> return factor
        is Outcome.Success -> factor.value
    }
    return try {
        Outcome.Success(
            OtpAuthUri(
                type = type,
                accountName = accountName,
                secret = secret,
                issuer = issuer.ifEmpty { null },
                algorithm = algorithm,
                digits = digitCount,
                period = resolved.period,
                counter = resolved.counter,
            ),
        )
    } catch (e: IllegalArgumentException) {
        // The model's messages state the rule rather than the value, and the one value that would
        // matter here — the secret — is named by its rule alone.
        Outcome.Failure(VaultError.InvalidEntry(e.message ?: "these values do not make a valid account"))
    }
}

// The type decides which moving factor the edit carries, and it is the entry's rather than the
// form's: an edit cannot change it.
fun EntryEditDraft.resolved(type: OtpType): Outcome<EntryEdit, VaultError.InvalidEntry> {
    val digitCount = digits.toAsciiIntOrNull()
        ?: return Outcome.Failure(VaultError.InvalidEntry(DIGITS_RULE))
    val resolved = when (val factor = movingFactor(type, period, counter)) {
        is Outcome.Failure -> return factor
        is Outcome.Success -> factor.value
    }
    return Outcome.Success(
        EntryEdit(
            accountName = accountName,
            issuer = issuer.ifEmpty { null },
            algorithm = algorithm,
            digits = digitCount,
            period = resolved.period,
            counter = resolved.counter,
        ),
    )
}

private const val DIGITS_RULE = "digits must be a whole number"
private const val PERIOD_RULE = "the period must be a whole number of seconds"
private const val COUNTER_RULE = "the counter must be a whole number"

private class Factor(val period: Int?, val counter: ULong?)

private fun movingFactor(type: OtpType, period: String, counter: String): Outcome<Factor, VaultError.InvalidEntry> =
    when (type) {
        OtpType.TOTP -> period.toAsciiIntOrNull()
            ?.let { Outcome.Success(Factor(it, null)) }
            ?: Outcome.Failure(VaultError.InvalidEntry(PERIOD_RULE))

        OtpType.HOTP -> counter.toAsciiULongOrNull()
            ?.let { Outcome.Success(Factor(null, it)) }
            ?: Outcome.Failure(VaultError.InvalidEntry(COUNTER_RULE))
    }
