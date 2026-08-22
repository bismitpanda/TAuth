package com.panda.tauth.session

import com.panda.tauth.totp.OtpType
import com.panda.tauth.totp.Totp
import com.panda.tauth.totp.TotpCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.takeWhile
import kotlin.time.Clock

// The codes the account list draws, for the rows it has on screen. The clock says which code is
// current; the cadence says when to ask again.
class CodeTicker internal constructor(
    private val session: VaultSession,
    private val clock: Clock,
    private val cadence: TickCadence,
) {
    constructor(session: VaultSession, clock: Clock = Clock.System) :
        this(session, clock, SecondBoundaryCadence(clock))

    // Cold, so the scope that collects it stops the ticker. Every emission is computed from the
    // current `visible` set, so a scroll recomputes at once rather than leaving a new row blank.
    fun codes(visible: StateFlow<Set<String>>): Flow<Map<String, TotpCode>> =
        combine(ticks(), visible, session.state) { _, ids, state -> snapshot(state, ids) }
            // A lock ends the flow and the last emission is an empty map, so no row goes on showing
            // a code from a session that has closed.
            .takeWhile { it != null }
            .filterNotNull()
            .onCompletion { emit(emptyMap()) }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            // Before the first wait, so a list that has just come up draws codes rather than blanks
            // for the rest of the second.
            emit(Unit)
            cadence.awaitTick()
        }
    }

    // Null once the session holds no key, which is what ends the flow above.
    private fun snapshot(state: SessionState, visible: Set<String>): Map<String, TotpCode>? {
        if (state !is SessionState.Unlocked) return null
        val epochSeconds = clock.now().epochSeconds
        return state.entries
            .filter { it.id in visible }
            .mapNotNull { entry -> codeFor(entry, epochSeconds)?.let { entry.id to it } }
            .toMap()
    }

    private fun codeFor(entry: UnlockedEntry, epochSeconds: Long): TotpCode? {
        // Advancing a hotp counter on a timer would spend a code nobody asked to see, and a period
        // guessed for a totp entry carrying none would show a code no server is computing.
        if (entry.type != OtpType.TOTP) return null
        val period = entry.period ?: return null
        // The key is lent rather than copied out, so a lock arriving mid-tick waits for this.
        val code = session.withSecret(entry.id) { secret ->
            Totp.generate(secret, epochSeconds, entry.algorithm, entry.digits, period)
        } ?: return null
        return TotpCode(code, Totp.secondsRemaining(epochSeconds, period), period)
    }
}
