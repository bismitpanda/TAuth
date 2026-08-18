package com.panda.tauth.ui

import com.panda.tauth.Outcome
import com.panda.tauth.ui.components.DisclosureState
import com.panda.tauth.ui.settings.SettingsWork
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
}
