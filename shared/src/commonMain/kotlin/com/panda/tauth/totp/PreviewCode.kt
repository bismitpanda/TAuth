package com.panda.tauth.totp

import com.panda.tauth.valueOrNull

// Computed without storing anything, so confirming a hotp account does not advance the counter the
// server expects. The key decoded from the base32 text is zeroed before this returns.
fun previewCode(uri: OtpAuthUri, epochSeconds: Long): String? {
    val key = Base32.decode(uri.secret).valueOrNull ?: return null
    return try {
        when (uri.type) {
            OtpType.TOTP -> uri.period?.let { Totp.generate(key, epochSeconds, uri.algorithm, uri.digits, it) }
            OtpType.HOTP -> uri.counter?.let { Hotp.generate(key, it, uri.algorithm, uri.digits) }
        }
    } finally {
        key.fill(0)
    }
}
