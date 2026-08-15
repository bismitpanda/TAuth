package com.panda.tauth.totp

import com.panda.tauth.Outcome
import com.panda.tauth.vault.VaultError

private class Padding {
    var count = 0
}

// RFC 4648 §6.
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val PAD = '='
    private const val ASCII_LIMIT = 0x80

    // What a paste or a line-wrapped payload carries. Char.isWhitespace is wider and would swallow
    // characters no producer emits.
    private const val SKIPPED_WHITESPACE = " \t\r\n"
    private const val BITS_PER_SYMBOL = 5
    private const val BITS_PER_BYTE = 8
    private const val GROUP_SYMBOLS = 8
    private const val BYTE_MASK = 0xFF
    private const val SYMBOL_MASK = 0x1F

    // A trailing group of 1, 3 or 6 symbols cannot arise from any input, so it means truncation.
    private val WHOLE_BYTE_REMAINDERS = setOf(0, 2, 4, 5, 7)

    fun decode(input: String): Outcome<ByteArray, VaultError> {
        val symbols = StringBuilder(input.length)
        val pad = Padding()
        collect(input, symbols, pad)?.let { return Outcome.Failure(it) }
        if (symbols.length % GROUP_SYMBOLS !in WHOLE_BYTE_REMAINDERS) {
            return Outcome.Failure(VaultError.InvalidSecret("truncated base32 group"))
        }
        // Absent padding is accepted; wrong padding is not. RFC 4648 §6 pads to a multiple of eight,
        // so a wrong count means characters were lost.
        if (pad.count > 0 && (pad.count >= GROUP_SYMBOLS || (symbols.length + pad.count) % GROUP_SYMBOLS != 0)) {
            return Outcome.Failure(VaultError.InvalidSecret("base32 padding is the wrong length"))
        }
        return Outcome.Success(unpack(symbols))
    }

    // ASCII only. Unicode uppercase folds U+017F onto S and U+0131 onto I, so folding before the
    // alphabet check would make two different secrets decode to one key.
    private fun collect(input: String, symbols: StringBuilder, pad: Padding): VaultError? {
        for (raw in input) {
            if (raw.code >= ASCII_LIMIT) return VaultError.InvalidSecret("invalid base32 character")
            if (raw in SKIPPED_WHITESPACE) continue
            val symbol = raw.uppercaseChar()
            when {
                symbol == PAD -> pad.count++
                pad.count > 0 -> return VaultError.InvalidSecret("data follows base32 padding")
                symbol !in ALPHABET -> return VaultError.InvalidSecret("invalid base32 character")
                else -> symbols.append(symbol)
            }
        }
        return null
    }

    // A string that looks non-empty can carry no key, which HMAC would refuse only at code-generation
    // time. The decode is key material and is zeroed before this returns.
    fun validateSecret(secret: String): VaultError? {
        val bytes = when (val decoded = decode(secret)) {
            is Outcome.Failure -> return decoded.error
            is Outcome.Success -> decoded.value
        }
        val isEmpty = bytes.isEmpty()
        bytes.fill(0)
        return if (isEmpty) VaultError.InvalidSecret("empty secret") else null
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * BITS_PER_BYTE + BITS_PER_SYMBOL - 1) / BITS_PER_SYMBOL)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl BITS_PER_BYTE) or (byte.toInt() and BYTE_MASK)
            bits += BITS_PER_BYTE
            while (bits >= BITS_PER_SYMBOL) {
                bits -= BITS_PER_SYMBOL
                out.append(ALPHABET[(buffer shr bits) and SYMBOL_MASK])
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer shl (BITS_PER_SYMBOL - bits)) and SYMBOL_MASK])
        }
        return out.toString()
    }

    private fun unpack(symbols: CharSequence): ByteArray {
        val out = ByteArray(symbols.length * BITS_PER_SYMBOL / BITS_PER_BYTE)
        var buffer = 0
        var bits = 0
        var index = 0
        for (symbol in symbols) {
            buffer = (buffer shl BITS_PER_SYMBOL) or ALPHABET.indexOf(symbol)
            bits += BITS_PER_SYMBOL
            if (bits >= BITS_PER_BYTE) {
                bits -= BITS_PER_BYTE
                out[index] = ((buffer shr bits) and BYTE_MASK).toByte()
                index++
            }
        }
        // RFC 4648 §3.5 permits a decoder to accept non-zero trailing bits, and producers emit them.
        return out
    }
}
