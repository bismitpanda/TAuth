package com.panda.tauth.vault

import kotlinx.serialization.json.Json

// magic | version | headerLength | headerCrc | header JSON | ciphertext with its tag appended.
internal const val MAGIC = "TAUTH"
internal const val MAGIC_BYTES = 5
internal const val VERSION_BYTES = 1
internal const val LENGTH_BYTES = 4
internal const val CRC_BYTES = 4

// The checksum covers these ten bytes too. Their layout is fixed for every format version.
internal const val CRC_OFFSET = MAGIC_BYTES + VERSION_BYTES + LENGTH_BYTES
internal const val PREFIX_BYTES = CRC_OFFSET + CRC_BYTES

internal const val FORMAT_VERSION = 1
internal const val HEADER_VERSION = 1
internal const val BODY_VERSION = 1

internal const val MAX_HEADER_BYTES = 64 * 1024

// Without a ceiling a hostile file raises OutOfMemoryError, an Error the IOException catch in
// VaultStore.readFile does not take and no Outcome carries.
internal const val MAX_VAULT_BYTES = 16 * 1024 * 1024

// Unknown keys are tolerated so a later version fails on its version number, not on a parse error.
internal val vaultJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

// The same document rules, laid out to be read: a plaintext export is opened in a text editor by
// whoever is migrating, which the vault file never is.
internal val plaintextExportJson = Json(vaultJson) {
    prettyPrint = true
}
