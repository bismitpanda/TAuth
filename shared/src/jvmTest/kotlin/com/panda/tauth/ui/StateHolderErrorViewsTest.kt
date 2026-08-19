package com.panda.tauth.ui

import com.panda.tauth.Outcome
import com.panda.tauth.ui.components.DisclosureState
import com.panda.tauth.ui.settings.ExportError
import com.panda.tauth.ui.settings.FileWriteError
import com.panda.tauth.ui.settings.SettingsWork
import com.panda.tauth.ui.settings.VaultExportError
import com.panda.tauth.vault.DiscloseError
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultRewriteError
import com.panda.tauth.vault.VaultUnlockError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Each holder is built at a view narrower than the root and the value it kept is read back at that
// view, so a holder storing the root, or taking it from the operation it runs, stops this compiling.
class StateHolderErrorViewsTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    @Test
    fun `a password attempt keeps the view its derivation reports`() {
        val attempt = PasswordAttempt<VaultUnlockError>()

        attempt.run(scope, "pw".toCharArray()) { Outcome.Failure(VaultError.WrongPassword) }

        val kept: VaultUnlockError? = attempt.error
        assertEquals(VaultError.WrongPassword, kept)
    }

    @Test
    fun `a disclosure gate keeps the view its check reports`() {
        val gate = DisclosureState<String, DiscloseError>()
        gate.ask("an account")

        gate.confirm(scope, "pw".toCharArray(), { _, _ -> Outcome.Failure(VaultError.NoSuchEntry) }) { }

        val kept: DiscloseError? = gate.error
        assertEquals(VaultError.NoSuchEntry, kept)
    }

    @Test
    fun `a settings action keeps the view its write reports`() {
        val settings = SettingsWork<VaultRewriteError>()

        settings.run(scope) { Outcome.Failure(VaultError.WrongPassword) }

        val kept: VaultRewriteError? = settings.error
        assertEquals(VaultError.WrongPassword, kept)
    }

    // Placing the copy reports the destination alone, and the read that precedes it is the other half
    // this slot holds: a placement widened to the whole hierarchy stops this compiling.
    @Test
    fun `an export keeps the read it made and the file it wrote`() {
        val settings = SettingsWork<VaultRewriteError>()
        val place: suspend (ByteArray) -> Outcome<Unit, FileWriteError> = { Outcome.Failure(ExportError.NotRestricted) }

        settings.export(scope, { Outcome.Success(ByteArray(1)) }, place)

        val kept: VaultExportError? = settings.exportError
        assertEquals(ExportError.NotRestricted, kept)
    }
}
