package com.panda.tauth.session

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.valueOrNull
import com.panda.tauth.vault.TEST_SECRET
import com.panda.tauth.vault.VaultBody
import com.panda.tauth.vault.VaultCodec
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
private const val WRONG_PASSWORD = "correct horse battery stapld"

// The bytes TEST_SECRET spells out, written here rather than decoded, so an assertion on a lent key
// stands on RFC 4226 §5.1's published seed instead of on the decoder that produced it.
private const val SEED = "12345678901234567890"

private const val TOTP_ID = "0192f4c1-0000-7000-8000-000000000031"
private const val HOTP_ID = "0192f4c1-0000-7000-8000-000000000032"
private const val ABSENT_ID = "0192f4c1-0000-7000-8000-000000000033"

// Written out rather than rebuilt from the entries below: a URI assembled by the code under test
// agrees with itself whatever fields it reads.
private const val TOTP_URI =
    "otpauth://totp/Ex%20Ample:carol?secret=$TEST_SECRET&issuer=Ex%20Ample&algorithm=SHA256&digits=8&period=60"

private const val HOTP_URI = "otpauth://hotp/bob?secret=$TEST_SECRET&counter=41"

private val BODY = VaultBody(
    entries = listOf(
        totpEntry(id = TOTP_ID, accountName = "carol").copy(
            issuer = "Ex Ample",
            algorithm = HashAlgorithm.SHA256,
            digits = 8,
            period = 60,
        ),
        hotpEntry(id = HOTP_ID, counter = 41uL, orderIndex = 1),
    ),
)

// One derivation for the whole class rather than one per test: Argon2id is priced to be slow.
private val VAULT by lazy { checkNotNull(VaultCodec.create(PASSWORD.toCharArray(), BODY).valueOrNull) }

private class CountingVaultFile(var contents: ByteArray?) : VaultFile {
    var writes = 0
    var reads = 0

    override fun exists(): Boolean = contents != null

    override fun read(): Outcome<ByteArray, VaultError> {
        reads++
        return contents?.let { Outcome.Success(it) } ?: Outcome.Failure(VaultError.NoVaultFile)
    }

    override fun write(bytes: ByteArray): Outcome<Unit, VaultError> {
        writes++
        contents = bytes
        return Outcome.Success(Unit)
    }
}

private object DisclosureClipboard : SessionClipboard {
    override fun clearIfHoldsOwnValue() = Unit
}

class SecretDisclosureTest {
    private val file = CountingVaultFile(VAULT)

    // Unconfined runs each resumption on the thread that causes it, so every assertion below sees a
    // settled session without joining anything.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val session = VaultSession(file, DisclosureClipboard, scope)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    @Test
    fun `the vault's own password is accepted while it is open`() {
        unlocked()

        val outcome = runBlocking { session.verifyPassword(PASSWORD.toCharArray()) }

        assertIs<Outcome.Success<Unit>>(outcome)
    }

    @Test
    fun `a password one character out is refused while the vault is open`() {
        unlocked()

        val outcome = runBlocking { session.verifyPassword(WRONG_PASSWORD.toCharArray()) }

        assertEquals(VaultError.WrongPassword, outcome.errorOrNull)
    }

    // A check that moved the state would send the window to the unlock screen on a mistyped
    // disclosure prompt, and one that replaced the key would zero the entries under the list.
    @Test
    fun `a refused password leaves the published state where it was`() {
        unlocked()
        val before = session.state.value

        runBlocking { session.verifyPassword(WRONG_PASSWORD.toCharArray()) }

        assertEquals(before, session.state.value)
    }

    @Test
    fun `an accepted password leaves the published state where it was`() {
        unlocked()
        val before = session.state.value

        runBlocking { session.verifyPassword(PASSWORD.toCharArray()) }

        assertEquals(before, session.state.value)
    }

