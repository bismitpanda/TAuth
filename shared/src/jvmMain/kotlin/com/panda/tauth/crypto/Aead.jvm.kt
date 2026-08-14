package com.panda.tauth.crypto

import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val KEY_ALGORITHM = "AES"

private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, associatedData: ByteArray): Cipher {
    require(key.size == AEAD_KEY_BYTES) { "key must be $AEAD_KEY_BYTES bytes" }
    require(nonce.size == AEAD_NONCE_BYTES) { "nonce must be $AEAD_NONCE_BYTES bytes" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(mode, SecretKeySpec(key, KEY_ALGORITHM), GCMParameterSpec(AEAD_TAG_BITS, nonce))
    cipher.updateAAD(associatedData)
    return cipher
}

actual fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
    // The JDK mandates this transformation, so a failure is a broken runtime rather than something
    // a caller can act on.
    try {
        cipher(Cipher.ENCRYPT_MODE, key, nonce, associatedData).doFinal(plaintext)
    } catch (e: GeneralSecurityException) {
        throw IllegalStateException("$TRANSFORMATION failed in this JVM", e)
    } catch (e: ProviderException) {
        // A RuntimeException, so the catch above does not cover it.
        throw IllegalStateException("$TRANSFORMATION failed in this JVM", e)
    }

actual fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray? =
    try {
        cipher(Cipher.DECRYPT_MODE, key, nonce, associatedData).doFinal(ciphertext)
    } catch (_: AEADBadTagException) {
        null
    } catch (e: GeneralSecurityException) {
        throw IllegalStateException("$TRANSFORMATION failed in this JVM", e)
    } catch (e: ProviderException) {
        // A RuntimeException, so the catch above does not cover it.
        throw IllegalStateException("$TRANSFORMATION failed in this JVM", e)
    }
