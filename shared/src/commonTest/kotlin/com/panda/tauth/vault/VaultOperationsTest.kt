package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.valueOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

private const val OLD = "old password"
private const val NEW = "new password"

private fun body() = VaultBody(entries = listOf(totpEntry(), hotpEntry(counter = 5uL)))

private fun vault() = checkNotNull(VaultCodec.create(OLD.toCharArray(), body()).valueOrNull)

private fun opened(bytes: ByteArray, password: String): OpenVault {
    val outcome = VaultCodec.open(bytes, password.toCharArray())
    assertIs<Outcome.Success<OpenVault>>(outcome)
    return outcome.value
}

private fun dekOf(bytes: ByteArray, password: String): ByteArray =
    opened(bytes, password).use { vault -> checkNotNull(vault.useDek { dek -> dek.copyOf() }) }

private fun changed(): ByteArray =
    checkNotNull(VaultCodec.changePassword(vault(), OLD.toCharArray(), NEW.toCharArray()).valueOrNull)

class VaultOperationsTest {
    @Test
    fun `after a password change the vault opens under the new password`() {
        assertIs<Outcome.Success<OpenVault>>(VaultCodec.open(changed(), NEW.toCharArray()))
    }

    @Test
    fun `after a password change the old password no longer opens it`() {
        assertEquals(VaultError.WrongPassword, VaultCodec.open(changed(), OLD.toCharArray()).errorOrNull)
    }

    @Test
    fun `a password change leaves the key that encrypts the body unchanged`() {
        // The body is not re-encrypted under a new key, so a keyring copy of the DEK survives.
        val original = vault()
        val rewritten = checkNotNull(
            VaultCodec.changePassword(original, OLD.toCharArray(), NEW.toCharArray()).valueOrNull,
        )
        assertContentEquals(dekOf(original, OLD), dekOf(rewritten, NEW))
    }

    @Test
    fun `a password change preserves the entries`() {
        opened(changed(), NEW).use {
            assertEquals(body().entries.map { entry -> entry.id }, it.body.entries.map { entry -> entry.id })
        }
    }

    @Test
    fun `a password change draws a new salt`() {
        val original = vault()
        val rewritten = checkNotNull(
            VaultCodec.changePassword(original, OLD.toCharArray(), NEW.toCharArray()).valueOrNull,
        )
        opened(original, OLD).use { before ->
            opened(rewritten, NEW).use { after ->
                assertNotEquals(before.header.salt, after.header.salt)
            }
        }
    }

    @Test
    fun `a password change keeps the vault id`() {
        val original = vault()
        val rewritten = checkNotNull(
            VaultCodec.changePassword(original, OLD.toCharArray(), NEW.toCharArray()).valueOrNull,
        )
        opened(original, OLD).use { before ->
            opened(rewritten, NEW).use { after ->
                assertEquals(before.header.vaultId, after.header.vaultId)
            }
        }
    }

    @Test
    fun `a password change under the wrong current password is refused`() {
        val outcome = VaultCodec.changePassword(vault(), "wrong".toCharArray(), NEW.toCharArray())
        assertEquals(VaultError.WrongPassword, outcome.errorOrNull)
    }

    @Test
    fun `rotating the key changes the key that encrypts the body`() {
        val original = vault()
        val rotated = checkNotNull(VaultCodec.rotateDek(original, OLD.toCharArray()).valueOrNull)
        assertFalse(dekOf(original, OLD).contentEquals(dekOf(rotated, OLD)))
    }

    @Test
    fun `rotating the key keeps the same password`() {
        val rotated = checkNotNull(VaultCodec.rotateDek(vault(), OLD.toCharArray()).valueOrNull)
        assertIs<Outcome.Success<OpenVault>>(VaultCodec.open(rotated, OLD.toCharArray()))
    }

    @Test
    fun `rotating the key preserves the entries`() {
        val rotated = checkNotNull(VaultCodec.rotateDek(vault(), OLD.toCharArray()).valueOrNull)
        opened(rotated, OLD).use {
            assertEquals(body().entries.map { entry -> entry.id }, it.body.entries.map { entry -> entry.id })
        }
    }

    @Test
    fun `rotating the key keeps the existing salt`() {
        // The DEK is re-wrapped under the existing KEK, so the password derivation is untouched.
        val original = vault()
        val rotated = checkNotNull(VaultCodec.rotateDek(original, OLD.toCharArray()).valueOrNull)
        opened(original, OLD).use { before ->
            opened(rotated, OLD).use { after ->
                assertEquals(before.header.salt, after.header.salt)
            }
        }
    }

    @Test
    fun `rotating the key under the wrong password is refused`() {
        assertEquals(VaultError.WrongPassword, VaultCodec.rotateDek(vault(), "wrong".toCharArray()).errorOrNull)
    }
}
