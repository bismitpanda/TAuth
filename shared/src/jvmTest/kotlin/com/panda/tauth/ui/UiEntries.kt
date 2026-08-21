package com.panda.tauth.ui

import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpType
import kotlin.time.Instant

// RFC 4226 §5.1's published seed as base32.
internal const val SEED_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

internal fun totpRow(
    id: String = "0192f4c1-0000-7000-8000-0000000000a1",
    accountName: String = "alice",
    issuer: String? = "GitHub",
    algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    digits: Int = 6,
    period: Int = 30,
    orderIndex: Int = 0,
    createdAt: String = "2026-01-01T00:00:00Z",
) = UnlockedEntry(
    id = id,
    type = OtpType.TOTP,
    accountName = accountName,
    createdAt = Instant.parse(createdAt),
    issuer = issuer,
    algorithm = algorithm,
    digits = digits,
    period = period,
    counter = null,
    orderIndex = orderIndex,
)

internal fun hotpRow(
    id: String = "0192f4c1-0000-7000-8000-0000000000b1",
    accountName: String = "bob",
    issuer: String? = "Zendesk",
    algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    digits: Int = 6,
    counter: ULong = 41uL,
    orderIndex: Int = 1,
    createdAt: String = "2026-02-01T00:00:00Z",
) = UnlockedEntry(
    id = id,
    type = OtpType.HOTP,
    accountName = accountName,
    createdAt = Instant.parse(createdAt),
    issuer = issuer,
    algorithm = algorithm,
    digits = digits,
    period = null,
    counter = counter,
    orderIndex = orderIndex,
)
