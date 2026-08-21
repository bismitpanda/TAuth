package com.panda.tauth.totp

import com.panda.tauth.crypto.base64Encode

// RFC 4226 §5.1's published seed.
internal val MIGRATION_SEED = "12345678901234567890".encodeToByteArray()

internal const val MIGRATION_ALGORITHM_SHA256 = 2
internal const val MIGRATION_ALGORITHM_MD5 = 4
internal const val MIGRATION_DIGITS_EIGHT = 2
internal const val MIGRATION_TYPE_HOTP = 1
internal const val MIGRATION_TYPE_TOTP = 2

private const val VARINT_BITS = 0x7FL
private const val CONTINUATION = 0x80
private const val VARINT_SHIFT = 7
private const val TAG_SHIFT = 3
private const val WIRE_LENGTH = 2

internal fun migrationVarint(value: Long): ByteArray {
    val out = mutableListOf<Byte>()
    var rest = value
    while (true) {
        val byte = (rest and VARINT_BITS).toInt()
        rest = rest ushr VARINT_SHIFT
        if (rest == 0L) {
            out.add(byte.toByte())
            return out.toByteArray()
        }
        out.add((byte or CONTINUATION).toByte())
    }
}

internal fun migrationField(number: Int, value: Long): ByteArray =
    migrationVarint((number shl TAG_SHIFT).toLong()) + migrationVarint(value)

internal fun migrationField(number: Int, value: ByteArray): ByteArray =
    migrationVarint(((number shl TAG_SHIFT) or WIRE_LENGTH).toLong()) +
        migrationVarint(value.size.toLong()) +
        value

internal fun migrationField(number: Int, value: String): ByteArray = migrationField(number, value.encodeToByteArray())

internal fun migrationAccount(
    secret: ByteArray = MIGRATION_SEED,
    name: String = "alice",
    issuer: String? = "GitHub",
    algorithm: Int? = null,
    digits: Int? = null,
    type: Int = MIGRATION_TYPE_TOTP,
    counter: Long? = null,
): ByteArray = migrationField(1, secret) +
    migrationField(2, name) +
    (issuer?.let { migrationField(3, it) } ?: ByteArray(0)) +
    (algorithm?.let { migrationField(4, it.toLong()) } ?: ByteArray(0)) +
    (digits?.let { migrationField(5, it.toLong()) } ?: ByteArray(0)) +
    migrationField(6, type.toLong()) +
    (counter?.let { migrationField(7, it) } ?: ByteArray(0))

internal fun migrationPayload(vararg accounts: ByteArray, size: Int? = null, index: Int? = null): ByteArray =
    accounts.fold(ByteArray(0)) { all, one -> all + migrationField(1, one) } +
        (size?.let { migrationField(3, it.toLong()) } ?: ByteArray(0)) +
        (index?.let { migrationField(4, it.toLong()) } ?: ByteArray(0))

internal fun migrationUri(bytes: ByteArray): String =
    MIGRATION_SCHEME + "offline?data=" + percentEncode(base64Encode(bytes))
