package com.panda.tauth.session

import com.panda.tauth.Outcome
import com.panda.tauth.totp.Base32
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.TotpCode
import com.panda.tauth.valueOrNull
import com.panda.tauth.vault.VaultBody
import com.panda.tauth.vault.VaultCodec
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultFile
import com.panda.tauth.vault.hotpEntry
import com.panda.tauth.vault.totpEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private const val PASSWORD = "correct horse battery staple"

private const val TOTP_ID = "0192f4c1-0000-7000-8000-000000000011"
private const val OFFSCREEN_ID = "0192f4c1-0000-7000-8000-000000000012"
private const val HOTP_ID = "0192f4c1-0000-7000-8000-000000000013"
private const val LONG_PERIOD_ID = "0192f4c1-0000-7000-8000-000000000014"
private const val SHA256_ID = "0192f4c1-0000-7000-8000-000000000015"

// RFC 6238 errata 2866: Appendix B uses a distinct seed per algorithm, 32 bytes for SHA-256. Off the
// SHA-1 default, so an algorithm the ticker assumed rather than read misses the published value.
private val SHA256_SECRET = Base32.encode("12345678901234567890123456789012".encodeToByteArray())

// An entry off the 30-second default, so a countdown against a period the ticker assumed rather than
// read shows up as a wrong number of seconds.
private const val LONG_PERIOD = 90

// RFC 6238 Appendix B publishes eight-digit codes, so each expectation below is a published value
// rather than a truncation of one.
private const val EIGHT_DIGITS = 8

private val TICKER_VAULT by lazy {
    val body = VaultBody(
        entries = listOf(
            totpEntry(id = TOTP_ID).copy(digits = EIGHT_DIGITS),
            totpEntry(id = OFFSCREEN_ID, orderIndex = 1, accountName = "carol").copy(digits = EIGHT_DIGITS),
            hotpEntry(id = HOTP_ID, counter = 5uL, orderIndex = 2),
            totpEntry(id = LONG_PERIOD_ID, orderIndex = 3, accountName = "dave")
                .copy(digits = EIGHT_DIGITS, period = LONG_PERIOD),
            totpEntry(id = SHA256_ID, orderIndex = 4, accountName = "erin")
                .copy(digits = EIGHT_DIGITS, algorithm = HashAlgorithm.SHA256, secret = SHA256_SECRET),
        ),
    )
    checkNotNull(VaultCodec.create(PASSWORD.toCharArray(), body).valueOrNull)
}

private class ByteVaultFile(private var bytes: ByteArray?) : VaultFile {
    override fun exists(): Boolean = bytes != null

    override fun read(): Outcome<ByteArray, VaultError> =
        bytes?.let { Outcome.Success(it) } ?: Outcome.Failure(VaultError.NoVaultFile)

    override fun write(bytes: ByteArray): Outcome<Unit, VaultError> {
        this.bytes = bytes
        return Outcome.Success(Unit)
    }
}

private object SilentClipboard : SessionClipboard {
    override fun clearIfHoldsOwnValue() = Unit
}

// No sleep and no wall clock: a tick happens when the test says so, and never otherwise.
private class FakeCadence : TickCadence {
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    val waits: Int get() = gates.size

    override suspend fun awaitTick() {
        val gate = CompletableDeferred<Unit>()
        gates += gate
        gate.await()
    }

    fun tick() {
        gates.last().complete(Unit)
    }
}

private class FixedClock(var epochSeconds: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds)
}

class CodeTickerTest {
    private val clock = FixedClock(59)
    private val cadence = FakeCadence()
    private val visible = MutableStateFlow(setOf(TOTP_ID))
    private val emissions = mutableListOf<Map<String, TotpCode>>()

    // Unconfined runs each resumption on the thread that causes it, so every assertion below sees a
    // ticker that has finished reacting without joining anything.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val session = VaultSession(ByteVaultFile(TICKER_VAULT), SilentClipboard, scope)

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    private fun ticking(): Job {
        assertIs<Outcome.Success<Unit>>(runBlocking { session.unlock(PASSWORD.toCharArray()) })
        val ticker = CodeTicker(session, clock, cadence)
        return scope.launch { ticker.codes(visible).collect { emissions += it } }
    }

    private fun latest(): Map<String, TotpCode> = emissions.last()

    @Test
    fun `a visible entry carries the RFC 6238 SHA-1 code for the instant on the clock`() {
        ticking()

        assertEquals("94287082", latest().getValue(TOTP_ID).code)
    }

    @Test
    fun `a tick after the clock moves carries the code for the new instant`() {
        ticking()
        clock.epochSeconds = 1111111109

        cadence.tick()

        assertEquals("07081804", latest().getValue(TOTP_ID).code)
    }

    @Test
    fun `a code is computed under the algorithm its own entry names`() {
        visible.value = setOf(SHA256_ID)

        ticking()

        assertEquals("46119246", latest().getValue(SHA256_ID).code)
    }

    @Test
    fun `a tick in the last second of a period reports one second left`() {
        ticking()

        // Appendix B: T becomes 0000000000000002 at 60 seconds, one second after the clock reads 59.
        assertEquals(1, latest().getValue(TOTP_ID).secondsRemaining)
    }

    @Test
    fun `a code counts down the period its own entry carries`() {
        visible.value = setOf(LONG_PERIOD_ID)

        ticking()

        // The period holding 59 seconds runs from 0 to 89, leaving 31 seconds of it to run.
        assertEquals(31, latest().getValue(LONG_PERIOD_ID).secondsRemaining)
    }

    @Test
    fun `a code carries the period it was generated under`() {
        visible.value = setOf(LONG_PERIOD_ID)

        ticking()

        assertEquals(LONG_PERIOD, latest().getValue(LONG_PERIOD_ID).period)
    }

    @Test
    fun `an entry scrolled out of view is not computed`() {
        ticking()

        assertNull(latest()[OFFSCREEN_ID])
    }

    @Test
    fun `an hotp entry on screen is not computed`() {
        visible.value = setOf(TOTP_ID, HOTP_ID)

        ticking()

        assertNull(latest()[HOTP_ID])
    }

    @Test
    fun `a row scrolled into view is computed before the next tick`() {
        ticking()

        // No tick between the scroll and the assertion: the cadence moves only when told to.
        visible.value = setOf(TOTP_ID, OFFSCREEN_ID)

        assertEquals("94287082", latest().getValue(OFFSCREEN_ID).code)
    }

    @Test
    fun `a lock ends the ticker`() {
        val job = ticking()

        session.lock(LockReason.Manual)

        assertTrue(job.isCompleted)
    }

    @Test
    fun `a lock leaves the list holding no code`() {
        ticking()

        session.lock(LockReason.Manual)

        assertEquals(emptyMap(), latest())
    }

    @Test
    fun `a cancelled collection asks for no further tick`() {
        val job = ticking()
        val waitsBefore = cadence.waits

        // What hiding the window does: the scope collecting the ticker goes, and the vault stays
        // open behind it. A ticker still running would take the tick and wait for the next one.
        job.cancel()
        cadence.tick()

        assertEquals(waitsBefore, cadence.waits)
    }
}
