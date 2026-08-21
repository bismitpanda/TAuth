package com.panda.tauth.session

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.settings.SecurityPolicy
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpType
import com.panda.tauth.valueOrNull
import com.panda.tauth.vault.TEST_SECRET
import com.panda.tauth.vault.VaultBody
import com.panda.tauth.vault.VaultCodec
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
import kotlin.test.assertNull
import kotlin.time.Instant

private const val PASSWORD = "correct horse battery staple"

// The bytes TEST_SECRET spells out. RFC 4226 §5.1.
private const val SEED = "12345678901234567890"

private const val TOTP_ID = "0192f4c1-0000-7000-8000-000000000001"
private const val HOTP_ID = "0192f4c1-0000-7000-8000-000000000002"

// A key of thirty-two zero bytes: what an AES-256 key array holds once it has been zeroed.
private val ZEROED_KEY = ByteArray(32)

private val ZEROED_SEED = ByteArray(SEED.length)

private fun body(policy: SecurityPolicy) = VaultBody(
    policy = policy,
    entries = listOf(totpEntry(id = TOTP_ID), hotpEntry(id = HOTP_ID, counter = 5uL, orderIndex = 1)),
)

// One derivation per distinct vault rather than one per test: Argon2id is priced to be slow.
private fun vaultBytes(policy: SecurityPolicy) =
    checkNotNull(VaultCodec.create(PASSWORD.toCharArray(), body(policy)).valueOrNull)

private val IMMEDIATE_VAULT by lazy { vaultBytes(SecurityPolicy()) }

private val GRACE_VAULT by lazy { vaultBytes(SecurityPolicy(hideGraceSeconds = 30)) }

private val NO_MINIMISE_VAULT by lazy { vaultBytes(SecurityPolicy(lockOnMinimise = false)) }

// Two entries under one id, which the entry model permits and the session's map of decoded keys
// cannot hold.
private val DUPLICATE_ID_VAULT by lazy {
    val duplicated = VaultBody(entries = listOf(totpEntry(id = TOTP_ID), hotpEntry(id = TOTP_ID, orderIndex = 1)))
    checkNotNull(VaultCodec.create(PASSWORD.toCharArray(), duplicated).valueOrNull)
}

private class FakeVaultFile : VaultFile {
    var contents: ByteArray? = null
    var writes = 0

    // Runs inside the read, which is the one place a test can act while a derivation is in flight.
    var onRead: (() -> Unit)? = null

    override fun exists(): Boolean = contents != null

    override fun read(): Outcome<ByteArray, VaultReadError> {
        onRead?.invoke()
        return contents?.let { Outcome.Success(it) } ?: Outcome.Failure(VaultError.NoVaultFile)
    }

    override fun write(bytes: ByteArray): Outcome<Unit, VaultWriteError> {
        contents = bytes
        writes++
        return Outcome.Success(Unit)
    }
}

private class FakeClipboard : SessionClipboard {
    var clears = 0

    override fun clearIfHoldsOwnValue() {
        clears++
    }
}

// No clock and no sleep: a scheduled wait ends when the test opens its gate, and never otherwise.
private class FakeLockDelay : LockDelay {
    val requested = mutableListOf<Int>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    override suspend fun elapse(seconds: Int) {
        val gate = CompletableDeferred<Unit>()
        requested += seconds
        gates += gate
        gate.await()
    }

    fun finish(index: Int) {
        gates[index].complete(Unit)
    }
}

class VaultSessionTest {
    private val file = FakeVaultFile()
    private val clipboard = FakeClipboard()
    private val lockDelay = FakeLockDelay()

