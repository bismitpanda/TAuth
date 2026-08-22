package com.panda.tauth

import com.panda.tauth.settings.PreferencesError
import com.panda.tauth.settings.WindowGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val SETTLE_MILLIS = 400L

// A drag reports every position the window passes through. The wait is a suspension, so the sample
// that follows cancels the one before it rather than a thread writing a position already left.
internal fun interface SettleDelay {
    suspend fun elapse()
}

internal object SuspendingSettleDelay : SettleDelay {
    override suspend fun elapse() = delay(SETTLE_MILLIS.milliseconds)
}

class WindowGeometryRecorder internal constructor(
    private val scope: CoroutineScope,
    private var recorded: WindowGeometry,
    private val settle: SettleDelay,
    private val save: suspend (WindowGeometry) -> Outcome<Unit, PreferencesError>,
) {
    constructor(
        scope: CoroutineScope,
        recorded: WindowGeometry,
        save: suspend (WindowGeometry) -> Outcome<Unit, PreferencesError>,
    ) : this(scope, recorded, SuspendingSettleDelay, save)

    private var pending: Job? = null

    // A state with no geometry to record leaves the pending write standing, so a move the user makes
    // and then minimizes out of still reaches the file.
    fun sample(geometry: WindowGeometry?) {
        if (geometry == null) return
        pending?.cancel()
        pending = scope.launch {
            settle.elapse()
            // The comparison is against what the file holds and is made after the wait, so a window
            // that comes back to where it was writes nothing and takes the pending write with it.
            if (geometry == recorded) return@launch
            // A refused write leaves the baseline where it was, so the next sample offers this
            // geometry again rather than reading as already held.
            when (save(geometry)) {
                is Outcome.Success -> recorded = geometry
                is Outcome.Failure -> Unit
            }
        }
    }
}
