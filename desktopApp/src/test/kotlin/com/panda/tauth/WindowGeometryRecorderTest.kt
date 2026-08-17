package com.panda.tauth

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.panda.tauth.settings.PreferencesError
import com.panda.tauth.settings.WindowGeometry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Three geometries, no two of them sharing every member: the window opens at one, is dragged to the
// second and resized to the third.
private val OPENED = WindowGeometry(width = 960, height = 720, x = 100, y = 50)
private val MOVED = WindowGeometry(width = 960, height = 720, x = 400, y = 50)
private val RESIZED = WindowGeometry(width = 1280, height = 900, x = 400, y = 50)

private fun stateOf(geometry: WindowGeometry, isMinimized: Boolean = false): WindowState = WindowState(
    isMinimized = isMinimized,
    position = WindowPosition(checkNotNull(geometry.x).dp, checkNotNull(geometry.y).dp),
    size = DpSize(geometry.width.dp, geometry.height.dp),
)

// No clock and no sleep: a wait ends when the test ends it, and never otherwise.
private class FakeSettle : SettleDelay {
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    override suspend fun elapse() {
        val gate = CompletableDeferred<Unit>()
        gates += gate
        gate.await()
    }

    fun finish(index: Int) {
        gates[index].complete(Unit)
    }

    // A cancelled wait is a gate nothing awaits, so completing every one of them ends only the
    // waits still standing.
    fun finishAll() {
        gates.forEach { it.complete(Unit) }
    }
}

class WindowGeometryRecorderTest {

    private val saves = mutableListOf<WindowGeometry>()
    private val settle = FakeSettle()
    private var isWriteRefused = false

    // Unconfined runs each resumption on the thread that causes it, so every assertion below sees a
    // settled recorder without joining anything.
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val recorder = WindowGeometryRecorder(scope, OPENED, settle) { geometry ->
        saves += geometry
        if (isWriteRefused) {
            Outcome.Failure(PreferencesError.Io(IOException("the file is read only")))
        } else {
            Outcome.Success(Unit)
        }
    }

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    @Test
    fun `a moved window is written when the wait elapses`() {
        recorder.sample(MOVED)

        settle.finish(0)

        assertEquals(listOf(MOVED), saves)
    }

    @Test
    fun `nothing is written before the wait elapses`() {
        recorder.sample(MOVED)

        assertEquals(emptyList(), saves)
    }

    @Test
    fun `a geometry the file already holds is not written`() {
        recorder.sample(OPENED)

        settle.finishAll()

        assertEquals(emptyList(), saves)
    }

    @Test
    fun `a window coming back to where it was during the wait writes nothing`() {
        recorder.sample(MOVED)

        recorder.sample(OPENED)
        settle.finishAll()

        assertEquals(emptyList(), saves)
    }

    @Test
    fun `a later sample replaces the one still waiting`() {
        recorder.sample(MOVED)

        recorder.sample(RESIZED)
        settle.finishAll()

        assertEquals(listOf(RESIZED), saves)
    }

    @Test
    fun `a resize after a move is written on its own`() {
        recorder.sample(MOVED)
        settle.finish(0)

        recorder.sample(RESIZED)
        settle.finish(1)

        assertEquals(listOf(MOVED, RESIZED), saves)
    }

    @Test
    fun `a geometry already written is not written twice`() {
        recorder.sample(MOVED)
        settle.finish(0)

        recorder.sample(MOVED)
        settle.finish(1)

        assertEquals(listOf(MOVED), saves)
    }

    @Test
    fun `a window returning to where it opened is written back after the first write`() {
        recorder.sample(MOVED)
        settle.finish(0)

        recorder.sample(OPENED)
        settle.finish(1)

        assertEquals(listOf(MOVED, OPENED), saves)
    }

    // The shell hands recordedGeometry the geometry the file held at launch on every sample, so a
    // state that records nothing must not put that constant back over where the window went.
    @Test
    fun `a minimised sample after a move leaves the moved geometry standing`() {
        recorder.sample(recordedGeometry(stateOf(MOVED), OPENED))
        settle.finish(0)

        recorder.sample(recordedGeometry(stateOf(MOVED, isMinimized = true), OPENED))
        settle.finishAll()

        assertEquals(listOf(MOVED), saves)
    }

    @Test
    fun `a minimise before the wait elapses still writes the move`() {
        recorder.sample(recordedGeometry(stateOf(MOVED), OPENED))

        recorder.sample(recordedGeometry(stateOf(MOVED, isMinimized = true), OPENED))
        settle.finishAll()

        assertEquals(listOf(MOVED), saves)
    }

    @Test
    fun `a geometry the file refused is written again by the next sample`() {
        isWriteRefused = true
        recorder.sample(MOVED)
        settle.finish(0)

        isWriteRefused = false
        recorder.sample(MOVED)
        settle.finish(1)

        assertEquals(listOf(MOVED, MOVED), saves)
    }

    @Test
    fun `cancelling the owning scope stops a pending write`() {
        recorder.sample(MOVED)

        scope.cancel()
        settle.finishAll()

        assertEquals(emptyList(), saves)
    }
}
