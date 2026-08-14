package com.panda.tauth.totp

private const val ESCAPE = '%'
private const val ESCAPE_LENGTH = 3
private const val HEX_RADIX = 16
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F
private const val BYTE_MASK = 0xFF
private const val HEX_DIGITS = "0123456789ABCDEF"

// RFC 3986 §2.3.
private const val UNRESERVED_PUNCTUATION = "-._~"

// Malformed input is rejected, not repaired.
internal fun percentDecode(input: String): String? {
    // An unpaired surrogate is not text. Rejected here rather than at export time, long after the
    // account was stored.
    if (!input.isWellFormed()) return null
    if (ESCAPE !in input) return input
    val out = StringBuilder(input.length)
    val pending = mutableListOf<Byte>()
    var index = 0
    while (index < input.length) {
        if (input[index] == ESCAPE) {
            pending.add(escapeByteAt(input, index) ?: return null)
            index += ESCAPE_LENGTH
        } else {
            // An escape run is a UTF-8 byte sequence and only becomes text once it ends.
            if (!flush(pending, out)) return null
            out.append(input[index])
            index++
        }
    }
    return if (flush(pending, out)) out.toString() else null
}

private fun escapeByteAt(input: String, index: Int): Byte? {
    if (index + ESCAPE_LENGTH > input.length) return null
    val hex = input.substring(index + 1, index + ESCAPE_LENGTH)
    if (!hex.all { it.isAsciiHexDigit() }) return null
    return hex.toInt(HEX_RADIX).toByte()
}

// Every string arriving here comes through percentDecode or the vault body, both valid by
// construction, so an unpaired surrogate is a contract violation rather than an operational failure.
internal fun percentEncode(input: String): String {
    val encoded = try {
        input.encodeToByteArray(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("input is not well-formed UTF-16", e)
    }
    val out = StringBuilder(input.length)
    for (byte in encoded) {
        val value = byte.toInt() and BYTE_MASK
        val char = value.toChar()
        if (char.isAsciiAlphanumeric() || char in UNRESERVED_PUNCTUATION) {
            out.append(char)
        } else {
            out.append(ESCAPE).append(HEX_DIGITS[value shr NIBBLE_BITS]).append(HEX_DIGITS[value and NIBBLE_MASK])
        }
    }
    return out.toString()
}

private fun flush(pending: MutableList<Byte>, out: StringBuilder): Boolean {
    if (pending.isEmpty()) return true
    val text = try {
        pending.toByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        return false
    }
    out.append(text)
    pending.clear()
    return true
}

private fun String.isWellFormed(): Boolean = try {
    encodeToByteArray(throwOnInvalidSequence = true)
    true
} catch (_: CharacterCodingException) {
    false
}

private fun Char.isAsciiAlphanumeric(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
