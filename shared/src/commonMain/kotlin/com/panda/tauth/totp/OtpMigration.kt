package com.panda.tauth.totp

import com.panda.tauth.Outcome
import com.panda.tauth.crypto.base64Decode
import com.panda.tauth.vault.VaultError

const val MIGRATION_SCHEME = "otpauth-migration://"

// The payload carries no period: the authenticator writing it generates every totp at thirty seconds.
const val MIGRATION_PERIOD_SECONDS = 30

data class MigrationAccount(
    val secret: String,
    val accountName: String,
    val issuer: String?,
    val algorithm: HashAlgorithm?,
    val digits: Int,
    val type: OtpType,
    val counter: ULong,
) {
    override fun toString(): String =
        "MigrationAccount(issuer=$issuer, accountName=$accountName, algorithm=$algorithm, digits=$digits, " +
            "type=$type, counter=$counter, secret=<redacted>)"
}

data class MigrationBatch(val accounts: List<MigrationAccount>, val part: Int, val parts: Int)

fun isMigrationUri(text: String): Boolean = text.trimStart().startsWith(MIGRATION_SCHEME, ignoreCase = true)

fun readMigration(text: String): Outcome<MigrationBatch, VaultError.Corrupt> {
    val data = dataParameter(text) ?: return refusal("this code carries no export data")
    val decoded = percentDecode(data) ?: return refusal("this code's export data is not readable text")
    val bytes = base64Decode(padded(decoded)) ?: return refusal("this code's export data is not base64")
    return try {
        Outcome.Success(payload(bytes))
    } catch (_: IndexOutOfBoundsException) {
        // Walking off the end is how the reader reports a truncated message, and the throw is the
        // whole answer.
        refusal("this code's export data is incomplete")
    }
}

private fun refusal(detail: String): Outcome.Failure<VaultError.Corrupt> = Outcome.Failure(VaultError.Corrupt(detail))

private const val QUERY_SEPARATOR = '?'
private const val PARAM_SEPARATOR = '&'
private const val DATA_PARAM = "data="
private const val BASE64_GROUP = 4
private const val BASE64_PAD = '='

private fun dataParameter(text: String): String? = text.trim()
    .substringAfter(QUERY_SEPARATOR, "")
    .split(PARAM_SEPARATOR)
    .firstOrNull { it.startsWith(DATA_PARAM) }
    ?.removePrefix(DATA_PARAM)
    ?.takeIf { it.isNotEmpty() }

// Producers differ over whether they pad, and the decoder takes only what RFC 4648 §4 spells out.
private fun padded(text: String): String {
    val remainder = text.length % BASE64_GROUP
    return if (remainder == 0) text else text + BASE64_PAD.toString().repeat(BASE64_GROUP - remainder)
}

private const val WIRE_VARINT = 0
private const val WIRE_64_BIT = 1
private const val WIRE_LENGTH = 2
private const val WIRE_32_BIT = 5
private const val CONTINUATION = 0x80
private const val VARINT_BITS = 0x7F
private const val VARINT_SHIFT = 7
private const val TAG_SHIFT = 3
private const val WIRE_MASK = 0x7
private const val BYTES_64_BIT = 8
private const val BYTES_32_BIT = 4

private const val FIELD_ACCOUNT = 1
private const val FIELD_BATCH_SIZE = 3
private const val FIELD_BATCH_INDEX = 4

private const val FIELD_SECRET = 1
private const val FIELD_NAME = 2
private const val FIELD_ISSUER = 3
private const val FIELD_ALGORITHM = 4
private const val FIELD_DIGITS = 5
private const val FIELD_TYPE = 6
private const val FIELD_COUNTER = 7

private const val ALGORITHM_UNSPECIFIED = 0
private const val ALGORITHM_SHA1 = 1
private const val ALGORITHM_SHA256 = 2
private const val ALGORITHM_SHA512 = 3

private const val DIGITS_SIX = 1
private const val DIGITS_EIGHT = 2

private const val TYPE_HOTP = 1

private const val SINGLE_CODE = 1

private class Reader(private val bytes: ByteArray) {
    private var position = 0

