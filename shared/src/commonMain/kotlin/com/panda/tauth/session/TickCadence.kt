package com.panda.tauth.session

import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

private const val MILLIS_PER_SECOND = 1000L

// When to compute again, kept apart from the clock that says which code is current. The wait is a
// suspension rather than a timer thread, so cancelling the scope that collects the ticker stops it.
internal fun interface TickCadence {
    suspend fun awaitTick()
}

// A period boundary falls on a whole epoch second, so a wait ending on the second ends on every
// boundary a code can cross. Never zero: a wait of no time on an exact second would spin.
internal fun millisToNextSecond(epochMillis: Long): Long = MILLIS_PER_SECOND - epochMillis.mod(MILLIS_PER_SECOND)

internal class SecondBoundaryCadence(private val clock: Clock) : TickCadence {
    override suspend fun awaitTick() = delay(millisToNextSecond(clock.now().toEpochMilliseconds()).milliseconds)
}
