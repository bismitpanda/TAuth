package com.panda.tauth.session

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.settings.SecurityPolicy
import com.panda.tauth.valueOrNull
import com.panda.tauth.vault.VaultBody
import com.panda.tauth.vault.VaultCodec
import com.panda.tauth.vault.VaultEntry
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultFile
import com.panda.tauth.vault.VaultReadError
import com.panda.tauth.vault.VaultWriteError
import com.panda.tauth.vault.hotpEntry
import com.panda.tauth.vault.totpEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PASSWORD = "correct horse battery staple"
private const val WRONG_PASSWORD = "correct horse battery stapld"
private const val NEW_PASSWORD = "a rather different passphrase"

// The bytes TEST_SECRET spells out. RFC 4226 §5.1.
private const val SEED = "12345678901234567890"

// RFC 4226 Appendix D publishes this as the six-digit code for counter 0 over the seed above.
private const val FIRST_HOTP_CODE = "755224"

private const val TOTP_ID = "0192f4c1-0000-7000-8000-000000000041"
private const val HOTP_ID = "0192f4c1-0000-7000-8000-000000000042"
private const val NEW_ID = "0192f4c1-0000-7000-8000-000000000043"

// A key of thirty-two zero bytes: what an AES-256 key array holds once it has been zeroed.
private val ZEROED_KEY = ByteArray(32)

// Every field off its default, so a policy the session reports from anywhere other than the body it
// wrote disagrees with this in every field rather than in none.
private val STORED_POLICY = SecurityPolicy(
    idleTimeoutMinutes = 1,
    lockOnMinimise = false,
    lockOnFocusLoss = true,
    hideGraceSeconds = 30,
    clipboardClearSeconds = 10,
)

// Every field differs from the one above, the grace period included, which is the field a scheduled
// lock reads back.
private val CHOSEN_POLICY = SecurityPolicy(
    idleTimeoutMinutes = 15,
    lockOnMinimise = true,
    lockOnFocusLoss = false,
    hideGraceSeconds = 45,
    clipboardClearSeconds = 60,
)

private val BODY = VaultBody(
    policy = STORED_POLICY,
    entries = listOf(totpEntry(id = TOTP_ID), hotpEntry(id = HOTP_ID, counter = 0uL, orderIndex = 1)),
)

// One derivation for the whole class rather than one per test: Argon2id is priced to be slow.
private val VAULT by lazy { checkNotNull(VaultCodec.create(PASSWORD.toCharArray(), BODY).valueOrNull) }

private fun newEntry() = totpEntry(id = NEW_ID, accountName = "dave")

// The write refuses the way it does when another process holds the vault's lock file, which leaves
// the previous vault whole on disk.
private val WRITE_REFUSED = VaultError.LockedByAnotherProcess("/nowhere/vault.tauth.lock")

private class SettingsVaultFile(var contents: ByteArray?) : VaultFile {
    var writes = 0
    var refusal: VaultWriteError? = null
    var onWrite: ((ByteArray) -> Unit)? = null

    override fun exists(): Boolean = contents != null

    override fun read(): Outcome<ByteArray, VaultReadError> =
        contents?.let { Outcome.Success(it) } ?: Outcome.Failure(VaultError.NoVaultFile)

    override fun write(bytes: ByteArray): Outcome<Unit, VaultWriteError> {
        writes++
        onWrite?.invoke(bytes)
        refusal?.let { return Outcome.Failure(it) }
        contents = bytes
        return Outcome.Success(Unit)
    }
}

private object SettingsClipboard : SessionClipboard {
    override fun clearIfHoldsOwnValue() = Unit
}

// No clock and no sleep: a scheduled wait records what it was asked for and never ends.
private class RecordingLockDelay : LockDelay {
    val requested = mutableListOf<Int>()

    override suspend fun elapse(seconds: Int) {
        requested += seconds
        CompletableDeferred<Unit>().await()
    }
}

class SettingsOperationsTest {
    private val file = SettingsVaultFile(VAULT)

    private val lockDelay = RecordingLockDelay()

