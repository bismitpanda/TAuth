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
import com.panda.tauth.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

internal const val VAULT_ID_BYTES = 16

private val NO_ASSOCIATED_DATA = ByteArray(0)

// Every path that finishes with this vault must call close(), error paths included: it zeroes the DEK.
class OpenVault internal constructor(
    val body: VaultBody,
    internal val header: VaultHeader,
    private val dek: SecureBytes,
) : AutoCloseable {
    val isClosed: Boolean get() = dek.isDestroyed

    // The key is lent for the length of a block and never handed out, so a close arriving mid-write
    // waits rather than zeroing the key that write is sealing under.
    internal fun <T> useDek(block: (ByteArray) -> T): T? = dek.lendOrNull(block)

    override fun close() = dek.destroy()

    override fun toString(): String = "OpenVault(entries=${body.entries.size}, closed=$isClosed)"
}

// Every nonce is drawn fresh from secureRandomBytes on every write, and no function in this file
// takes one as a parameter. Reusing a nonce with one key across two plaintexts breaks GCM completely.
object VaultCodec {
    fun create(password: CharArray, body: VaultBody = VaultBody()): Outcome<ByteArray, VaultError> {
        val salt = secureRandomBytes(ARGON2_SALT_BYTES)
        return withFreshDek { dek ->
            val header = VaultHeader(
                v = HEADER_VERSION,
                vaultId = base64Encode(secureRandomBytes(VAULT_ID_BYTES)),
                salt = base64Encode(salt),
                wrap = wrapDek(password, salt, dek),
                body = BodyBlock(""),
            )
            assemble(header, body, dek)
        }
    }

    fun open(bytes: ByteArray, password: CharArray): Outcome<OpenVault, VaultError> =
        readEnvelope(bytes).flatMap { envelope -> unlock(envelope, password) }

    fun encode(vault: OpenVault, body: VaultBody): Outcome<ByteArray, VaultError> =
        vault.useDek { dek -> assemble(vault.header, body, dek) } ?: Outcome.Failure(VaultError.VaultClosed)

    // The DEK is unchanged, so a leaked one survives a password change; rotateDek replaces it.
    fun changePassword(
        bytes: ByteArray,
        currentPassword: CharArray,
        newPassword: CharArray,
    ): Outcome<ByteArray, VaultError> = open(bytes, currentPassword).flatMap { vault ->
        vault.use {
            vault.useDek { dek ->
                val salt = secureRandomBytes(ARGON2_SALT_BYTES)
                val header = vault.header.copy(
                    salt = base64Encode(salt),
                    wrap = wrapDek(newPassword, salt, dek),
                )
                assemble(header, vault.body, dek)
            } ?: Outcome.Failure(VaultError.VaultClosed)
        }
    }

