package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.crypto.AEAD_KEY_BYTES
import com.panda.tauth.crypto.ARGON2_SALT_BYTES
import com.panda.tauth.crypto.aeadSeal
import com.panda.tauth.crypto.base64Decode
import com.panda.tauth.crypto.base64Encode
import com.panda.tauth.crypto.crc32
import com.panda.tauth.crypto.secureRandomBytes
import com.panda.tauth.errorOrNull
import com.panda.tauth.settings.SecurityPolicy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct horse battery staple"

private fun password() = PASSWORD.toCharArray()

private fun newVault(body: VaultBody = VaultBody()) = VaultCodec.create(password(), body)

// Takes a block so the DEK is zeroed when the test finishes with it, on the failure path too.
private fun <T> opened(bytes: ByteArray, secret: CharArray = password(), block: (OpenVault) -> T): T {
    val outcome = VaultCodec.open(bytes, secret)
    assertIs<Outcome.Success<OpenVault>>(outcome)
    return outcome.value.use(block)
}

private fun flipBit(bytes: ByteArray, index: Int): ByteArray {
    val damaged = bytes.copyOf()
    damaged[index] = (damaged[index].toInt() xor 1).toByte()
    return damaged
}

// A file a later TAuth would have written: the version byte says something else and the checksum
// agrees with it, which is what tells a version this reader cannot read from a damaged one.
private fun withFormatVersion(bytes: ByteArray, version: Int): ByteArray {
    val rewritten = bytes.copyOf()
    rewritten[MAGIC_BYTES] = version.toByte()
    val headerLength = readUInt32(rewritten, MAGIC_BYTES + VERSION_BYTES).toInt()
    val checksummed = rewritten.copyOfRange(0, CRC_OFFSET) +
        rewritten.copyOfRange(PREFIX_BYTES, PREFIX_BYTES + headerLength)
    val checksum = crc32(checksummed)
    for (index in 0 until CRC_BYTES) {
        rewritten[CRC_OFFSET + index] = (checksum shr ((CRC_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
    }
    return rewritten
}

private const val EMPTY_BODY = """{"v":1,"entries":[]}"""

private fun headerOf(bytes: ByteArray): VaultHeader = vaultJson.decodeFromString(headerJsonOf(bytes))

private fun headerJsonOf(bytes: ByteArray): String {
    val length = readUInt32(bytes, MAGIC_BYTES + VERSION_BYTES).toInt()
    return bytes.copyOfRange(PREFIX_BYTES, PREFIX_BYTES + length).decodeToString()
}

class VaultCodecTest {
    @Test
    fun `a new vault opens with the password it was created under`() {
        assertTrue(opened(newVault()) { it.body.entries.isEmpty() })
    }

    @Test
    fun `a new vault starts with the default policy`() {
        assertEquals(SecurityPolicy(), opened(newVault()) { it.body.policy })
    }

    @Test
    fun `the file begins with the TAUTH magic`() {
        assertEquals("TAUTH", newVault().copyOfRange(0, 5).decodeToString())
    }

    @Test
    fun `the file carries format version 1`() {
        assertEquals(1, newVault()[5].toInt())
    }

    @Test
    fun `several hundred entries round-trip`() {
        val entries = List(300) { totpEntry(id = "id-$it", orderIndex = it, accountName = "user$it") }
        val ids = opened(newVault(VaultBody(entries = entries))) { vault -> vault.body.entries.map { it.id } }
        assertEquals(entries.map { it.id }, ids)
    }

    @Test
    fun `a hotp counter survives a round trip`() {
        val body = VaultBody(entries = listOf(hotpEntry(counter = 9_000_000_000_000_000_000uL)))
        assertEquals(9_000_000_000_000_000_000uL, opened(newVault(body)) { it.body.entries.single().counter })
    }

    @Test
    fun `a policy edit round-trips through a write and a read`() {
        val policy = SecurityPolicy(idleTimeoutMinutes = 1, hideGraceSeconds = 30)
        assertEquals(policy, opened(newVault(VaultBody(policy = policy))) { it.body.policy })
    }

    @Test
    fun `order indexes are renumbered densely on write`() {
        val body =
            VaultBody(entries = listOf(totpEntry(id = "a", orderIndex = 7), totpEntry(id = "b", orderIndex = 30)))
        assertEquals(listOf(0, 1), opened(newVault(body)) { vault -> vault.body.entries.map { it.orderIndex } })
    }

    @Test
    fun `a wrong password fails at the unwrap`() {
        assertEquals(VaultError.WrongPassword, VaultCodec.open(newVault(), "wrong".toCharArray()).errorOrNull)
    }

    @Test
    fun `a flipped bit in the ciphertext is an integrity failure`() {
        val bytes = newVault(VaultBody(entries = listOf(totpEntry())))
        assertEquals(
            VaultError.IntegrityFailure,
            VaultCodec.open(flipBit(bytes, bytes.size - 20), password()).errorOrNull,
        )
    }

    @Test
    fun `a flipped bit in the GCM tag is an integrity failure`() {
        val bytes = newVault()
        assertEquals(
            VaultError.IntegrityFailure,
            VaultCodec.open(flipBit(bytes, bytes.size - 1), password()).errorOrNull,
        )
    }

    @Test
    fun `a flipped bit in the header checksum itself is caught`() {
        assertIs<VaultError.Corrupt>(VaultCodec.open(flipBit(newVault(), 10), password()).errorOrNull)
    }

    @Test
    fun `damage to the header never reports a wrong password`() {
        // Damage must never be reported as a wrong password. Swept over every byte, not sampled.
        val bytes = newVault()
        val headerEnd = PREFIX_BYTES + readUInt32(bytes, MAGIC_BYTES + VERSION_BYTES).toInt()
        for (offset in PREFIX_BYTES until headerEnd) {
            val error = VaultCodec.open(flipBit(bytes, offset), password()).errorOrNull
            assertNotEquals(VaultError.WrongPassword, error, "offset $offset")
        }
    }

    @Test
    fun `every byte of the header is covered by the checksum`() {
        val bytes = newVault()
        val headerEnd = PREFIX_BYTES + readUInt32(bytes, MAGIC_BYTES + VERSION_BYTES).toInt()
        for (offset in PREFIX_BYTES until headerEnd) {
            assertIs<VaultError.Corrupt>(
                VaultCodec.open(flipBit(bytes, offset), password()).errorOrNull,
                "offset $offset",
            )
        }
    }

    @Test
    fun `a modified header length is corrupt rather than a silent success`() {
        val bytes = newVault()
        val damaged = bytes.copyOf()
        damaged[9] = (damaged[9].toInt() + 1).toByte()
        assertIs<VaultError.Corrupt>(VaultCodec.open(damaged, password()).errorOrNull)
    }

    @Test
    fun `a header length beyond the file is corrupt`() {
        val bytes = newVault()
        val damaged = bytes.copyOf()
        damaged[6] = 0x7F
        assertIs<VaultError.Corrupt>(VaultCodec.open(damaged, password()).errorOrNull)
    }

    @Test
    fun `a truncated file is corrupt`() {
        val bytes = newVault()
        assertIs<VaultError.Corrupt>(VaultCodec.open(bytes.copyOf(bytes.size / 2), password()).errorOrNull)
    }

    @Test
    fun `a file shorter than the prefix is corrupt`() {
        assertIs<VaultError.Corrupt>(VaultCodec.open(ByteArray(4), password()).errorOrNull)
    }

    @Test
    fun `wrong magic bytes are corrupt`() {
        val bytes = newVault()
        bytes[0] = 'X'.code.toByte()
        assertIs<VaultError.Corrupt>(VaultCodec.open(bytes, password()).errorOrNull)
    }

    @Test
    fun `a future format version is unsupported`() {
        val bytes = withFormatVersion(newVault(), 9)
        assertEquals(VaultError.UnsupportedVersion(9, FORMAT_VERSION), VaultCodec.open(bytes, password()).errorOrNull)
    }

    @Test
    fun `a format version above 127 is reported as the unsigned byte`() {
        val bytes = withFormatVersion(newVault(), 0xFF)
        assertEquals(VaultError.UnsupportedVersion(255, 1), VaultCodec.open(bytes, password()).errorOrNull)
    }

    @Test
    fun `a damaged version byte is corrupt rather than a version this reader lacks`() {
        // The reader has nothing to upgrade to, so naming a version the writer never wrote sends the
        // user looking for a TAuth that does not exist instead of at their damaged file.
        val bytes = newVault()
        bytes[MAGIC_BYTES] = 9
        assertIs<VaultError.Corrupt>(VaultCodec.open(bytes, password()).errorOrNull)
    }

    @Test
    fun `every byte ahead of the checksum is covered by it`() {
        val bytes = newVault()
        for (offset in 0 until CRC_OFFSET) {
            val error = VaultCodec.open(flipBit(bytes, offset), password()).errorOrNull
            assertIs<VaultError.Corrupt>(error, "offset $offset")
        }
    }

    @Test
    fun `a header carrying an unknown key still opens`() {
        val bytes = opened(newVault()) { withExtraHeaderKey(it) }
        assertIs<Outcome.Success<OpenVault>>(VaultCodec.open(bytes, password()))
    }

    @Test
    fun `two writes of identical content produce different ciphertext`() {
        val body = VaultBody(entries = listOf(totpEntry()))
        assertFalse(newVault(body).contentEquals(newVault(body)))
    }

    @Test
    fun `re-encoding an open vault changes the body nonce`() {
        opened(newVault()) { vault ->
            val first = VaultCodec.encode(vault, vault.body)
            val second = VaultCodec.encode(vault, vault.body)
            assertNotEquals(
                vaultJson.decodeFromString<VaultHeader>(headerJsonOf(first)).body.nonce,
                vaultJson.decodeFromString<VaultHeader>(headerJsonOf(second)).body.nonce,
            )
        }
    }

    @Test
    fun `a re-encoded vault opens with the same password`() {
        val reencoded = opened(newVault()) { vault ->
            VaultCodec.encode(vault, VaultBody(entries = listOf(totpEntry())))
        }
        assertEquals(1, opened(reencoded) { it.body.entries.size })
    }

    @Test
    fun `re-encoding keeps the vault id`() {
        val original = newVault()
        val vaultId = opened(original) { it.header.vaultId }
        val reencoded = opened(original) { VaultCodec.encode(it, it.body) }
        assertEquals(vaultId, opened(reencoded) { it.header.vaultId })
    }

    @Test
    fun `closing an open vault zeroes the key it held`() {
        val outcome = VaultCodec.open(newVault(), password())
        assertIs<Outcome.Success<OpenVault>>(outcome)
        val vault = outcome.value
        val key = vault.dekBytes()
        vault.close()
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test
    fun `the open vault toString does not leak the entry contents`() {
        val body = VaultBody(entries = listOf(totpEntry()))
        assertFalse(opened(newVault(body)) { TEST_SECRET in it.toString() })
    }

    @Test
    fun `a body holding an invalid entry is corrupt rather than an exception`() {
        // A hotp entry with no counter, written past the model by hand.
        val body = """{"v":1,"entries":[{"id":"a","type":"hotp","accountName":"b","secret":"$TEST_SECRET",
            "createdAt":"2026-08-13T09:41:12Z"}]}"""
        val bytes = opened(newVault()) { rewriteBody(it, body) }
        assertIs<VaultError.Corrupt>(VaultCodec.open(bytes, password()).errorOrNull)
    }

    @Test
    fun `two vaults created in a row hold different keys`() {
        // A key that came from anywhere but the CSPRNG — a constant, a counter, a seeded generator —
        // would repeat here, and every vault on earth would share it.
        val first = opened(newVault()) { it.dekBytes().toList() }
        val second = opened(newVault()) { it.dekBytes().toList() }
        assertNotEquals(first, second)
    }

    @Test
    fun `two vaults created in a row carry different salts`() {
        // A shared salt makes one precomputed table serve every TAuth vault.
        assertNotEquals(opened(newVault()) { it.header.salt }, opened(newVault()) { it.header.salt })
    }

    @Test
    fun `the salt is 16 bytes`() {
        assertEquals(ARGON2_SALT_BYTES, opened(newVault()) { base64Decode(it.header.salt)?.size })
    }

    @Test
    fun `two vaults created in a row carry different wrap nonces`() {
        assertNotEquals(opened(newVault()) { it.header.wrap.nonce }, opened(newVault()) { it.header.wrap.nonce })
    }

    @Test
    fun `rotating the key rewraps under a nonce the old wrap never used`() {
        // Rotation keeps the password and its salt, so the KEK is the one that wrapped the previous
        // key. A repeated nonce under that KEK is keystream reuse, which hands an attacker the XOR
        // of the two keys it wrapped.
        val original = newVault()
        val rotated = VaultCodec.rotateDek(original, password())
        assertIs<Outcome.Success<ByteArray>>(rotated)
        assertNotEquals(headerOf(original).wrap.nonce, headerOf(rotated.value).wrap.nonce)
    }

    @Test
    fun `rotating the key leaves the salt alone`() {
        val original = newVault()
        val rotated = VaultCodec.rotateDek(original, password())
        assertIs<Outcome.Success<ByteArray>>(rotated)
        assertEquals(headerOf(original).salt, headerOf(rotated.value).salt)
    }

    @Test
    fun `changing the password draws a new salt`() {
        val original = newVault()
        val changed = VaultCodec.changePassword(original, password(), "next".toCharArray())
        assertIs<Outcome.Success<ByteArray>>(changed)
        assertNotEquals(headerOf(original).salt, headerOf(changed.value).salt)
    }

    @Test
    fun `a header version this reader does not know is unsupported`() {
        val bytes = opened(newVault()) { vault ->
            seal(vault, { body -> vaultJson.encodeToString(vault.header.copy(v = 2, body = body)) }, EMPTY_BODY)
        }
        assertEquals(VaultError.UnsupportedVersion(2, HEADER_VERSION), VaultCodec.open(bytes, password()).errorOrNull)
    }

    @Test
    fun `a body version this reader does not know is unsupported`() {
        val bytes = opened(newVault()) { rewriteBody(it, """{"v":2,"entries":[]}""") }
        assertEquals(VaultError.UnsupportedVersion(2, BODY_VERSION), VaultCodec.open(bytes, password()).errorOrNull)
    }

    @Test
    fun `a header length with the high bit set is corrupt rather than a negative slice`() {
        // Read as an unsigned 32-bit value. Read as a signed Int it is negative, which passes a
        // "fits the file" test written with the wrong type and then slices backwards.
        val bytes = newVault()
        for (index in 0 until LENGTH_BYTES) {
            bytes[MAGIC_BYTES + VERSION_BYTES + index] = 0xFF.toByte()
        }
        assertIs<VaultError.Corrupt>(VaultCodec.open(bytes, password()).errorOrNull)
    }

    @Test
    fun `a file one byte shorter than the prefix is corrupt`() {
        assertIs<VaultError.Corrupt>(VaultCodec.open(ByteArray(PREFIX_BYTES - 1), password()).errorOrNull)
    }

    @Test
    fun `the KEK is zeroed once the caller is done with it`() {
        // Every other zeroing in this file is of a local the test cannot reach; this one runs the
        // block the KEK is lent to, so the array it was given can be checked after the lending ends.
        var lent = ByteArray(0)
        VaultCodec.withKek(password(), ByteArray(ARGON2_SALT_BYTES) { 0x07 }) { kek -> lent = kek }
        assertContentEquals(ByteArray(AEAD_KEY_BYTES), lent)
    }

    @Test
    fun `a body holding a negative policy duration is corrupt rather than an exception`() {
        val body = """{"v":1,"entries":[],"policy":{"idleTimeoutMinutes":-1}}"""
        val bytes = opened(newVault()) { rewriteBody(it, body) }
        assertIs<VaultError.Corrupt>(VaultCodec.open(bytes, password()).errorOrNull)
    }

    @Test
    fun `an empty salt is corrupt rather than a thrown exception`() {
        // Every field below is attacker-writable with a recomputed checksum and reaches a primitive
        // that rejects it with an unchecked exception. open() promises the failure is a value.
        assertIs<VaultError.Corrupt>(openTampered("salt", "").errorOrNull)
    }

    @Test
    fun `a salt of the wrong length is corrupt`() {
        assertIs<VaultError.Corrupt>(openTampered("salt", "AAAA").errorOrNull)
    }

    @Test
    fun `a wrap nonce of the wrong length is corrupt`() {
        assertIs<VaultError.Corrupt>(openTampered("nonce", "AAAA").errorOrNull)
    }

    @Test
    fun `a wrapped key of the wrong length is corrupt`() {
        assertIs<VaultError.Corrupt>(openTampered("ct", "AAAA").errorOrNull)
    }

    @Test
    fun `a header field of the wrong length never throws`() {
        // Swept rather than sampled: every field, every length either side of the one required.
        for (field in listOf("salt", "nonce", "ct", "vaultId")) {
            for (value in listOf("", "AAAA", "AA", base64Encode(ByteArray(100)))) {
                val outcome = runCatching { VaultCodec.open(tampered(field, value), password()) }
                assertTrue(outcome.isSuccess, "$field=$value threw ${outcome.exceptionOrNull()}")
            }
        }
    }

    @Test
    fun `the header carries no derivation parameters`() {
        // Which function, which version, how many lanes and at what cost are all implied by the
        // format version; a stored copy could only ever disagree with it.
        val header = headerJsonOf(newVault())
        assertFalse("memoryKib" in header || "iterations" in header || "algo" in header)
    }

    @Test
    fun `the wrapped key is 48 bytes`() {
        // 32 bytes of DEK plus the 16-byte tag.
        assertEquals(48, opened(newVault()) { base64Decode(it.header.wrap.ct)?.size })
    }

    @Test
    fun `the vault id is 16 bytes`() {
        assertEquals(16, opened(newVault()) { base64Decode(it.header.vaultId)?.size })
    }

    @Test
    fun `the body nonce is 12 bytes`() {
        assertEquals(12, opened(newVault()) { base64Decode(it.header.body.nonce)?.size })
    }

    @Test
    fun `a vault created with a blank password still opens with it`() {
        val bytes = VaultCodec.create(CharArray(0), VaultBody())
        assertTrue(opened(bytes, CharArray(0)) { it.body.entries.isEmpty() })
    }

    @Test
    fun `the plaintext secret does not appear anywhere in the file`() {
        val bytes = newVault(VaultBody(entries = listOf(totpEntry())))
        assertFalse(containsSubsequence(bytes, TEST_SECRET.encodeToByteArray()))
    }

    @Test
    fun `the account name does not appear anywhere in the file`() {
        val bytes = newVault(VaultBody(entries = listOf(totpEntry(accountName = "unmistakable-name"))))
        assertFalse(containsSubsequence(bytes, "unmistakable-name".encodeToByteArray()))
    }
}

// Rewrites one header field and repairs the checksum, so the read gets past the CRC and reaches the
// code that consumes the field.
private fun tampered(field: String, value: String): ByteArray {
    val original = newVault()
    val length = readUInt32(original, MAGIC_BYTES + VERSION_BYTES).toInt()
    val json = original.copyOfRange(PREFIX_BYTES, PREFIX_BYTES + length).decodeToString()
    val edited = Regex("\"$field\":\"[^\"]*\"").replace(json, "\"$field\":\"$value\"")
    check(edited != json) { "no $field field to rewrite" }
    return VaultCodec.prefixOf(edited.encodeToByteArray()) +
        original.copyOfRange(PREFIX_BYTES + length, original.size)
}

private fun openTampered(field: String, value: String) = VaultCodec.open(tampered(field, value), password())

// The same, for the one header field that is a number rather than base64 text.
private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean =
    (0..haystack.size - needle.size).any { start ->
        needle.indices.all { haystack[start + it] == needle[it] }
    }

// Builds a file the model would refuse to produce, using the codec's own prefix builder so the byte
// layout is never asserted against a second copy of itself. The header JSON is passed as text, so an
// extra key survives into the file instead of being dropped by a re-serialisation.
private fun seal(vault: OpenVault, headerJson: (BodyBlock) -> String, bodyJson: String): ByteArray {
    val nonce = secureRandomBytes(12)
    val prefix = VaultCodec.prefixOf(headerJson(BodyBlock(base64Encode(nonce))).encodeToByteArray())
    return prefix + aeadSeal(vault.dekBytes(), nonce, bodyJson.encodeToByteArray(), prefix)
}

private fun rewriteBody(vault: OpenVault, bodyJson: String): ByteArray =
    seal(vault, { vaultJson.encodeToString(vault.header.copy(body = it)) }, bodyJson)

private fun withExtraHeaderKey(vault: OpenVault): ByteArray = seal(
    vault,
    { vaultJson.encodeToString(vault.header.copy(body = it)).dropLast(1) + ""","futureField":true}""" },
    vaultJson.encodeToString(vault.body),
)
