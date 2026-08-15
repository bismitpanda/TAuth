package com.panda.tauth.vault

import com.panda.tauth.totp.OtpAuthUri
import kotlin.time.Instant

// The URI is the shared secret in full, in a String nothing here retains, which is why the one caller
// that reaches this is behind the password re-entry.
internal fun VaultEntry.toOtpAuthUri(): OtpAuthUri = OtpAuthUri(
    type = type,
    accountName = accountName,
    secret = secret,
    issuer = issuer,
    algorithm = algorithm,
    digits = digits,
    period = period,
    counter = counter,
)

// The id and the creation time are the caller's: a screen has neither a generator nor a clock.
fun OtpAuthUri.toEntry(id: String, createdAt: Instant): VaultEntry = VaultEntry(
    id = id,
    type = type,
    accountName = accountName,
    secret = secret,
    createdAt = createdAt,
    issuer = issuer,
    algorithm = algorithm,
    digits = digits,
    period = period,
    counter = counter,
)
