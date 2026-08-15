package com.panda.tauth.session

import com.panda.tauth.Outcome
import com.panda.tauth.crypto.SecureBytes
import com.panda.tauth.errorOrNull
import com.panda.tauth.totp.Base32
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.valueOrNull
import com.panda.tauth.vault.EntryEdit
import com.panda.tauth.vault.TEST_SECRET
import com.panda.tauth.vault.VaultBody
import com.panda.tauth.vault.VaultCodec
import com.panda.tauth.vault.VaultEntry
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultFile
import com.panda.tauth.vault.hotpEntry
import com.panda.tauth.vault.totpEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val PASSWORD = "correct horse battery staple"

// The bytes TEST_SECRET spells out, written here rather than decoded, so an assertion on a decoded
// key stands on RFC 4226 §5.1's published seed instead of on the decoder that produced it.
private const val SEED = "12345678901234567890"

private const val FIRST_ID = "0192f4c1-0000-7000-8000-000000000021"
private const val SECOND_ID = "0192f4c1-0000-7000-8000-000000000022"
private const val HOTP_ID = "0192f4c1-0000-7000-8000-000000000023"
private const val EXHAUSTED_ID = "0192f4c1-0000-7000-8000-000000000024"
private const val NEW_ID = "0192f4c1-0000-7000-8000-000000000025"
private const val ABSENT_ID = "0192f4c1-0000-7000-8000-000000000026"
private const val EIGHT_DIGIT_ID = "0192f4c1-0000-7000-8000-000000000027"
private const val SHA256_ID = "0192f4c1-0000-7000-8000-000000000028"

private const val ENTRY_COUNT = 6

// RFC 4226 Appendix D publishes the truncation before the modulus, so counter 0 over the seed below
// is 1284755224. Off the six-digit default, so an assumed digit count misses the published value.
private const val EIGHT_DIGITS = 8

// RFC 6238 errata 2866: Appendix B uses a distinct seed per algorithm, 32 bytes for SHA-256. Off the
// SHA-1 default, so an assumed algorithm misses the published value.
private val SHA256_SECRET = Base32.encode("12345678901234567890123456789012".encodeToByteArray())

// Appendix B's T for 1111111109 seconds at a 30-second period, which is the counter an hotp entry
// needs to stand where that vector stands.
private const val SHA256_COUNTER = 37037036uL

// The write refuses the way it does when another process holds the vault's lock file, which leaves
// the previous vault whole on disk.
private val WRITE_REFUSED = VaultError.LockedByAnotherProcess("/nowhere/vault.tauth.lock")

private val ZEROED_SEED = ByteArray(SEED.length)

// Everything an edit screen can send for the first entry, which is a totp one.
private val RENAME = EntryEdit(accountName = "erin", issuer = "Example", period = 30)

// One derivation for the whole class rather than one per test: Argon2id is priced to be slow.
private val VAULT by lazy {
    val body = VaultBody(
        entries = listOf(
            totpEntry(id = FIRST_ID),
            totpEntry(id = SECOND_ID, orderIndex = 1, accountName = "carol"),
            hotpEntry(id = HOTP_ID, counter = 0uL, orderIndex = 2),
            hotpEntry(id = EXHAUSTED_ID, counter = ULong.MAX_VALUE, orderIndex = 3),
            hotpEntry(id = EIGHT_DIGIT_ID, counter = 0uL, orderIndex = 4, digits = EIGHT_DIGITS),
            hotpEntry(
                id = SHA256_ID,
                counter = SHA256_COUNTER,
                orderIndex = 5,
                algorithm = HashAlgorithm.SHA256,
                digits = EIGHT_DIGITS,
            ).copy(secret = SHA256_SECRET),
        ),
    )
    checkNotNull(VaultCodec.create(PASSWORD.toCharArray(), body).valueOrNull)
}

private fun newEntry(id: String = NEW_ID) = totpEntry(id = id, accountName = "dave")

// The file the session writes through. A refused write keeps the previous contents, which is what
// the store's atomic rename leaves behind when a write does not commit.
private class ScriptedVaultFile(var contents: ByteArray?) : VaultFile {
    var writes = 0
    var refusal: VaultError? = null
    var onWrite: ((ByteArray) -> Unit)? = null

    // Runs inside the read, which is the one place a test can act while a derivation is in flight.
    var onRead: (() -> Unit)? = null

