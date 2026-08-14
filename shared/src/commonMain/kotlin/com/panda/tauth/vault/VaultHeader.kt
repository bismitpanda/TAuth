package com.panda.tauth.vault

import kotlinx.serialization.Serializable

// Byte fields are base64 because the header is JSON. The salt is the only part of the derivation
// that travels; the rest is fixed by `v`.
@Serializable
data class VaultHeader(val v: Int, val vaultId: String, val salt: String, val wrap: WrapBlock, val body: BodyBlock)

// `ct` is the DEK sealed under the KEK: 32 bytes of key then the 16-byte tag.
@Serializable
data class WrapBlock(val nonce: String, val ct: String)

@Serializable
data class BodyBlock(val nonce: String)
