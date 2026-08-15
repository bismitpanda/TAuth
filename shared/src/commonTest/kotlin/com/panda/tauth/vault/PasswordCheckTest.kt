package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.crypto.base64Encode
import com.panda.tauth.errorOrNull
import com.panda.tauth.valueOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val PASSWORD = "correct horse battery staple"
private const val WRONG_PASSWORD = "correct horse battery stapld"

private val BODY = VaultBody(entries = listOf(totpEntry(), hotpEntry(counter = 3uL, orderIndex = 1)))

// One derivation for the whole class rather than one per test: Argon2id is priced to be slow. The
// header is what the check reads, and an open vault is where a live one comes from.
private val HEADER by lazy {
    val bytes = checkNotNull(VaultCodec.create(PASSWORD.toCharArray(), BODY).valueOrNull)
    checkNotNull(VaultCodec.open(bytes, PASSWORD.toCharArray()).valueOrNull).use { it.header }
}

class PasswordCheckTest {
    @Test
    fun `the password that created the vault is accepted`() {
        assertIs<Outcome.Success<Unit>>(VaultCodec.verify(HEADER, PASSWORD.toCharArray()))
    }

    @Test
    fun `a password one character out is refused`() {
        assertEquals(VaultError.WrongPassword, VaultCodec.verify(HEADER, WRONG_PASSWORD.toCharArray()).errorOrNull)
    }

    // The header is plaintext and attacker-writable, and a field it cannot decode is damage rather
    // than a password that did not work.
    @Test
    fun `a salt that is not base64 is refused as damage`() {
        val damaged = HEADER.copy(salt = "not base64 at all")

        assertIs<VaultError.Corrupt>(VaultCodec.verify(damaged, PASSWORD.toCharArray()).errorOrNull)
    }

    @Test
    fun `a wrapped key of the wrong size is refused as damage`() {
        val damaged = HEADER.copy(wrap = HEADER.wrap.copy(ct = base64Encode(ByteArray(1))))

        assertIs<VaultError.Corrupt>(VaultCodec.verify(damaged, PASSWORD.toCharArray()).errorOrNull)
    }

    @Test
    fun `a wrap nonce of the wrong size is refused as damage`() {
        val damaged = HEADER.copy(wrap = HEADER.wrap.copy(nonce = base64Encode(ByteArray(1))))

        assertIs<VaultError.Corrupt>(VaultCodec.verify(damaged, PASSWORD.toCharArray()).errorOrNull)
    }
}
