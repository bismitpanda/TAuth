package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.crypto.AEAD_KEY_BYTES
import com.panda.tauth.crypto.AEAD_NONCE_BYTES
import com.panda.tauth.crypto.AEAD_TAG_BYTES
import com.panda.tauth.crypto.ARGON2_SALT_BYTES
import com.panda.tauth.crypto.SecureBytes
import com.panda.tauth.crypto.aeadOpen
import com.panda.tauth.crypto.aeadSeal
import com.panda.tauth.crypto.argon2id
import com.panda.tauth.crypto.base64Decode
import com.panda.tauth.crypto.base64Encode
import com.panda.tauth.crypto.crc32
import com.panda.tauth.crypto.secureRandomBytes
import com.panda.tauth.flatMap
import kotlinx.serialization.SerializationException

internal const val VAULT_ID_BYTES = 16

private val NO_ASSOCIATED_DATA = ByteArray(0)

// close() zeroes the DEK, and every path that finishes with it must call close(), error paths
// included.
class OpenVault internal constructor(
    val body: VaultBody,
    internal val header: VaultHeader,
    private val dek: SecureBytes,
) : AutoCloseable {
    val isClosed: Boolean get() = dek.isDestroyed

    internal fun dekBytes(): ByteArray = dek.reveal()

    override fun close() = dek.destroy()

    override fun toString(): String = "OpenVault(entries=${body.entries.size}, closed=$isClosed)"
}

// Every nonce comes from secureRandomBytes and no function here takes one as a parameter. Reusing a
// nonce with one key across two plaintexts breaks GCM completely.
object VaultCodec {
    fun create(password: CharArray, body: VaultBody = VaultBody()): ByteArray {
        val salt = secureRandomBytes(ARGON2_SALT_BYTES)
        val dek = secureRandomBytes(AEAD_KEY_BYTES)
        return try {
            val header = VaultHeader(
                v = HEADER_VERSION,
                vaultId = base64Encode(secureRandomBytes(VAULT_ID_BYTES)),
                salt = base64Encode(salt),
                wrap = wrapDek(password, salt, dek),
                body = BodyBlock(""),
            )
            assemble(header, body, dek)
        } finally {
            dek.fill(0)
        }
    }

    fun open(bytes: ByteArray, password: CharArray): Outcome<OpenVault, VaultError> =
        readEnvelope(bytes).flatMap { envelope -> unlock(envelope, password) }

    fun encode(vault: OpenVault, body: VaultBody): ByteArray = assemble(vault.header, body, vault.dekBytes())

    // The DEK is unchanged, so a leaked one survives a password change; rotateDek replaces it.
    fun changePassword(
        bytes: ByteArray,
        currentPassword: CharArray,
        newPassword: CharArray,
    ): Outcome<ByteArray, VaultError> = open(bytes, currentPassword).flatMap { vault ->
        vault.use {
            val salt = secureRandomBytes(ARGON2_SALT_BYTES)
            val header = vault.header.copy(
                salt = base64Encode(salt),
                wrap = wrapDek(newPassword, salt, vault.dekBytes()),
            )
            Outcome.Success(assemble(header, vault.body, vault.dekBytes()))
        }
    }

    fun rotateDek(bytes: ByteArray, password: CharArray): Outcome<ByteArray, VaultError> =
        open(bytes, password).flatMap { vault ->
            vault.use {
                val salt = base64Decode(vault.header.salt)
                    ?: return@use Outcome.Failure(VaultError.Corrupt("salt is not valid base64"))
                val fresh = secureRandomBytes(AEAD_KEY_BYTES)
                try {
                    val header = vault.header.copy(wrap = wrapDek(password, salt, fresh))
                    Outcome.Success(assemble(header, vault.body, fresh))
                } finally {
                    fresh.fill(0)
                }
            }
        }

    private fun wrapDek(password: CharArray, salt: ByteArray, dek: ByteArray): WrapBlock {
        val nonce = secureRandomBytes(AEAD_NONCE_BYTES)
        val ciphertext = withKek(password, salt) { kek ->
            aeadSeal(kek, nonce, dek, NO_ASSOCIATED_DATA)
        }
        return WrapBlock(nonce = base64Encode(nonce), ct = base64Encode(ciphertext))
    }