    // Unconfined runs each resumption on the thread that causes it, so every assertion below sees a
    // settled session without joining anything.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    private fun sessionOver(bytes: ByteArray?): VaultSession {
        file.contents = bytes
        return VaultSession(file, clipboard, scope, lockDelay)
    }

    private fun unlocked(bytes: ByteArray = IMMEDIATE_VAULT): VaultSession {
        val session = sessionOver(bytes)
        assertIs<Outcome.Success<Unit>>(runBlocking { session.unlock(PASSWORD.toCharArray()) })
        return session
    }

    private fun unlockedState(session: VaultSession): SessionState.Unlocked {
        val state = session.state.value
        assertIs<SessionState.Unlocked>(state)
        return state
    }

    @Test
    fun `a session over no file starts with no vault`() {
        assertEquals(SessionState.NoVault, sessionOver(null).state.value)
    }

    @Test
    fun `a session over a vault file starts locked with no reason`() {
        assertEquals(SessionState.Locked(null), sessionOver(IMMEDIATE_VAULT).state.value)
    }

    @Test
    fun `unlocking with the wrong password reports the password`() {
        val session = sessionOver(IMMEDIATE_VAULT)

        val outcome = runBlocking { session.unlock("not the password".toCharArray()) }

        assertEquals(VaultError.WrongPassword, outcome.errorOrNull)
    }

    @Test
    fun `a failed unlock leaves the session locked`() {
        val session = sessionOver(IMMEDIATE_VAULT)

        runBlocking { session.unlock("not the password".toCharArray()) }

        assertEquals(SessionState.Locked(null), session.state.value)
    }

    @Test
    fun `unlocking with no vault file reports the missing file`() {
        val session = sessionOver(null)

        val outcome = runBlocking { session.unlock(PASSWORD.toCharArray()) }

        assertEquals(VaultError.NoVaultFile, outcome.errorOrNull)
    }

    @Test
    fun `an unlocked session publishes every entry in the body`() {
        assertEquals(listOf(TOTP_ID, HOTP_ID), unlockedState(unlocked()).entries.map { it.id })
    }

    @Test
    fun `an unlocked entry keeps every field the account list draws`() {
        val expected = UnlockedEntry(
            id = TOTP_ID,
            type = OtpType.TOTP,
            accountName = "alice",
            createdAt = Instant.parse("2026-08-13T09:41:12Z"),
            issuer = "GitHub",
            algorithm = HashAlgorithm.SHA1,
            digits = 6,
            period = 30,
            counter = null,
            orderIndex = 0,
        )

        assertEquals(expected, unlockedState(unlocked()).entries.first())
    }

    @Test
    fun `no field of an unlocked entry carries the base32 secret`() {
        val entry = unlockedState(unlocked()).entries.first()

        val carriers = UnlockedEntry::class.java.declaredFields.filter { field ->
            field.isAccessible = true
            field.get(entry) == TEST_SECRET
        }

        assertEquals(emptyList(), carriers.map { it.name })
    }

    @Test
    fun `an unlock decodes each secret into the key its base32 stands for`() {
        val session = unlocked()

        val key = session.withSecret(TOTP_ID) { it.copyOf() }

        assertContentEquals(SEED.encodeToByteArray(), key)
    }

    @Test
    fun `a body holding two entries under one id is refused as corrupt`() {
        val session = sessionOver(DUPLICATE_ID_VAULT)

        val outcome = runBlocking { session.unlock(PASSWORD.toCharArray()) }

        assertIs<VaultError.Corrupt>(outcome.errorOrNull)
    }

    @Test
    fun `an unlocked session publishes the policy the body carries`() {
        assertEquals(30, unlockedState(unlocked(GRACE_VAULT)).policy.hideGraceSeconds)
    }

    @Test
    fun `an unlocked session holds a key that is not already zeros`() {
        val session = unlocked()

        val dek = checkNotNull(session.useDek { it })

        assertFalse(dek.contentEquals(ZEROED_KEY))
    }

    @Test
    fun `a lock zeros the key the session held`() {
        val session = unlocked()
        val dek = checkNotNull(session.useDek { it })

        session.lock(LockReason.Manual)

        assertContentEquals(ZEROED_KEY, dek)
    }

    @Test
    fun `a lock zeros every decoded secret`() {
        val session = unlocked()
        val secret = checkNotNull(session.withSecret(TOTP_ID) { it })

        session.lock(LockReason.Manual)

        assertContentEquals(ZEROED_SEED, secret)
    }

    @Test
    fun `a locked session lends no secret`() {
        val session = unlocked()

        session.lock(LockReason.Manual)

        assertNull(session.withSecret(TOTP_ID) { it.size })
    }

    @Test
    fun `a lock clears the clipboard`() {
        val session = unlocked()

        session.lock(LockReason.Manual)

        assertEquals(1, clipboard.clears)
    }

    @Test
    fun `a lock publishes the reason it happened for`() {
        val session = unlocked()

        session.lock(LockReason.Exit)

        assertEquals(SessionState.Locked(LockReason.Exit), session.state.value)
    }

    // A signal reaching the runtime after a hide, an idle timeout or the tray's Lock has already
    // locked runs the exit hook over a session that is locked.
    @Test
    fun `a second lock leaves the session locked for the later reason`() {
        val session = unlocked()
        session.lock(LockReason.HiddenToTray)

        session.lock(LockReason.Exit)

        assertEquals(SessionState.Locked(LockReason.Exit), session.state.value)
    }

    @Test
    fun `a second lock leaves the key zeroed`() {
        val session = unlocked()
        val dek = checkNotNull(session.useDek { it })
        session.lock(LockReason.HiddenToTray)

        session.lock(LockReason.Exit)

        assertContentEquals(ZEROED_KEY, dek)
    }

    @Test
    fun `a lock leaves a session that has no vault where it is`() {
        val session = sessionOver(null)

        session.lock(LockReason.Manual)

        assertEquals(SessionState.NoVault, session.state.value)
    }

    @Test
    fun `a scheduled lock waits the grace period the policy names`() {
        val session = unlocked(GRACE_VAULT)

        session.scheduleLock(LockReason.HiddenToTray)

        assertEquals(listOf(30), lockDelay.requested)
    }

    @Test
    fun `a scheduled lock leaves the vault open until the grace period elapses`() {
        val session = unlocked(GRACE_VAULT)

        session.scheduleLock(LockReason.HiddenToTray)

        assertIs<SessionState.Unlocked>(session.state.value)
    }

    @Test
    fun `a scheduled lock locks the vault when the grace period elapses`() {
        val session = unlocked(GRACE_VAULT)
        session.scheduleLock(LockReason.HiddenToTray)

        lockDelay.finish(0)

        assertEquals(SessionState.Locked(LockReason.HiddenToTray), session.state.value)
    }

    @Test
    fun `a scheduled lock zeros the key when it fires`() {
        val session = unlocked(GRACE_VAULT)
        val dek = checkNotNull(session.useDek { it })
        session.scheduleLock(LockReason.HiddenToTray)

        lockDelay.finish(0)

        assertContentEquals(ZEROED_KEY, dek)
    }

    @Test
    fun `a cancelled schedule leaves the vault open when the grace period elapses`() {
        val session = unlocked(GRACE_VAULT)
        session.scheduleLock(LockReason.HiddenToTray)

        session.cancelScheduledLock()
        lockDelay.finish(0)

        assertIs<SessionState.Unlocked>(session.state.value)
    }

    @Test
    fun `a lock drops the timer scheduled before it`() {
        val session = unlocked(GRACE_VAULT)
        session.scheduleLock(LockReason.HiddenToTray)
        session.lock(LockReason.Manual)
        assertIs<Outcome.Success<Unit>>(runBlocking { session.unlock(PASSWORD.toCharArray()) })

        lockDelay.finish(0)

        assertIs<SessionState.Unlocked>(session.state.value)
    }

    @Test
    fun `a schedule after a lock arms a timer of its own`() {
        val session = unlocked(GRACE_VAULT)
        session.scheduleLock(LockReason.HiddenToTray)
        session.lock(LockReason.Manual)
        assertIs<Outcome.Success<Unit>>(runBlocking { session.unlock(PASSWORD.toCharArray()) })

        session.scheduleLock(LockReason.HiddenToTray)

        assertEquals(listOf(30, 30), lockDelay.requested)
    }

    @Test
    fun `a second schedule does not push the pending deadline out`() {
        val session = unlocked(GRACE_VAULT)

        session.scheduleLock(LockReason.HiddenToTray)
        session.scheduleLock(LockReason.Minimised)

        assertEquals(listOf(30), lockDelay.requested)
    }

    @Test
    fun `a schedule with a zero grace period locks at once`() {
        val session = unlocked()

        session.scheduleLock(LockReason.HiddenToTray)

        assertEquals(SessionState.Locked(LockReason.HiddenToTray), session.state.value)
    }

    @Test
    fun `a schedule for a reason the policy arms locks the vault`() {
        val session = unlocked()

        session.scheduleLock(LockReason.Minimised)

        assertEquals(SessionState.Locked(LockReason.Minimised), session.state.value)
    }

    @Test
    fun `a schedule for a reason the policy disarms leaves the vault open`() {
        val session = unlocked(NO_MINIMISE_VAULT)

        session.scheduleLock(LockReason.Minimised)

        assertIs<SessionState.Unlocked>(session.state.value)
    }

    @Test
    fun `a schedule against a locked vault changes nothing`() {
        val session = unlocked()
        session.lock(LockReason.Manual)

        session.scheduleLock(LockReason.HiddenToTray)

        assertEquals(SessionState.Locked(LockReason.Manual), session.state.value)
    }

    @Test
    fun `a schedule against a session with no vault changes nothing`() {
        val session = sessionOver(null)

        session.scheduleLock(LockReason.HiddenToTray)

        assertEquals(SessionState.NoVault, session.state.value)
    }

    @Test
    fun `creating a vault writes it`() {
        val session = sessionOver(null)

        runBlocking { session.create(PASSWORD.toCharArray()) }

        assertEquals(1, file.writes)
    }

    @Test
    fun `a creation opens the file it wrote rather than the bytes it built`() {
        val session = sessionOver(null)
        file.onRead = {
            file.contents = file.contents?.copyOf()?.also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        }

        val outcome = runBlocking { session.create(PASSWORD.toCharArray()) }

        assertIs<VaultError.IntegrityFailure>(outcome.errorOrNull)
    }

    @Test
    fun `creating a vault leaves the session unlocked`() {
        val session = sessionOver(null)

        runBlocking { session.create(PASSWORD.toCharArray()) }

        assertIs<SessionState.Unlocked>(session.state.value)
    }

    @Test
    fun `creating a vault over one already there is refused`() {
        val session = sessionOver(IMMEDIATE_VAULT)

        val outcome = runBlocking { session.create(PASSWORD.toCharArray()) }

        assertEquals(VaultError.VaultFileExists, outcome.errorOrNull)
    }

    @Test
    fun `a refused creation writes nothing`() {
        val session = sessionOver(IMMEDIATE_VAULT)

        runBlocking { session.create(PASSWORD.toCharArray()) }

        assertEquals(0, file.writes)
    }

    @Test
    fun `a refused creation leaves the session locked`() {
        val session = sessionOver(IMMEDIATE_VAULT)

        runBlocking { session.create(PASSWORD.toCharArray()) }

        assertEquals(SessionState.Locked(null), session.state.value)
    }

    @Test
    fun `a lock landing during a derivation leaves the vault locked`() {
        val session = sessionOver(IMMEDIATE_VAULT)
        file.onRead = { session.lock(LockReason.Manual) }

        runBlocking { session.unlock(PASSWORD.toCharArray()) }

        assertEquals(SessionState.Locked(LockReason.Manual), session.state.value)
    }

    @Test
    fun `an unlock a lock overtook reports the vault as closed`() {
        val session = sessionOver(IMMEDIATE_VAULT)
        file.onRead = { session.lock(LockReason.Manual) }

        val outcome = runBlocking { session.unlock(PASSWORD.toCharArray()) }

        assertEquals(VaultError.VaultClosed, outcome.errorOrNull)
    }

    @Test
    fun `a trigger arriving during a derivation locks the vault once the body is open`() {
        val session = sessionOver(IMMEDIATE_VAULT)
        file.onRead = { session.scheduleLock(LockReason.HiddenToTray) }

        runBlocking { session.unlock(PASSWORD.toCharArray()) }

        assertEquals(SessionState.Locked(LockReason.HiddenToTray), session.state.value)
    }

    @Test
    fun `a disarmed trigger during a derivation does not swallow the armed one behind it`() {
        val session = sessionOver(IMMEDIATE_VAULT)
        // Losing focus and going to the tray are one act of the user's, and the policy arms only
        // the second of them.
        file.onRead = {
            session.scheduleLock(LockReason.FocusLost)
            session.scheduleLock(LockReason.HiddenToTray)
        }

        runBlocking { session.unlock(PASSWORD.toCharArray()) }

        assertEquals(SessionState.Locked(LockReason.HiddenToTray), session.state.value)
    }

    @Test
    fun `a rendered session carries neither its key nor a secret`() {
        val session = unlocked()

        assertEquals("VaultSession(state=Unlocked)", "$session")
    }
}
