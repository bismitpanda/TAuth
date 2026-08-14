package com.panda.tauth.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

// McGrew and Viega, "The Galois/Counter Mode of Operation", Appendix B, test case 16.
private val CASE16_KEY = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308".hexToByteArray()
private val CASE16_NONCE = "cafebabefacedbaddecaf888".hexToByteArray()
private val CASE16_AAD = "feedfacedeadbeeffeedfacedeadbeefabaddad2".hexToByteArray()
private val CASE16_PLAINTEXT = (
    "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
        "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
    ).hexToByteArray()
private val CASE16_CIPHERTEXT = (
    "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
        "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662"
    ).hexToByteArray()
private val CASE16_TAG = "76fc6ece0f4e1768cddf8853bb2d551b".hexToByteArray()

private val KEY = ByteArray(AEAD_KEY_BYTES) { it.toByte() }
private val NONCE = ByteArray(AEAD_NONCE_BYTES) { (it + 0x40).toByte() }

// One nonce per sealing test under the shared key. Sealing twice under a single key and nonce is
// what the ledger in Aead.jvm.kt refuses, so tests that seal cannot share one.
private fun nonce(distinguisher: Int) = ByteArray(AEAD_NONCE_BYTES) { (it + distinguisher).toByte() }

class AeadTest {
    @Test
    fun `opens the GCM specification test case 16`() {
        val opened = aeadOpen(CASE16_KEY, CASE16_NONCE, CASE16_CIPHERTEXT + CASE16_TAG, CASE16_AAD)
        assertContentEquals(CASE16_PLAINTEXT, opened)
    }

    @Test
    fun `a flipped ciphertext bit fails the tag`() {
        val damaged = (CASE16_CIPHERTEXT + CASE16_TAG).copyOf()
        damaged[0] = (damaged[0].toInt() xor 1).toByte()
        assertNull(aeadOpen(CASE16_KEY, CASE16_NONCE, damaged, CASE16_AAD))
    }

    @Test
    fun `a flipped tag bit fails the tag`() {
        val damaged = (CASE16_CIPHERTEXT + CASE16_TAG).copyOf()
        damaged[damaged.size - 1] = (damaged[damaged.size - 1].toInt() xor 1).toByte()
        assertNull(aeadOpen(CASE16_KEY, CASE16_NONCE, damaged, CASE16_AAD))
    }

    @Test
    fun `a flipped associated data bit fails the tag`() {
        val damaged = CASE16_AAD.copyOf()
        damaged[0] = (damaged[0].toInt() xor 1).toByte()
        assertNull(aeadOpen(CASE16_KEY, CASE16_NONCE, CASE16_CIPHERTEXT + CASE16_TAG, damaged))
    }

    @Test
    fun `a wrong key fails the tag`() {
        val other = CASE16_KEY.copyOf()
        other[0] = (other[0].toInt() xor 1).toByte()
        assertNull(aeadOpen(other, CASE16_NONCE, CASE16_CIPHERTEXT + CASE16_TAG, CASE16_AAD))
    }

    @Test
    fun `a wrong nonce fails the tag`() {
        val other = CASE16_NONCE.copyOf()
        other[0] = (other[0].toInt() xor 1).toByte()
        assertNull(aeadOpen(CASE16_KEY, other, CASE16_CIPHERTEXT + CASE16_TAG, CASE16_AAD))
    }

    @Test
    fun `ciphertext shorter than the tag is rejected`() {
        assertNull(aeadOpen(KEY, CASE16_NONCE, ByteArray(AEAD_TAG_BYTES - 1), ByteArray(0)))
    }

    @Test
    fun `seals the GCM specification test case 16`() {
        val sealed = aeadSeal(CASE16_KEY, CASE16_NONCE, CASE16_PLAINTEXT, CASE16_AAD)
        assertEquals((CASE16_CIPHERTEXT + CASE16_TAG).toHexString(), sealed.toHexString())
    }

    @Test
    fun `a sealed message opens to the original plaintext`() {
        val plaintext = "vault body".encodeToByteArray()
        val aad = "prefix".encodeToByteArray()
        val nonce = nonce(0x10)
        assertContentEquals(plaintext, aeadOpen(KEY, nonce, aeadSeal(KEY, nonce, plaintext, aad), aad))
    }

    @Test
    fun `two nonces produce different ciphertext for identical input`() {
        val plaintext = "same".encodeToByteArray()
        assertNotEquals(
            aeadSeal(KEY, nonce(0x20), plaintext, ByteArray(0)).toHexString(),
            aeadSeal(KEY, nonce(0x30), plaintext, ByteArray(0)).toHexString(),
        )
    }

    @Test
    fun `opening with the wrong associated data fails`() {
        val nonce = nonce(0x50)
        val sealed = aeadSeal(KEY, nonce, "body".encodeToByteArray(), "one".encodeToByteArray())
        assertNull(aeadOpen(KEY, nonce, sealed, "two".encodeToByteArray()))
    }

    @Test
    fun `a nonce of the wrong length is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            aeadSeal(KEY, ByteArray(AEAD_NONCE_BYTES - 1), ByteArray(1), ByteArray(0))
        }
    }

    @Test
    fun `a key of the wrong length is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            aeadSeal(ByteArray(16), CASE16_NONCE, ByteArray(1), ByteArray(0))
        }
    }
}