    // The KEK is zeroed on every path. Internal rather than private so a test can watch that happen.
    internal inline fun <T> withKek(password: CharArray, salt: ByteArray, block: (ByteArray) -> T): T {
        val kek = argon2id(password, salt, AEAD_KEY_BYTES)
        return try {
            block(kek)
        } finally {
            kek.fill(0)
        }
    }

    private fun assemble(header: VaultHeader, body: VaultBody, dek: ByteArray): ByteArray {
        val bodyBytes = vaultJson.encodeToString(body.renumbered()).encodeToByteArray()
        // A fresh nonce on every write without exception.
        val nonce = secureRandomBytes(AEAD_NONCE_BYTES)
        val prefix = prefixOf(header.copy(body = BodyBlock(base64Encode(nonce))))
        return prefix + aeadSeal(dek, nonce, bodyBytes, prefix)
    }

    internal fun prefixOf(header: VaultHeader): ByteArray =
        prefixOf(vaultJson.encodeToString(header).encodeToByteArray())

    // Over raw bytes so tests can build header JSON the model would refuse to produce.
    internal fun prefixOf(json: ByteArray): ByteArray {
        val prefix = ByteArray(PREFIX_BYTES + json.size)
        MAGIC.encodeToByteArray().copyInto(prefix, 0)
        prefix[MAGIC_BYTES] = FORMAT_VERSION.toByte()
        writeUInt32(prefix, MAGIC_BYTES + VERSION_BYTES, json.size.toUInt())
        json.copyInto(prefix, PREFIX_BYTES)
        writeUInt32(prefix, CRC_OFFSET, headerChecksum(prefix, json))
        return prefix
    }

    private fun headerChecksum(file: ByteArray, json: ByteArray): UInt = crc32(file.copyOfRange(0, CRC_OFFSET) + json)

    private fun readEnvelope(bytes: ByteArray): Outcome<Envelope, VaultError> =
        checkPreamble(bytes).flatMap { headerLength -> sliceEnvelope(bytes, headerLength) }

    private fun checkPreamble(bytes: ByteArray): Outcome<Int, VaultError> {
        if (bytes.size < PREFIX_BYTES) {
            return Outcome.Failure(VaultError.Corrupt("file is shorter than the header prefix"))
        }
        if (bytes.size > MAX_VAULT_BYTES) {
            return Outcome.Failure(VaultError.Corrupt("file is larger than a vault can be"))
        }
        if (bytes.decodeToString(0, MAGIC_BYTES) != MAGIC) {
            return Outcome.Failure(VaultError.Corrupt("magic bytes do not identify a vault"))
        }
        val headerLength = readUInt32(bytes, MAGIC_BYTES + VERSION_BYTES)
        return if (headerLength > MAX_HEADER_BYTES.toUInt() ||
            PREFIX_BYTES.toUInt() + headerLength > bytes.size.toUInt()
        ) {
            Outcome.Failure(VaultError.Corrupt("header length does not fit the file"))
        } else {
            Outcome.Success(headerLength.toInt())
        }
    }

    // The checksum runs before any key is derived, so damage is reported as damage rather than as a
    // wrong password.
    private fun sliceEnvelope(bytes: ByteArray, headerLength: Int): Outcome<Envelope, VaultError> {
        val headerEnd = PREFIX_BYTES + headerLength
        val headerJson = bytes.copyOfRange(PREFIX_BYTES, headerEnd)
        if (headerChecksum(bytes, headerJson) != readUInt32(bytes, CRC_OFFSET)) {
            return Outcome.Failure(VaultError.Corrupt("header checksum does not match the header"))
        }
        // After the checksum, so a damaged byte is damage rather than an upgrade that does not
        // exist. Offset 5 is unsigned.
        val version = bytes[MAGIC_BYTES].toUByte().toInt()
        if (version != FORMAT_VERSION) {
            return Outcome.Failure(VaultError.UnsupportedVersion(version, FORMAT_VERSION))
        }
        val header = try {
            vaultJson.decodeFromString<VaultHeader>(headerJson.decodeToString())
        } catch (e: SerializationException) {
            return Outcome.Failure(VaultError.Corrupt("header is not valid JSON: ${e.message}"))
        }
        if (header.v != HEADER_VERSION) {
            return Outcome.Failure(VaultError.UnsupportedVersion(header.v, HEADER_VERSION))
        }
        // The prefix is kept verbatim off disk for use as the body's associated data. Re-serialising
        // the header would make decryption depend on the serialiser never changing its output.
        return Outcome.Success(
            Envelope(
                header = header,
                prefix = bytes.copyOfRange(0, headerEnd),
                ciphertext = bytes.copyOfRange(headerEnd, bytes.size),
            ),
        )
    }