    override fun exists(): Boolean = contents != null

    override fun read(): Outcome<ByteArray, VaultError> {
        onRead?.invoke()
        return contents?.let { Outcome.Success(it) } ?: Outcome.Failure(VaultError.NoVaultFile)
    }

    override fun write(bytes: ByteArray): Outcome<Unit, VaultError> {
        writes++
        onWrite?.invoke(bytes)
        refusal?.let { return Outcome.Failure(it) }
        contents = bytes
        return Outcome.Success(Unit)
    }
}

private object UnwatchedClipboard : SessionClipboard {
    override fun clearIfHoldsOwnValue() = Unit
}

class EntryOperationsTest {
    private val file = ScriptedVaultFile(VAULT)

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val session = VaultSession(file, UnwatchedClipboard, scope)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    private fun unlocked() {
        assertIs<Outcome.Success<Unit>>(runBlocking { session.unlock(PASSWORD.toCharArray()) })
    }

    // What the file holds, read the way any other reader would: decrypted from the bytes on disk.
    private fun stored(bytes: ByteArray? = file.contents): List<VaultEntry> =
        checkNotNull(VaultCodec.open(checkNotNull(bytes), PASSWORD.toCharArray()).valueOrNull)
            .use { it.body.entries }

    private fun storedEntry(id: String, bytes: ByteArray? = file.contents): VaultEntry =
        stored(bytes).single { it.id == id }

    private fun published(): List<UnlockedEntry> {
        val state = session.state.value
        assertIs<SessionState.Unlocked>(state)
        return state.entries
    }

    private fun publishedEntry(id: String): UnlockedEntry = published().single { it.id == id }

    @Test
    fun `adding an entry writes the vault`() {
        unlocked()

        runBlocking { session.addEntry(newEntry()) }

        assertEquals(1, file.writes)
    }

    @Test
    fun `an added entry reaches the file`() {
        unlocked()

        runBlocking { session.addEntry(newEntry()) }

        assertEquals("dave", storedEntry(NEW_ID).accountName)
    }

    @Test
    fun `an added entry is published to the session`() {
        unlocked()

        runBlocking { session.addEntry(newEntry()) }

        assertEquals("dave", publishedEntry(NEW_ID).accountName)
    }

    @Test
    fun `an added entry lands after the entries already there`() {
        unlocked()

        runBlocking { session.addEntry(newEntry().copy(orderIndex = 0)) }

        assertEquals(ENTRY_COUNT, storedEntry(NEW_ID).orderIndex)
    }

    @Test
    fun `an added entry lends the key its base32 stands for`() {
        unlocked()

        runBlocking { session.addEntry(newEntry()) }

        assertContentEquals(SEED.encodeToByteArray(), session.withSecret(NEW_ID) { it.copyOf() })
    }

    @Test
    fun `an entry under an id the vault already holds is refused`() {
        unlocked()

        val outcome = runBlocking { session.addEntry(newEntry(id = FIRST_ID)) }

        assertIs<VaultError.InvalidEntry>(outcome.errorOrNull)
    }

    @Test
    fun `a refused add writes nothing`() {
        unlocked()

        runBlocking { session.addEntry(newEntry(id = FIRST_ID)) }

        assertEquals(0, file.writes)
    }

    @Test
    fun `an add whose write fails reports the write's own error`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        val outcome = runBlocking { session.addEntry(newEntry()) }

