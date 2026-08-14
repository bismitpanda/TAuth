package com.panda.tauth.crypto

const val AEAD_KEY_BYTES = 32
const val AEAD_NONCE_BYTES = 12
const val AEAD_TAG_BITS = 128
const val AEAD_TAG_BYTES = AEAD_TAG_BITS / 8

// AES-256-GCM, ciphertext with the tag appended. VaultCodec generates every nonce it passes here.
expect fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray

// Null when the tag does not verify, which GCM reports the same way for a wrong key and a damaged
// ciphertext.
expect fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray?
