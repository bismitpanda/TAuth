package com.panda.tauth.session

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.settings.SecurityPolicy
import com.panda.tauth.valueOrNull
import com.panda.tauth.vault.DiscloseError
import com.panda.tauth.vault.EntryAddError
import com.panda.tauth.vault.EntryChangeError
import com.panda.tauth.vault.EntryEdit
import com.panda.tauth.vault.PasswordGateError
import com.panda.tauth.vault.VaultBody
import com.panda.tauth.vault.VaultCodec
import com.panda.tauth.vault.VaultCommitError
import com.panda.tauth.vault.VaultCreateError
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultFile
import com.panda.tauth.vault.VaultReadError
import com.panda.tauth.vault.VaultRewriteError
import com.panda.tauth.vault.VaultUnlockError
import com.panda.tauth.vault.VaultWriteError
import com.panda.tauth.vault.totpEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val PASSWORD = "correct horse battery staple"
private const val WRONG_PASSWORD = "correct horse battery stapld"
private const val NEW_PASSWORD = "a rather different passphrase"

private const val ENTRY_ID = "0192f4c1-0000-7000-8000-000000000051"
private const val ABSENT_ID = "0192f4c1-0000-7000-8000-000000000052"

private val BODY = VaultBody(entries = listOf(totpEntry(id = ENTRY_ID)))

// One derivation for the whole class rather than one per test: Argon2id is priced to be slow.
private val VAULT by lazy { checkNotNull(VaultCodec.create(PASSWORD.toCharArray(), BODY).valueOrNull) }

private class ViewsVaultFile(var contents: ByteArray?) : VaultFile {
    override fun exists(): Boolean = contents != null

    override fun read(): Outcome<ByteArray, VaultReadError> =
        contents?.let { Outcome.Success(it) } ?: Outcome.Failure(VaultError.NoVaultFile)

    override fun write(bytes: ByteArray): Outcome<Unit, VaultWriteError> {
        contents = bytes
        return Outcome.Success(Unit)
    }
}

private object ViewsClipboard : SessionClipboard {
    override fun clearIfHoldsOwnValue() = Unit
}

// The type on the left of each assignment is what these assert: an operation whose signature widens
// back to VaultError, or to any view holding a case that operation cannot report, stops this compiling.
class SessionErrorViewsTest {
    private val file = ViewsVaultFile(VAULT)

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val session = VaultSession(file, ViewsClipboard, scope)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    private fun unlocked() {
        assertIs<Outcome.Success<Unit>>(runBlocking { session.unlock(PASSWORD.toCharArray()) })
    }

    @Test
    fun `a refused creation is typed at the view a creation reports`() {
        val refusal: VaultCreateError =
            checkNotNull(runBlocking { session.create(PASSWORD.toCharArray()) }.errorOrNull)

        assertEquals(VaultError.VaultFileExists, refusal)
    }

    @Test
    fun `a refused unlock is typed at the view an unlock reports`() {
        val refusal: VaultUnlockError =
            checkNotNull(runBlocking { session.unlock(WRONG_PASSWORD.toCharArray()) }.errorOrNull)

        assertEquals(VaultError.WrongPassword, refusal)
    }

    @Test
    fun `a refused password change is typed at the view a rewrite reports`() {
        unlocked()

        val refusal: VaultRewriteError = checkNotNull(
            runBlocking {
                session.changePassword(WRONG_PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray())
            }.errorOrNull,
        )

        assertEquals(VaultError.WrongPassword, refusal)
    }

    @Test
    fun `a refused policy write is typed at the view a commit reports`() {
        val refusal: VaultCommitError =
            checkNotNull(runBlocking { session.setPolicy(SecurityPolicy()) }.errorOrNull)

        assertEquals(VaultError.VaultClosed, refusal)
    }

    @Test
    fun `a refused export is typed at the view a read reports`() {
        file.contents = null

        val refusal: VaultReadError = checkNotNull(runBlocking { session.exportEncrypted() }.errorOrNull)

        assertEquals(VaultError.NoVaultFile, refusal)
    }

    @Test
    fun `a refused add is typed at the view storing a new entry reports`() {
        unlocked()

        // Under a name of its own, so what the id collides with is the id and not the account.
        val collision = totpEntry(id = ENTRY_ID, accountName = "erin")
        val refusal: EntryAddError = checkNotNull(runBlocking { session.addEntries(listOf(collision)) }.errorOrNull)

        assertIs<VaultError.InvalidEntry>(refusal)
    }

    @Test
    fun `a refused edit is typed at the view changing an entry reports`() {
        unlocked()

        val refusal: EntryChangeError = checkNotNull(
            runBlocking { session.editEntry(ABSENT_ID, EntryEdit(accountName = "erin")) }.errorOrNull,
        )

        assertEquals(VaultError.NoSuchEntry, refusal)
    }

    // Narrower than a disclosure by the one case a check has no id to report: this never looks an
    // entry up, so nothing here can be the entry that is not there.
    @Test
    fun `a refused password check is typed at the view a check at the gate reports`() {
        unlocked()

        val refusal: PasswordGateError = checkNotNull(
            runBlocking { session.verifyPassword(WRONG_PASSWORD.toCharArray()) }.errorOrNull,
        )

        assertEquals(VaultError.WrongPassword, refusal)
    }

    @Test
    fun `a refused disclosure is typed at the view a disclosure reports`() {
        unlocked()

        val refusal: DiscloseError = checkNotNull(
            runBlocking { session.discloseUri(ENTRY_ID, WRONG_PASSWORD.toCharArray()) }.errorOrNull,
        )

        assertEquals(VaultError.WrongPassword, refusal)
    }
}