        assertEquals(WRITE_REFUSED, outcome.errorOrNull)
    }

    @Test
    fun `an add whose write fails publishes the entries the file still holds`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        runBlocking { session.addEntry(newEntry()) }

        assertEquals(ENTRY_COUNT, published().size)
    }

    @Test
    fun `an add whose write fails lends no key for the entry`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        runBlocking { session.addEntry(newEntry()) }

        assertNull(session.withSecret(NEW_ID) { it.size })
    }

    @Test
    fun `a key held for a write that does not commit is zeroed`() {
        unlocked()
        val key = SEED.encodeToByteArray()

        session.installedOrZeroed(NEW_ID, SecureBytes.adopt(key)) { Outcome.Failure(WRITE_REFUSED) }

        assertContentEquals(ZEROED_SEED, key)
    }

    @Test
    fun `a key held for a write that commits is installed`() {
        unlocked()

        session.installedOrZeroed(NEW_ID, SecureBytes.adopt(SEED.encodeToByteArray())) { Outcome.Success(Unit) }

        assertContentEquals(SEED.encodeToByteArray(), session.withSecret(NEW_ID) { it.copyOf() })
    }

    @Test
    fun `adding an entry to a locked vault reports the vault as closed`() {
        unlocked()
        session.lock(LockReason.Manual)

        val outcome = runBlocking { session.addEntry(newEntry()) }

        assertEquals(VaultError.VaultClosed, outcome.errorOrNull)
    }

    @Test
    fun `editing an entry stores the new account name`() {
        unlocked()

        runBlocking { session.editEntry(FIRST_ID, RENAME) }

        assertEquals("erin", storedEntry(FIRST_ID).accountName)
    }

    @Test
    fun `editing an entry publishes the new account name`() {
        unlocked()

        runBlocking { session.editEntry(FIRST_ID, RENAME) }

        assertEquals("erin", publishedEntry(FIRST_ID).accountName)
    }

    @Test
    fun `editing an entry leaves the secret the file holds`() {
        unlocked()

        runBlocking { session.editEntry(FIRST_ID, RENAME) }

        assertEquals(TEST_SECRET, storedEntry(FIRST_ID).secret)
    }

    @Test
    fun `editing an entry leaves the key the session lends for it`() {
        unlocked()

        runBlocking { session.editEntry(FIRST_ID, RENAME) }

        assertContentEquals(SEED.encodeToByteArray(), session.withSecret(FIRST_ID) { it.copyOf() })
    }

    @Test
    fun `editing a hotp counter stores the value a resynchronisation needs`() {
        unlocked()

        runBlocking { session.editEntry(HOTP_ID, EntryEdit(accountName = "bob", counter = 900uL)) }

        assertEquals(900uL, storedEntry(HOTP_ID).counter)
    }

    @Test
    fun `an edit naming a value the model refuses is refused`() {
        unlocked()

        val outcome = runBlocking { session.editEntry(FIRST_ID, RENAME.copy(digits = 9)) }

        assertIs<VaultError.InvalidEntry>(outcome.errorOrNull)
    }

    @Test
    fun `a refused edit writes nothing`() {
        unlocked()

        runBlocking { session.editEntry(FIRST_ID, RENAME.copy(digits = 9)) }

        assertEquals(0, file.writes)
    }

    @Test
    fun `editing an entry the vault does not hold reports no such entry`() {
        unlocked()

        val outcome = runBlocking { session.editEntry(ABSENT_ID, RENAME) }

        assertEquals(VaultError.NoSuchEntry, outcome.errorOrNull)
    }

    @Test
    fun `an edit whose write fails publishes the entry the file still holds`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        runBlocking { session.editEntry(FIRST_ID, RENAME) }

        assertEquals("alice", publishedEntry(FIRST_ID).accountName)
    }

    @Test
    fun `deleting an entry takes it out of the file`() {
        unlocked()

        runBlocking { session.deleteEntry(FIRST_ID) }

        assertEquals(emptyList(), stored().filter { it.id == FIRST_ID })
    }

    @Test
    fun `deleting an entry takes it out of the published list`() {
        unlocked()

        runBlocking { session.deleteEntry(FIRST_ID) }

        assertEquals(emptyList(), published().filter { it.id == FIRST_ID })
    }

    @Test
    fun `deleting an entry zeroes the key it decoded`() {
        unlocked()
        val key = checkNotNull(session.withSecret(FIRST_ID) { it })

        runBlocking { session.deleteEntry(FIRST_ID) }

        assertContentEquals(ZEROED_SEED, key)
    }

    @Test
    fun `deleting an entry closes the gap it leaves in the order`() {
        unlocked()

        runBlocking { session.deleteEntry(FIRST_ID) }

        assertEquals(listOf(0, 1, 2, 3, 4), stored().map { it.orderIndex }.sorted())
    }

    @Test
    fun `a delete publishes the order the file was numbered with`() {
        unlocked()

        runBlocking { session.deleteEntry(FIRST_ID) }

        assertEquals(listOf(0, 1, 2, 3, 4), published().map { it.orderIndex })
    }

    @Test
    fun `deleting an entry the vault does not hold reports no such entry`() {
        unlocked()

        val outcome = runBlocking { session.deleteEntry(ABSENT_ID) }

        assertEquals(VaultError.NoSuchEntry, outcome.errorOrNull)
    }

    @Test
    fun `a delete whose write fails keeps the key of the entry the file still holds`() {
        unlocked()
        val key = checkNotNull(session.withSecret(FIRST_ID) { it })
        file.refusal = WRITE_REFUSED

        runBlocking { session.deleteEntry(FIRST_ID) }

        assertContentEquals(SEED.encodeToByteArray(), key)
    }

    @Test
    fun `a delete whose write fails goes on lending that entry's key`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        runBlocking { session.deleteEntry(FIRST_ID) }

        assertContentEquals(SEED.encodeToByteArray(), session.withSecret(FIRST_ID) { it.copyOf() })
    }

    @Test
    fun `a delete whose write fails publishes the entries the file still holds`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        runBlocking { session.deleteEntry(FIRST_ID) }

        assertEquals(ENTRY_COUNT, published().size)
    }

    @Test
    fun `moving an entry to the front stores it first`() {
        unlocked()

        runBlocking { session.moveEntry(HOTP_ID, 0) }

        assertEquals(0, storedEntry(HOTP_ID).orderIndex)
    }

    @Test
    fun `moving an entry publishes the order it produced`() {
        unlocked()

        runBlocking { session.moveEntry(HOTP_ID, 0) }

        assertEquals(
            listOf(HOTP_ID, FIRST_ID, SECOND_ID, EXHAUSTED_ID, EIGHT_DIGIT_ID, SHA256_ID),
            published().map { it.id },
        )
    }

    @Test
    fun `moving an entry leaves the order numbered densely from zero`() {
        unlocked()

        runBlocking { session.moveEntry(FIRST_ID, 2) }

        assertEquals(listOf(0, 1, 2, 3, 4, 5), stored().map { it.orderIndex }.sorted())
    }

    @Test
    fun `a move before the front puts the entry first`() {
        unlocked()

        runBlocking { session.moveEntry(SECOND_ID, -1) }

        assertEquals(0, storedEntry(SECOND_ID).orderIndex)
    }

    @Test
    fun `a move past the end puts the entry last`() {
        unlocked()

        runBlocking { session.moveEntry(FIRST_ID, ENTRY_COUNT + 5) }

        assertEquals(ENTRY_COUNT - 1, storedEntry(FIRST_ID).orderIndex)
    }

    @Test
    fun `moving an entry the vault does not hold reports no such entry`() {
        unlocked()

        val outcome = runBlocking { session.moveEntry(ABSENT_ID, 0) }

        assertEquals(VaultError.NoSuchEntry, outcome.errorOrNull)
    }

    @Test
    fun `a move whose write fails publishes the order the file still holds`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        runBlocking { session.moveEntry(HOTP_ID, 0) }

        assertEquals(
            listOf(FIRST_ID, SECOND_ID, HOTP_ID, EXHAUSTED_ID, EIGHT_DIGIT_ID, SHA256_ID),
            published().map { it.id },
        )
    }

    @Test
    fun `generating a code returns the RFC 4226 code for the stored counter`() {
        unlocked()

        val outcome = runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals("755224", outcome.valueOrNull)
    }

    @Test
    fun `a code is generated to the digit count its own entry names`() {
        unlocked()

        val outcome = runBlocking { session.generateHotpCode(EIGHT_DIGIT_ID) }

        assertEquals("84755224", outcome.valueOrNull)
    }

    @Test
    fun `a code is generated under the algorithm its own entry names`() {
        unlocked()

        val outcome = runBlocking { session.generateHotpCode(SHA256_ID) }

        assertEquals("68084774", outcome.valueOrNull)
    }

    @Test
    fun `generating a code persists the counter it advanced to`() {
        unlocked()

        runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals(1uL, storedEntry(HOTP_ID).counter)
    }

    @Test
    fun `generating a code publishes the counter it advanced to`() {
        unlocked()

        runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals(1uL, publishedEntry(HOTP_ID).counter)
    }

    @Test
    fun `the write of a generated code already carries the advanced counter`() {
        unlocked()
        var duringWrite: ULong? = null
        file.onWrite = { bytes -> duringWrite = storedEntry(HOTP_ID, bytes).counter }

        runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals(1uL, duringWrite)
    }

    @Test
    fun `the counter the session publishes moves only once the file holds it`() {
        unlocked()
        var duringWrite: ULong? = null
        file.onWrite = { duringWrite = publishedEntry(HOTP_ID).counter }

        runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals(0uL, duringWrite)
    }

    @Test
    fun `a generation whose write fails yields no code`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        val outcome = runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals(WRITE_REFUSED, outcome.errorOrNull)
    }

    @Test
    fun `a generation whose write fails leaves the stored counter unchanged`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals(0uL, storedEntry(HOTP_ID).counter)
    }

    @Test
    fun `a generation whose write fails leaves the published counter unchanged`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals(0uL, publishedEntry(HOTP_ID).counter)
    }

    @Test
    fun `a second generation returns the RFC 4226 code for the counter behind it`() {
        unlocked()
        runBlocking { session.generateHotpCode(HOTP_ID) }

        val outcome = runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals("287082", outcome.valueOrNull)
    }

    @Test
    fun `a second generation advances the counter again`() {
        unlocked()
        runBlocking { session.generateHotpCode(HOTP_ID) }

        runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals(2uL, storedEntry(HOTP_ID).counter)
    }

    @Test
    fun `a counter survives a lock and an unlock`() {
        unlocked()
        runBlocking { session.generateHotpCode(HOTP_ID) }
        session.lock(LockReason.Manual)

        unlocked()

        assertEquals(1uL, publishedEntry(HOTP_ID).counter)
    }

    @Test
    fun `a counter at its maximum is refused rather than wrapped`() {
        unlocked()

        val outcome = runBlocking { session.generateHotpCode(EXHAUSTED_ID) }

        assertIs<VaultError.InvalidEntry>(outcome.errorOrNull)
    }

    @Test
    fun `a counter at its maximum stays where it is`() {
        unlocked()

        runBlocking { session.generateHotpCode(EXHAUSTED_ID) }

        assertEquals(ULong.MAX_VALUE, storedEntry(EXHAUSTED_ID).counter)
    }

    @Test
    fun `a refused generation writes nothing`() {
        unlocked()

        runBlocking { session.generateHotpCode(EXHAUSTED_ID) }

        assertEquals(0, file.writes)
    }

    @Test
    fun `generating a code for a totp entry is refused`() {
        unlocked()

        val outcome = runBlocking { session.generateHotpCode(FIRST_ID) }

        assertIs<VaultError.InvalidEntry>(outcome.errorOrNull)
    }

    @Test
    fun `generating a code for an entry the vault does not hold reports no such entry`() {
        unlocked()

        val outcome = runBlocking { session.generateHotpCode(ABSENT_ID) }

        assertEquals(VaultError.NoSuchEntry, outcome.errorOrNull)
    }

    @Test
    fun `generating a code against a locked vault reports the vault as closed`() {
        unlocked()
        session.lock(LockReason.Manual)

        val outcome = runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals(VaultError.VaultClosed, outcome.errorOrNull)
    }

    @Test
    fun `a lock arriving during a write waits for the write to finish`() {
        unlocked()

        val outcome = lockedMidWrite()

        assertEquals("755224", outcome.valueOrNull)
    }

    @Test
    fun `a write a lock arrived during still commits`() {
        unlocked()

        lockedMidWrite()

        assertEquals(1uL, storedEntry(HOTP_ID).counter)
    }

    @Test
    fun `a lock arriving during a write takes effect after it`() {
        unlocked()

        lockedMidWrite()

        assertIs<SessionState.Locked>(session.state.value)
    }

    @Test
    fun `an entry added during a derivation survives the unlock that was running`() {
        unlocked()
        var adding: Thread? = null
        // Started inside the read, the one point a test can act mid-derivation. An add running
        // alongside would reach the file and then be dropped by the install of the body just read.
        file.onRead = {
            adding = Thread { runBlocking { session.addEntry(newEntry()) } }.also { it.start() }
        }

        runBlocking { session.unlock(PASSWORD.toCharArray()) }
        adding?.join()

        assertEquals("dave", publishedEntry(NEW_ID).accountName)
    }

    // The lock runs on a thread of its own, started while the write holds the guard and joined once
    // the operation has returned, so it can take effect only after the write it arrived during.
    private fun lockedMidWrite(): Outcome<String, VaultError> {
        var locking: Thread? = null
        file.onWrite = {
            locking = Thread { session.lock(LockReason.Manual) }.also { it.start() }
        }
        val outcome = runBlocking { session.generateHotpCode(HOTP_ID) }
        locking?.join()
        return outcome
    }
}
