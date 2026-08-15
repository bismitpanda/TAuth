package com.panda.tauth.session

import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpType
import com.panda.tauth.vault.VaultEntry
import kotlin.time.Instant

// Everything a screen draws about an account and nothing that generates its codes: an entry the UI
// holds carries neither the base32 text nor the decoded key.
data class UnlockedEntry(
    val id: String,
    val type: OtpType,
    val accountName: String,
    val createdAt: Instant,
    val issuer: String?,
    val algorithm: HashAlgorithm,
    val digits: Int,
    val period: Int?,
    val counter: ULong?,
    val orderIndex: Int,
)

internal fun VaultEntry.withoutSecret(): UnlockedEntry = UnlockedEntry(
    id = id,
    type = type,
    accountName = accountName,
    createdAt = createdAt,
    issuer = issuer,
    algorithm = algorithm,
    digits = digits,
    period = period,
    counter = counter,
    orderIndex = orderIndex,
)
