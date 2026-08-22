package com.panda.tauth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// These are the inputs. Every expected value below is written out as a literal, so no assertion is
// satisfied by the string the service happened to use.
private const val CODE = "123456"
private const val SECOND_CODE = "654321"

// The label and issuer are invented; the secret is RFC 4226's published seed in base32, which is
// the only kind of secret a test here carries.
private const val URI =
    "otpauth://totp/ACME:alice@example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=ACME"

private const val USER_TEXT = "a shopping list"

private class FakeClipboard(var contents: String? = null) : SystemClipboard {
    val writes = mutableListOf<String>()
    var setError: ClipboardError? = null
    var readError: ClipboardError? = null

    override fun setText(text: String): Outcome<Unit, ClipboardError> {
        setError?.let { return Outcome.Failure(it) }
        writes += text
        contents = text
        return Outcome.Success(Unit)
    }

    override fun readText(): Outcome<String?, ClipboardError> {
        readError?.let { return Outcome.Failure(it) }
        return Outcome.Success(contents)
    }
}

// No clock and no sleep: a scheduled wait finishes when the test completes its gate, and never
// otherwise.
private class FakeClearDelay : ClearDelay {
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

class ClipboardServiceTest {

    private val clipboard = FakeClipboard()
    private val clearDelay = FakeClearDelay()

    // Unconfined runs each resumption on the thread that causes it, so every assertion below sees a
    // settled service without joining anything.
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val service = ClipboardService(scope, clipboard, clearDelay)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    @Test
    fun `copy places the exact string on the clipboard`() {
        service.copy(CODE, 20)

        assertEquals("123456", clipboard.contents)
    }

    @Test
    fun `copy reports success when the clipboard takes the string`() {
        assertEquals(Outcome.Success(Unit), service.copy(CODE, 20))
    }

    @Test
    fun `the pending clear waits the number of seconds the caller asked for`() {
        service.copy(CODE, 20)

        assertEquals(listOf(20), clearDelay.requested)
    }

    @Test
    fun `nothing is cleared before the delay elapses`() {
        service.copy(CODE, 20)

        assertEquals(listOf("123456"), clipboard.writes)
    }

    @Test
    fun `the code is cleared when the delay elapses`() {
        service.copy(CODE, 20)

        clearDelay.finish(0)

        assertEquals("", clipboard.contents)
    }

    @Test
    fun `a copied otpauth uri is cleared when the delay elapses`() {
        service.copy(URI, 20)

        clearDelay.finish(0)

        assertEquals("", clipboard.contents)
    }

    @Test
    fun `a timed clear leaves a value the user copied in the meantime`() {
        service.copy(CODE, 20)
        clipboard.contents = USER_TEXT

        clearDelay.finish(0)

        assertEquals("a shopping list", clipboard.contents)
    }

    @Test
    fun `a clear that finds another value reports it as superseded`() {
        service.copy(CODE, 20)
        clipboard.contents = USER_TEXT

        assertEquals(Outcome.Success(ClipboardClear.SUPERSEDED), service.clearIfHoldsOwnValue())
    }

    @Test
    fun `contents that are not text are left alone`() {
        service.copy(CODE, 20)
        clipboard.contents = null

        service.clearIfHoldsOwnValue()

        assertEquals(listOf("123456"), clipboard.writes)
    }

    @Test
    fun `a superseded string is not cleared by a later lock`() {
        service.copy(CODE, 20)
        clipboard.contents = USER_TEXT
        service.clearIfHoldsOwnValue()

        assertEquals(Outcome.Success(ClipboardClear.NOTHING_PLACED), service.clearIfHoldsOwnValue())
    }

    @Test
    fun `zero seconds schedules no clear`() {
        service.copy(CODE, 0)

        assertEquals(emptyList(), clearDelay.requested)
    }

    @Test
    fun `zero seconds still leaves the string for a lock to clear`() {
        service.copy(CODE, 0)

        assertEquals(Outcome.Success(ClipboardClear.CLEARED), service.clearIfHoldsOwnValue())
    }

    @Test
    fun `a negative delay is refused`() {
        assertEquals(Outcome.Failure(ClipboardError.InvalidDelay(-1)), service.copy(CODE, -1))
    }

    @Test
    fun `a negative delay places nothing`() {
        service.copy(CODE, -1)

        assertEquals(emptyList(), clipboard.writes)
    }

    @Test
    fun `a second copy stops the first clear`() {
        service.copy(CODE, 20)
        service.copy(SECOND_CODE, 20)

        clearDelay.finish(0)

        assertEquals("654321", clipboard.contents)
    }

    @Test
    fun `a second copy is cleared when its own delay elapses`() {
        service.copy(CODE, 20)
        service.copy(SECOND_CODE, 30)

        clearDelay.finish(1)

        assertEquals("", clipboard.contents)
    }

    @Test
    fun `a second copy waits its own number of seconds`() {
        service.copy(CODE, 20)
        service.copy(SECOND_CODE, 30)

        assertEquals(listOf(20, 30), clearDelay.requested)
    }

    @Test
    fun `a copy the clipboard refuses reports the failure`() {
        val refusal = ClipboardError.Unavailable(IllegalStateException("the clipboard is busy"))
        clipboard.setError = refusal

        assertEquals(Outcome.Failure(refusal), service.copy(CODE, 20))
    }

