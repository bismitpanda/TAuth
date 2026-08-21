package com.panda.tauth.vault

// Two spellings of one key are one account, and nothing here decodes a secret to establish that.
private fun normalisedSecret(secret: String): String = secret.uppercase().filterNot { it == '=' || it.isWhitespace() }

internal data class AccountKey(val issuer: String?, val accountName: String, val secret: String) {
    override fun toString(): String = "AccountKey(issuer=$issuer, accountName=$accountName, secret=<redacted>)"
}

internal fun VaultEntry.key(): AccountKey = AccountKey(issuer, accountName, normalisedSecret(secret))

internal fun List<VaultEntry>.holds(entry: VaultEntry): Boolean = any { it.key() == entry.key() }
