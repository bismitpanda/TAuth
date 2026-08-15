package com.panda.tauth.ui.components

import com.panda.tauth.Outcome
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val PASSWORD = "correct horse battery staple"
private const val URI = "otpauth://totp/GitHub:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=GitHub"

private val ZEROED = CharArray(PASSWORD.length)

class DisclosureStateTest {
    // Unconfined runs each resumption on the thread that causes it, so a cancellation and a completion
    // below have both finished by the time the assertion after them runs.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val state = DisclosureState<String>()

    private var disclosed: String? = null

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    // The lock whose whole purpose is zeroing key material is what strands this copy: it cancels the
    // scope the check runs in, and the array is not the field holder's to wipe.
    @Test
    fun `a lock landing during the check zeroes the password it was handed`() {
        state.ask("id")
        var captured: CharArray? = null
        state.confirm(
            scope,
            PASSWORD.toCharArray(),
            { _, password ->
                captured = password
                awaitCancellation()
            },
            { disclosed = it },
        )

        scope.cancel()

        assertContentEquals(ZEROED, captured)
    }

    @Test
    fun `a refused check zeroes the password it was handed`() {
        state.ask("id")
        val password = PASSWORD.toCharArray()

        state.confirm(scope, password, { _, _ -> Outcome.Failure(VaultError.WrongPassword) }, { disclosed = it })

        assertContentEquals(ZEROED, password)
    }

    @Test
    fun `a confirmation with no gate open zeroes the password it was handed`() {
        val password = PASSWORD.toCharArray()

        state.confirm(scope, password, { _, _ -> Outcome.Success(URI) }, { disclosed = it })

        assertContentEquals(ZEROED, password)
    }

    // Escape and the scrim reach the dismissal whatever the dismiss button is doing, so a check the
    // user walked away from must not land a complete credential behind them.
    @Test
    fun `a check finishing after a dismissal discloses nothing`() {
        val gate = CompletableDeferred<Outcome<String, VaultError>>()
        state.ask("id")
        state.confirm(scope, PASSWORD.toCharArray(), { _, _ -> gate.await() }, { disclosed = it })

        state.cancel()
        gate.complete(Outcome.Success(URI))

        assertNull(disclosed)
    }

    @Test
    fun `a dismissal leaves no check running`() {
        state.ask("id")
        state.confirm(scope, PASSWORD.toCharArray(), { _, _ -> awaitCancellation() }, { disclosed = it })

        state.cancel()

        assertFalse(state.isBusy)
    }

    @Test
    fun `a check finishing after a dismissal does not close the gate opened next`() {
        val gate = CompletableDeferred<Outcome<String, VaultError>>()
        state.ask("first")
        state.confirm(scope, PASSWORD.toCharArray(), { _, _ -> gate.await() }, { disclosed = it })
        state.cancel()
        state.ask("second")

        gate.complete(Outcome.Success(URI))

        assertEquals("second", state.request)
    }

    @Test
    fun `an accepted check discloses the value it was given`() {
        state.ask("id")

        state.confirm(scope, PASSWORD.toCharArray(), { _, _ -> Outcome.Success(URI) }, { disclosed = it })

        assertEquals(URI, disclosed)
    }

    @Test
    fun `a refused check keeps the gate on screen`() {
        state.ask("id")

        state.confirm(scope, PASSWORD.toCharArray(), { _, _ -> Outcome.Failure(VaultError.WrongPassword) }) {
            disclosed = it
        }

        assertEquals("id", state.request)
    }
}