    fun hasMore(): Boolean = position < bytes.size

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = bytes[position].toInt()
            position++
            result = result or ((byte and VARINT_BITS).toLong() shl shift)
            if (byte and CONTINUATION == 0) return result
            shift += VARINT_SHIFT
        }
    }

    fun length(): ByteArray {
        val size = varint().toInt()
        if (size < 0 || position + size > bytes.size) stop()
        return bytes.copyOfRange(position, position + size).also { position += size }
    }

    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> varint()
            WIRE_64_BIT -> position += BYTES_64_BIT
            WIRE_LENGTH -> length()
            WIRE_32_BIT -> position += BYTES_32_BIT
            else -> stop()
        }
        if (position > bytes.size) stop()
    }

    private fun stop(): Nothing = throw IndexOutOfBoundsException("the message ends before a field it declares")
}

private fun payload(bytes: ByteArray): MigrationBatch {
    val accounts = mutableListOf<MigrationAccount>()
    var size = SINGLE_CODE
    var index = 0
    val reader = Reader(bytes)
    while (reader.hasMore()) {
        val tag = reader.varint().toInt()
        when (tag shr TAG_SHIFT) {
            FIELD_ACCOUNT -> accounts.add(account(reader.length()))
            FIELD_BATCH_SIZE -> size = reader.varint().toInt()
            FIELD_BATCH_INDEX -> index = reader.varint().toInt()
            else -> reader.skip(tag and WIRE_MASK)
        }
    }
    val parts = size.coerceAtLeast(SINGLE_CODE)
    return MigrationBatch(accounts, part = (index + 1).coerceIn(SINGLE_CODE, parts), parts = parts)
}

private fun account(bytes: ByteArray): MigrationAccount {
    var secret = ByteArray(0)
    var name = ""
    var issuer = ""
    var algorithm = ALGORITHM_UNSPECIFIED
    var digits = 0
    var type = 0
    var counter = 0L

    val reader = Reader(bytes)
    while (reader.hasMore()) {
        val tag = reader.varint().toInt()
        when (tag shr TAG_SHIFT) {
            FIELD_SECRET -> secret = reader.length()
            FIELD_NAME -> name = reader.length().decodeToString()
            FIELD_ISSUER -> issuer = reader.length().decodeToString()
            FIELD_ALGORITHM -> algorithm = reader.varint().toInt()
            FIELD_DIGITS -> digits = reader.varint().toInt()
            FIELD_TYPE -> type = reader.varint().toInt()
            FIELD_COUNTER -> counter = reader.varint()
            else -> reader.skip(tag and WIRE_MASK)
        }
    }

    val (labelIssuer, accountName) = splitLabel(name, issuer)
    return MigrationAccount(
        secret = Base32.encode(secret).also { secret.fill(0) },
        accountName = accountName,
        issuer = labelIssuer,
        algorithm = algorithmFor(algorithm),
        digits = digitsFor(digits),
        type = if (type == TYPE_HOTP) OtpType.HOTP else OtpType.TOTP,
        counter = counter.toULong(),
    )
}

private fun splitLabel(name: String, issuer: String): Pair<String?, String> {
    val separator = OtpAuthUri.LABEL_SEPARATOR
    val prefix = name.substringBefore(separator, "").trim()
    val account = if (separator in name) name.substringAfter(separator).trim() else name.trim()
    return (issuer.trim().ifEmpty { prefix }).ifEmpty { null } to account
}

private fun algorithmFor(algorithm: Int): HashAlgorithm? = when (algorithm) {
    ALGORITHM_UNSPECIFIED, ALGORITHM_SHA1 -> HashAlgorithm.SHA1
    ALGORITHM_SHA256 -> HashAlgorithm.SHA256
    ALGORITHM_SHA512 -> HashAlgorithm.SHA512
    else -> null
}

private fun digitsFor(digits: Int): Int = when (digits) {
    DIGITS_EIGHT -> OtpCore.DIGITS_MAX
    DIGITS_SIX -> OtpCore.DIGITS_DEFAULT
    else -> OtpCore.DIGITS_DEFAULT
}
