package com.panda.tauth.crypto

import kotlin.io.encoding.Base64

// RFC 4648 §4 with padding, so the header JSON reproduces byte for byte.
fun base64Encode(bytes: ByteArray): String = Base64.Default.encode(bytes)

// Null when the text is not valid base64.
fun base64Decode(text: String): ByteArray? = try {
    Base64.Default.decode(text)
} catch (_: IllegalArgumentException) {
    null
}