    // Unconfined runs each resumption on the thread that causes it, so every assertion below sees a
    // settled session without joining anything.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val session = VaultSession(file, SettingsClipboard, scope, lockDelay)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    private fun unlocked() {
        assertIs<Outcome.Success<Unit>>(runBlocking { session.unlock(PASSWORD.toCharArray()) })
    }

    private fun changePassword(current: String = PASSWORD, next: String = NEW_PASSWORD) =
        runBlocking { session.changePassword(current.toCharArray(), next.toCharArray()) }

    private fun rotate(password: String = PASSWORD) = runBlocking { session.rotateDek(password.toCharArray()) }

    // The precondition of everything asserted after it: a rewrite that reported a failure has left
    // the session somewhere none of those assertions is about.
    private fun passwordChanged() {
        assertIs<Outcome.Success<Unit>>(changePassword())
    }

    private fun rotated() {
        assertIs<Outcome.Success<Unit>>(rotate())
    }

    private fun add() = runBlocking { session.addEntries(listOf(newEntry())) }

    // What the file holds, read the way any other reader would: decrypted from the bytes on disk.
    private fun stored(password: String = PASSWORD, bytes: ByteArray? = file.contents): VaultBody =
        checkNotNull(VaultCodec.open(checkNotNull(bytes), password.toCharArray()).valueOrNull).use { it.body }

    private fun storedEntries(password: String = PASSWORD): List<VaultEntry> = stored(password).entries

    private fun openedUnder(password: String) = VaultCodec.open(checkNotNull(file.contents), password.toCharArray())

    private fun dekOf(bytes: ByteArray?, password: String = PASSWORD): ByteArray =
        checkNotNull(VaultCodec.open(checkNotNull(bytes), password.toCharArray()).valueOrNull)
            .use { vault -> checkNotNull(vault.useDek { dek -> dek.copyOf() }) }

    private fun publishedPolicy(): SecurityPolicy {
        val state = session.state.value
        assertIs<SessionState.Unlocked>(state)
        return state.policy
    }

    // A rewrite that reported a failure over a file it had already written would have the screen tell
    // the user their master password is unchanged when it is the new one that opens the vault.
    @Test
    fun `a password change reports success`() {
        unlocked()

        val outcome = changePassword()

        assertIs<Outcome.Success<Unit>>(outcome)
    }

    @Test
    fun `a password change leaves the file opening under the new password`() {
        unlocked()

        passwordChanged()

        assertEquals(BODY.entries.map { it.id }, storedEntries(NEW_PASSWORD).map { it.id })
    }

    @Test
    fun `a password change leaves the old password refused by the file`() {
        unlocked()

        passwordChanged()

        assertEquals(VaultError.WrongPassword, openedUnder(PASSWORD).errorOrNull)
    }

    @Test
    fun `a password change leaves the session unlocked over the same entries`() {
        unlocked()

        passwordChanged()

        assertEquals(BODY.entries.map { it.id }, publishedEntryIds())
    }

    @Test
    fun `a password change leaves the policy the body carries`() {
        unlocked()

        passwordChanged()

        assertEquals(STORED_POLICY, publishedPolicy())
    }

    @Test
    fun `a password change leaves the session lending the key its base32 stands for`() {
        unlocked()

        passwordChanged()

        assertContentEquals(SEED.encodeToByteArray(), session.withSecret(TOTP_ID) { it.copyOf() })
    }

    // The header a password change writes is a new one, and the header an open vault holds is the
    // associated data of every write made through it.
    @Test
    fun `an entry added after a password change reaches the file the new password opens`() {
        unlocked()
        passwordChanged()

        add()

        assertEquals("dave", storedEntries(NEW_PASSWORD).single { it.id == NEW_ID }.accountName)
    }

    @Test
    fun `an entry added after a password change leaves the old password refused`() {
        unlocked()
        passwordChanged()

        add()

        assertEquals(VaultError.WrongPassword, openedUnder(PASSWORD).errorOrNull)
    }

    @Test
    fun `a wrong current password is refused`() {
        unlocked()

        val outcome = changePassword(current = WRONG_PASSWORD)

        assertEquals(VaultError.WrongPassword, outcome.errorOrNull)
    }

    @Test
    fun `a wrong current password writes nothing`() {
        unlocked()

        changePassword(current = WRONG_PASSWORD)

        assertEquals(0, file.writes)
    }

    @Test
    fun `a wrong current password leaves the published state where it was`() {
        unlocked()
        val before = session.state.value

        changePassword(current = WRONG_PASSWORD)

        assertEquals(before, session.state.value)
    }

    @Test
    fun `a wrong current password leaves the key the session holds live`() {
        unlocked()
        val dek = checkNotNull(session.useDek { it })

        changePassword(current = WRONG_PASSWORD)

        assertFalse(dek.contentEquals(ZEROED_KEY))
    }

    @Test
    fun `a wrong current password leaves the decoded keys lendable`() {
        unlocked()

        changePassword(current = WRONG_PASSWORD)

        assertContentEquals(SEED.encodeToByteArray(), session.withSecret(TOTP_ID) { it.copyOf() })
    }

    @Test
    fun `a password change against a locked vault reports the closed vault`() {
        val outcome = changePassword()

        assertEquals(VaultError.VaultClosed, outcome.errorOrNull)
    }

    @Test
    fun `a password change against a locked vault writes nothing`() {
        changePassword()

        assertEquals(0, file.writes)
    }

    @Test
    fun `a rotation reports success`() {
        unlocked()

        val outcome = rotate()

        assertIs<Outcome.Success<Unit>>(outcome)
    }

    @Test
    fun `a rotation leaves the file opening under the same password`() {
        unlocked()

        rotated()

        assertEquals(BODY.entries.map { it.id }, storedEntries().map { it.id })
    }

    @Test
    fun `a rotation replaces the key the body is encrypted under`() {
        unlocked()
        val before = dekOf(file.contents)

        rotated()

        assertFalse(before.contentEquals(dekOf(file.contents)))
    }

    @Test
    fun `a rotation leaves the session lending the key its base32 stands for`() {
        unlocked()

        rotated()

        assertContentEquals(SEED.encodeToByteArray(), session.withSecret(TOTP_ID) { it.copyOf() })
    }

    @Test
    fun `a rotation leaves an entry yielding the code its secret publishes`() {
        unlocked()
        rotated()

        val outcome = runBlocking { session.generateHotpCode(HOTP_ID) }

        assertEquals(FIRST_HOTP_CODE, outcome.valueOrNull)
    }

    @Test
    fun `a rotation leaves the policy the body carries`() {
        unlocked()

        rotated()

        assertEquals(STORED_POLICY, publishedPolicy())
    }

    @Test
    fun `an entry added after a rotation reaches the file`() {
        unlocked()
        rotated()

        add()

        assertEquals("dave", storedEntries().single { it.id == NEW_ID }.accountName)
    }

    // A session holding the vault it had before the rotation would seal the next body under the key
    // the rotation replaced, putting the file back where it was.
    @Test
    fun `an entry added after a rotation is written under the rotated key`() {
        unlocked()
        val before = dekOf(file.contents)
        rotated()

        add()

        assertFalse(before.contentEquals(dekOf(file.contents)))
    }

    @Test
    fun `a rotation under the wrong password is refused`() {
        unlocked()

        val outcome = rotate(password = WRONG_PASSWORD)

        assertEquals(VaultError.WrongPassword, outcome.errorOrNull)
    }

    @Test
    fun `a rotation under the wrong password writes nothing`() {
        unlocked()

        rotate(password = WRONG_PASSWORD)

        assertEquals(0, file.writes)
    }

    @Test
    fun `a rotation against a locked vault reports the closed vault`() {
        val outcome = rotate()

        assertEquals(VaultError.VaultClosed, outcome.errorOrNull)
    }

    @Test
    fun `a policy change reaches the file`() {
        unlocked()

        runBlocking { session.setPolicy(CHOSEN_POLICY) }

        assertEquals(CHOSEN_POLICY, stored().policy)
    }

    @Test
    fun `a policy change is published to the session`() {
        unlocked()

        runBlocking { session.setPolicy(CHOSEN_POLICY) }

        assertEquals(CHOSEN_POLICY, publishedPolicy())
    }

    // The grace period a schedule waits is read from the published policy, so this is the change
    // reaching the control it governs rather than only the file.
    @Test
    fun `a lock scheduled after a policy change waits the grace period it set`() {
        unlocked()
        runBlocking { session.setPolicy(CHOSEN_POLICY) }

        session.scheduleLock(LockReason.HiddenToTray)

        assertEquals(listOf(45), lockDelay.requested)
    }

    // The one point the two orders separate: the bytes going to the file already carry the policy
    // while the state the session publishes does not.
    @Test
    fun `the policy the session publishes moves only once the file holds it`() {
        unlocked()
        var duringWrite: SecurityPolicy? = null
        file.onWrite = { duringWrite = publishedPolicy() }

        runBlocking { session.setPolicy(CHOSEN_POLICY) }

        assertEquals(STORED_POLICY, duringWrite)
    }

    @Test
    fun `the write of a policy change already carries it`() {
        unlocked()
        var duringWrite: SecurityPolicy? = null
        file.onWrite = { bytes -> duringWrite = stored(bytes = bytes).policy }

        runBlocking { session.setPolicy(CHOSEN_POLICY) }

        assertEquals(CHOSEN_POLICY, duringWrite)
    }

    @Test
    fun `a policy change whose write fails leaves the published policy`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        runBlocking { session.setPolicy(CHOSEN_POLICY) }

        assertEquals(STORED_POLICY, publishedPolicy())
    }

    @Test
    fun `a policy change whose write fails reports the write's own error`() {
        unlocked()
        file.refusal = WRITE_REFUSED

        val outcome = runBlocking { session.setPolicy(CHOSEN_POLICY) }

        assertEquals(WRITE_REFUSED, outcome.errorOrNull)
    }

    @Test
    fun `a policy change against a locked vault reports the closed vault`() {
        val outcome = runBlocking { session.setPolicy(CHOSEN_POLICY) }

        assertEquals(VaultError.VaultClosed, outcome.errorOrNull)
    }

    @Test
    fun `an exported copy opens under the vault's own password`() {
        unlocked()

        val exported = runBlocking { session.exportEncrypted() }

        assertEquals(BODY.entries.map { it.id }, stored(bytes = exported.valueOrNull).entries.map { it.id })
    }

    @Test
    fun `an export carries an entry added before it`() {
        unlocked()
        add()

        val exported = runBlocking { session.exportEncrypted() }

        assertTrue(stored(bytes = exported.valueOrNull).entries.any { it.id == NEW_ID })
    }

    @Test
    fun `an export after a password change opens under the new password`() {
        unlocked()
        passwordChanged()

        val exported = runBlocking { session.exportEncrypted() }

        assertEquals(BODY.entries.map { it.id }, stored(NEW_PASSWORD, exported.valueOrNull).entries.map { it.id })
    }

    // The bytes are the vault's own ciphertext, so an export needs no open vault behind it.
    @Test
    fun `an export from a locked session hands over the vault the file holds`() {
        val exported = runBlocking { session.exportEncrypted() }

        assertEquals(BODY.entries.map { it.id }, stored(bytes = exported.valueOrNull).entries.map { it.id })
    }

    @Test
    fun `an export against no file reports the missing vault`() {
        file.contents = null

        val outcome = runBlocking { session.exportEncrypted() }

        assertEquals(VaultError.NoVaultFile, outcome.errorOrNull)
    }

    @Test
    fun `an export writes nothing`() {
        unlocked()

        runBlocking { session.exportEncrypted() }

        assertEquals(0, file.writes)
    }

    private fun publishedEntryIds(): List<String> {
        val state = session.state.value
        assertIs<SessionState.Unlocked>(state)
        return state.entries.map { it.id }
    }
}
