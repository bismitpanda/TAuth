package com.panda.tauth.ui.list

import com.panda.tauth.Outcome
import com.panda.tauth.vault.EntryChangeError
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ENTRY_ID = "0192f4c1-0000-7000-8000-0000000000b1"

// RFC 4226 Appendix D counter 0 over the seed the fixtures carry.
private const val CODE = "755224"

private val REFUSED = VaultError.LockedByAnotherProcess("vault.lock")

class RowStateTest {
    // Unconfined runs each resumption on the thread that causes it, so every generation below has
    // reached the fake by the time the assertion after it runs.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val rows = RowState()

    private var generations = 0

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    // The control's enabled flag is what the last recomposition drew, so two presses inside one frame
    // both see it live. Each press past the first spends a counter value nobody asked for.
    @Test
    fun `a second press with the write still in flight reaches the vault not at all`() {
        rows.generate(scope, ENTRY_ID) { pending() }

        rows.generate(scope, ENTRY_ID) { pending() }

        assertEquals(1, generations)
    }

    @Test
    fun `a press inside the interval after a write reaches the vault not at all`() {
        rows.generate(scope, ENTRY_ID) { succeeded() }

        rows.generate(scope, ENTRY_ID) { succeeded() }

        assertEquals(1, generations)
    }

    @Test
    fun `the first press reaches the vault`() {
        rows.generate(scope, ENTRY_ID) { pending() }

        assertEquals(1, generations)
    }

    @Test
    fun `a refused generation shows no code`() {
        rows.generate(scope, ENTRY_ID) { refused() }

        assertEquals(emptyMap(), rows.generated)
    }

    // A refused write left the counter where it was, so the control has to come back for the retry.
    @Test
    fun `a refused generation leaves the control live`() {
        rows.generate(scope, ENTRY_ID) { refused() }

        assertEquals(emptySet(), rows.coolingDown)
    }

    @Test
    fun `a press after a refusal reaches the vault`() {
        rows.generate(scope, ENTRY_ID) { refused() }

        rows.generate(scope, ENTRY_ID) { refused() }

        assertEquals(2, generations)
    }

    private suspend fun pending(): Outcome<String, EntryChangeError> {
        generations++
        return CompletableDeferred<Outcome<String, EntryChangeError>>().await()
    }

    private fun succeeded(): Outcome<String, EntryChangeError> {
        generations++
        return Outcome.Success(CODE)
    }

    private fun refused(): Outcome<String, EntryChangeError> {
        generations++
        return Outcome.Failure(REFUSED)
    }
}