    @Test
    fun `a refused password leaves the decoded keys lendable`() {
        unlocked()

        runBlocking { session.verifyPassword(WRONG_PASSWORD.toCharArray()) }

        assertContentEquals(SEED.encodeToByteArray(), session.withSecret(HOTP_ID) { it.copyOf() })
    }

    @Test
    fun `an accepted password leaves the decoded keys lendable`() {
        unlocked()

        runBlocking { session.verifyPassword(PASSWORD.toCharArray()) }

        assertContentEquals(SEED.encodeToByteArray(), session.withSecret(HOTP_ID) { it.copyOf() })
    }

    @Test
    fun `a check writes nothing`() {
        unlocked()

        runBlocking { session.verifyPassword(PASSWORD.toCharArray()) }

        assertEquals(0, file.writes)
    }

    // The salt and the wrapped key come from the header the open vault is holding, so the check
    // answers about the vault on screen rather than about whatever now sits at the path.
    @Test
    fun `a check reads no file`() {
        unlocked()
        val readsAfterUnlock = file.reads

        runBlocking { session.verifyPassword(PASSWORD.toCharArray()) }

        assertEquals(readsAfterUnlock, file.reads)
    }

    @Test
    fun `a check succeeds after the file behind it has gone`() {
        unlocked()
        file.contents = null

        val outcome = runBlocking { session.verifyPassword(PASSWORD.toCharArray()) }

        assertIs<Outcome.Success<Unit>>(outcome)
    }

    @Test
    fun `a check against a locked vault reports the closed vault`() {
        val outcome = runBlocking { session.verifyPassword(PASSWORD.toCharArray()) }

        assertEquals(VaultError.VaultClosed, outcome.errorOrNull)
    }

    @Test
    fun `a totp entry discloses the URI its fields name`() {
        unlocked()

        val outcome = runBlocking { session.discloseUri(TOTP_ID, PASSWORD.toCharArray()) }

        assertEquals(TOTP_URI, outcome.valueOrNull)
    }

    @Test
    fun `an hotp entry discloses the URI its counter names`() {
        unlocked()

        val outcome = runBlocking { session.discloseUri(HOTP_ID, PASSWORD.toCharArray()) }

        assertEquals(HOTP_URI, outcome.valueOrNull)
    }

    // The gate is the whole protection on a complete credential: a refused password must yield no
    // URI at all rather than one the caller is trusted to discard.
    @Test
    fun `a refused password discloses no URI`() {
        unlocked()

        val outcome = runBlocking { session.discloseUri(TOTP_ID, WRONG_PASSWORD.toCharArray()) }

        assertNull(outcome.valueOrNull)
    }

    @Test
    fun `a refused password reports the password rather than the entry`() {
        unlocked()

        val outcome = runBlocking { session.discloseUri(TOTP_ID, WRONG_PASSWORD.toCharArray()) }

        assertEquals(VaultError.WrongPassword, outcome.errorOrNull)
    }

    @Test
    fun `an id the vault does not hold discloses nothing`() {
        unlocked()

        val outcome = runBlocking { session.discloseUri(ABSENT_ID, PASSWORD.toCharArray()) }

        assertEquals(VaultError.NoSuchEntry, outcome.errorOrNull)
    }

    @Test
    fun `a locked vault discloses nothing`() {
        val outcome = runBlocking { session.discloseUri(TOTP_ID, PASSWORD.toCharArray()) }

        assertEquals(VaultError.VaultClosed, outcome.errorOrNull)
    }

    @Test
    fun `no entry the session publishes carries the disclosed secret`() {
        unlocked()
        val state = session.state.value
        assertIs<SessionState.Unlocked>(state)

        assertNull(state.entries.firstOrNull { TEST_SECRET in it.toString() })
    }

    private fun unlocked() {
        assertIs<Outcome.Success<Unit>>(runBlocking { session.unlock(PASSWORD.toCharArray()) })
    }
}