    fun rotateDek(bytes: ByteArray, password: CharArray): Outcome<ByteArray, VaultError> =
        open(bytes, password).flatMap { vault ->
            vault.use {
                val salt = base64Decode(vault.header.salt)
                    ?: return@use Outcome.Failure(VaultError.Corrupt("salt is not valid base64"))
                withFreshDek { fresh ->
                    val header = vault.header.copy(wrap = wrapDek(password, salt, fresh))
                    assemble(header, vault.body, fresh)
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

    // The fresh DEK is zeroed on every path out of the block, and internal so a test can watch that.
    internal inline fun <T> withFreshDek(block: (ByteArray) -> T): T {
        val dek = secureRandomBytes(AEAD_KEY_BYTES)
        return try {
            block(dek)
        } finally {
            dek.fill(0)
        }
    }

    // The DEK passes to the OpenVault built here and nowhere else; every other way out of the block
    // zeroes it, a refused body and a throw from the decrypt included. Internal so a test can watch.
    internal inline fun adoptedOrZeroed(
        dek: ByteArray,
        header: VaultHeader,
        block: () -> Outcome<VaultBody, VaultError>,
    ): Outcome<OpenVault, VaultError> {
        var adopted = false
        return try {
            block().map { body -> OpenVault(body, header, SecureBytes.adopt(dek)).also { adopted = true } }
        } finally {
            if (!adopted) dek.fill(0)
        }
    }

    // The versions and sizes checked here are the reader's own, so no file this returns fails a read on either.
    private fun assemble(header: VaultHeader, body: VaultBody, dek: ByteArray): Outcome<ByteArray, VaultError> {
        if (header.v != HEADER_VERSION) {
            return Outcome.Failure(VaultError.UnsupportedVersion(header.v, HEADER_VERSION))
        }
        if (body.v != BODY_VERSION) {
            return Outcome.Failure(VaultError.UnsupportedVersion(body.v, BODY_VERSION))
        }
        val bodyBytes = vaultJson.encodeToString(body.renumbered()).encodeToByteArray()
        val nonce = secureRandomBytes(AEAD_NONCE_BYTES)
        val prefix = prefixOf(header.copy(body = BodyBlock(base64Encode(nonce))))
        val headerLength = prefix.size - PREFIX_BYTES
        if (headerLength > MAX_HEADER_BYTES) {
            return Outcome.Failure(VaultError.TooLarge(headerLength, MAX_HEADER_BYTES))
        }
        val file = prefix + aeadSeal(dek, nonce, bodyBytes, prefix)
        return if (file.size > MAX_VAULT_BYTES) {
            Outcome.Failure(VaultError.TooLarge(file.size, MAX_VAULT_BYTES))
        } else {
            Outcome.Success(file)
        }
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

    // The checksum runs before any key is derived, so damage reads as damage, not as a wrong password.
    private fun sliceEnvelope(bytes: ByteArray, headerLength: Int): Outcome<Envelope, VaultError> {
        val headerEnd = PREFIX_BYTES + headerLength
        val headerJson = bytes.copyOfRange(PREFIX_BYTES, headerEnd)
        if (headerChecksum(bytes, headerJson) != readUInt32(bytes, CRC_OFFSET)) {
            return Outcome.Failure(VaultError.Corrupt("header checksum does not match the header"))
        }
        // After the checksum, so a damaged byte is damage and not a release that never shipped.
        val version = bytes[MAGIC_BYTES].toUByte().toInt()
        if (version != FORMAT_VERSION) {
            return Outcome.Failure(VaultError.UnsupportedVersion(version, FORMAT_VERSION))
        }
        val json = headerJson.decodeToString()
        unsupportedVersion(json, HEADER_VERSION)?.let { return Outcome.Failure(it) }
        val header = try {
            vaultJson.decodeFromString<VaultHeader>(json)
        } catch (_: SerializationException) {
            // The parser quotes the document it stopped in: here the salt and the wrapped DEK, all an
            // offline password search needs. The error reaches the log and the screen, so it carries none.
            return Outcome.Failure(VaultError.Corrupt("header is not valid JSON"))
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
        return adoptedOrZeroed(dek, header) {
            val plaintext = aeadOpen(dek, fields.bodyNonce, envelope.ciphertext, envelope.prefix)
                ?: return@adoptedOrZeroed Outcome.Failure(VaultError.IntegrityFailure)
            decodeBody(plaintext)
        }
    }

    private fun decodeBody(plaintext: ByteArray): Outcome<VaultBody, VaultError> {
        val json = plaintext.decodeToString()
        unsupportedVersion(json, BODY_VERSION)?.let { return Outcome.Failure(it) }
        return try {
            Outcome.Success(vaultJson.decodeFromString<VaultBody>(json))
        } catch (_: SerializationException) {
            // The parser quotes the document it stopped in: here the decrypted body, every entry's
            // secret. The error reaches the log and the screen, so it carries none of it.
            Outcome.Failure(VaultError.Corrupt("body is not valid JSON"))
        } catch (_: IllegalArgumentException) {
            // A refused value can arrive in the message, which is dropped for the same reason.
            Outcome.Failure(VaultError.Corrupt("body holds an invalid value"))
        }
    }
}

// A document's `v` is read on its own, ahead of the rest of it: a later version can give a field a
// type this model never used, and decoding under this model would report its vault as damage.
@Serializable
private class VersionTag(val v: Int? = null)

// A `v` absent or not a number is left to the decode that follows: required in a header, defaulted
// in a body.
private fun unsupportedVersion(json: String, supported: Int): VaultError.UnsupportedVersion? {
    val found = try {
        vaultJson.decodeFromString<VersionTag>(json).v
    } catch (_: SerializationException) {
        null
    }
    return if (found != null && found != supported) VaultError.UnsupportedVersion(found, supported) else null
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