    private fun unlock(envelope: Envelope, password: CharArray): Outcome<OpenVault, VaultError> {
        val header = envelope.header
        val fields = decodeHeaderFields(header)
            ?: return Outcome.Failure(VaultError.Corrupt("a header field is missing, malformed or the wrong size"))
        val dek = withKek(password, fields.salt) { kek ->
            aeadOpen(kek, fields.wrapNonce, fields.wrapCiphertext, NO_ASSOCIATED_DATA)
        } ?: return Outcome.Failure(VaultError.WrongPassword)
        // Ownership passes to the OpenVault only on success; until then the DEK is zeroed on every
        // way out, including a throw from the decrypt.
        var owned = false
        try {
            val plaintext = aeadOpen(dek, fields.bodyNonce, envelope.ciphertext, envelope.prefix)
                ?: return Outcome.Failure(VaultError.IntegrityFailure)
            return when (val decoded = decodeBody(plaintext)) {
                is Outcome.Failure -> decoded

                is Outcome.Success -> {
                    owned = true
                    Outcome.Success(OpenVault(decoded.value, header, SecureBytes.adopt(dek)))
                }
            }
        } finally {
            if (!owned) dek.fill(0)
        }
    }

    private fun decodeBody(plaintext: ByteArray): Outcome<VaultBody, VaultError> {
        val body = try {
            vaultJson.decodeFromString<VaultBody>(plaintext.decodeToString())
        } catch (e: SerializationException) {
            return Outcome.Failure(VaultError.Corrupt("body is not valid JSON: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            return Outcome.Failure(VaultError.Corrupt("body holds an invalid value: ${e.message}"))
        }
        return if (body.v != BODY_VERSION) {
            Outcome.Failure(VaultError.UnsupportedVersion(body.v, BODY_VERSION))
        } else {
            Outcome.Success(body)
        }
    }
}

private class Envelope(val header: VaultHeader, val prefix: ByteArray, val ciphertext: ByteArray)

private class HeaderFields(
    val salt: ByteArray,
    val wrapNonce: ByteArray,
    val wrapCiphertext: ByteArray,
    val bodyNonce: ByteArray,
)

// Every field here is plaintext and attacker-writable, and the primitives reject a wrong length by
// throwing, which would escape a function whose contract is that failure is a returned value.
private fun decodeHeaderFields(header: VaultHeader): HeaderFields? {
    val salt = base64Decode(header.salt)?.takeIf { it.size == ARGON2_SALT_BYTES } ?: return null
    val wrapNonce = base64Decode(header.wrap.nonce)?.takeIf { it.size == AEAD_NONCE_BYTES } ?: return null
    val wrapCiphertext = base64Decode(header.wrap.ct)
        ?.takeIf { it.size == AEAD_KEY_BYTES + AEAD_TAG_BYTES } ?: return null
    val bodyNonce = base64Decode(header.body.nonce)?.takeIf { it.size == AEAD_NONCE_BYTES } ?: return null
    return HeaderFields(salt, wrapNonce, wrapCiphertext, bodyNonce)
}

private fun writeUInt32(target: ByteArray, offset: Int, value: UInt) {
    for (index in 0 until LENGTH_BYTES) {
        target[offset + index] = (value shr ((LENGTH_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
    }
}

internal fun readUInt32(source: ByteArray, offset: Int): UInt {
    var value = 0u
    for (index in 0 until LENGTH_BYTES) {
        value = (value shl Byte.SIZE_BITS) or source[offset + index].toUByte().toUInt()
    }
    return value
}