    @Test
    fun `a copy the clipboard refuses places nothing`() {
        clipboard.setError = ClipboardError.Unavailable(IllegalStateException("the clipboard is busy"))

        service.copy(CODE, 20)

        assertEquals(emptyList(), clipboard.writes)
    }

    @Test
    fun `a copy the clipboard refuses schedules no clear`() {
        clipboard.setError = ClipboardError.Unavailable(IllegalStateException("the clipboard is busy"))

        service.copy(CODE, 20)

        assertEquals(emptyList(), clearDelay.requested)
    }

    @Test
    fun `a copy the clipboard refuses leaves the earlier clear pending`() {
        service.copy(CODE, 20)
        clipboard.setError = ClipboardError.Unavailable(IllegalStateException("the clipboard is busy"))
        service.copy(SECOND_CODE, 20)
        clipboard.setError = null

        clearDelay.finish(0)

        assertEquals("", clipboard.contents)
    }

    @Test
    fun `a clipboard that cannot be read is not cleared`() {
        service.copy(CODE, 20)
        clipboard.readError = ClipboardError.Unavailable(IllegalStateException("the clipboard is busy"))

        service.clearIfHoldsOwnValue()

        assertEquals("123456", clipboard.contents)
    }

    @Test
    fun `a clipboard that cannot be read reports the failure`() {
        val refusal = ClipboardError.Unavailable(IllegalStateException("the clipboard is busy"))
        service.copy(CODE, 20)
        clipboard.readError = refusal

        assertEquals(Outcome.Failure(refusal), service.clearIfHoldsOwnValue())
    }

    @Test
    fun `a clear skipped by a read failure is retried by the next one`() {
        service.copy(CODE, 20)
        clipboard.readError = ClipboardError.Unavailable(IllegalStateException("the clipboard is busy"))
        service.clearIfHoldsOwnValue()
        clipboard.readError = null

        assertEquals(Outcome.Success(ClipboardClear.CLEARED), service.clearIfHoldsOwnValue())
    }

    @Test
    fun `a clear the clipboard refuses to write is retried by the next one`() {
        service.copy(CODE, 20)
        clipboard.setError = ClipboardError.Unavailable(IllegalStateException("the clipboard is busy"))
        service.clearIfHoldsOwnValue()
        clipboard.setError = null

        assertEquals(Outcome.Success(ClipboardClear.CLEARED), service.clearIfHoldsOwnValue())
    }

    // A lock is the last chance the caller has, so a lock whose clear failed must leave the timer
    // standing: nothing else will take the string off the clipboard.
    @Test
    fun `a lock whose clear fails leaves the timer to do it`() {
        service.copy(CODE, 20)
        clipboard.readError = ClipboardError.Unavailable(IllegalStateException("the clipboard is busy"))
        service.clearIfHoldsOwnValue()
        clipboard.readError = null

        clearDelay.finish(0)

        assertEquals("", clipboard.contents)
    }

    @Test
    fun `a timed clear that cannot read the clipboard leaves it alone`() {
        service.copy(CODE, 20)
        clipboard.readError = ClipboardError.Unavailable(IllegalStateException("the clipboard is busy"))

        clearDelay.finish(0)

        assertEquals("123456", clipboard.contents)
    }

    @Test
    fun `a timed clear that cannot read the clipboard leaves the code outstanding`() {
        service.copy(CODE, 20)
        clipboard.readError = ClipboardError.Unavailable(IllegalStateException("the clipboard is busy"))
        clearDelay.finish(0)
        clipboard.readError = null

        assertEquals(Outcome.Success(ClipboardClear.CLEARED), service.clearIfHoldsOwnValue())
    }

    @Test
    fun `a lock before any copy reports nothing placed`() {
        assertEquals(Outcome.Success(ClipboardClear.NOTHING_PLACED), service.clearIfHoldsOwnValue())
    }

    @Test
    fun `a lock before any copy writes nothing`() {
        val untouched = FakeClipboard(USER_TEXT)

        ClipboardService(scope, untouched, clearDelay).clearIfHoldsOwnValue()

        assertEquals("a shopping list", untouched.contents)
    }

    @Test
    fun `a second lock reports nothing placed`() {
        service.copy(CODE, 20)
        service.clearIfHoldsOwnValue()

        assertEquals(Outcome.Success(ClipboardClear.NOTHING_PLACED), service.clearIfHoldsOwnValue())
    }

    @Test
    fun `canceling the owning scope stops a pending clear`() {
        service.copy(CODE, 20)

        scope.cancel()
        clearDelay.finish(0)

        assertEquals("123456", clipboard.contents)
    }

    @Test
    fun `a copy whose clear cannot be scheduled places nothing`() {
        scope.cancel()

        service.copy(CODE, 20)

        assertEquals(emptyList(), clipboard.writes)
    }

    @Test
    fun `a copy whose clear cannot be scheduled reports the shutdown`() {
        scope.cancel()

        assertEquals(Outcome.Failure(ClipboardError.ShuttingDown), service.copy(CODE, 20))
    }

    @Test
    fun `a copy needing no clear still succeeds after the scope is canceled`() {
        scope.cancel()

        assertEquals(Outcome.Success(Unit), service.copy(CODE, 0))
    }

    @Test
    fun `the copied string does not appear in the service's text form`() {
        service.copy(URI, 20)

        val text = service.toString()

        assertFalse(text.contains("GEZDGNBVGY3TQOJQ"), "the service's text form carries the secret")
    }
}
