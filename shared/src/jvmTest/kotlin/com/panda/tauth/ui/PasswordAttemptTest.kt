package com.panda.tauth.ui

import com.panda.tauth.Outcome
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val PASSWORD = "correct horse battery staple"

private val ZEROED = CharArray(PASSWORD.length)

class PasswordAttemptTest {
    // Unconfined runs each resumption on the thread that causes it, so a cancellation below has
    // finished by the time the assertion after it runs.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val attempt = PasswordAttempt<VaultError>()

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    // The window closing and a lock both cancel the scope this runs in. The array is the copy the
    // field handed over, and the holder behind the field does not own it.
    @Test
    fun `a cancellation during the derivation zeroes the password it was handed`() {
        var captured: CharArray? = null
        attempt.run(scope, PASSWORD.toCharArray()) {
            captured = it
            awaitCancellation()
        }

        scope.cancel()

        assertContentEquals(ZEROED, captured)
    }

    @Test
    fun `a refused derivation zeroes the password it was handed`() {
        val password = PASSWORD.toCharArray()

        attempt.run(scope, password) { Outcome.Failure(VaultError.WrongPassword) }

        assertContentEquals(ZEROED, password)
    }

    @Test
    fun `an accepted derivation zeroes the password it was handed`() {
        val password = PASSWORD.toCharArray()

        attempt.run(scope, password) { Outcome.Success(Unit) }

        assertContentEquals(ZEROED, password)
    }

    @Test
    fun `a refused derivation reports what it refused`() {
        attempt.run(scope, PASSWORD.toCharArray()) { Outcome.Failure(VaultError.WrongPassword) }

        assertEquals(VaultError.WrongPassword, attempt.error)
    }

    @Test
    fun `an accepted derivation reports nothing`() {
        attempt.run(scope, PASSWORD.toCharArray()) { Outcome.Success(Unit) }

        assertEquals(null, attempt.error)
    }

    // Which screen reports the progress of a derivation turns on which attempt is running, and the
    // session's state alone does not say which of the two asked for it.
    @Test
    fun `an attempt says it is running while its derivation is in flight`() {
        attempt.run(scope, PASSWORD.toCharArray()) { awaitCancellation() }

        assertEquals(true, attempt.isRunning)
    }

    @Test
    fun `an attempt says it is not running once its derivation has ended`() {
        attempt.run(scope, PASSWORD.toCharArray()) { Outcome.Success(Unit) }

        assertFalse(attempt.isRunning)
    }
}
