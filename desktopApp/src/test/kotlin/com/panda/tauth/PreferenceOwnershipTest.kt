package com.panda.tauth

import com.panda.tauth.settings.Preferences
import com.panda.tauth.settings.PreferencesState
import com.panda.tauth.settings.SortOrder
import com.panda.tauth.settings.Theme
import com.panda.tauth.settings.WindowGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val OPENED = WindowGeometry(width = 960, height = 720, x = 100, y = 50)
private val MOVED = WindowGeometry(width = 960, height = 720, x = 400, y = 50)

// Every field off its default, so a write made from anywhere other than the holder disagrees with
// this in every field rather than in none.
private val OPENING = Preferences(
    theme = Theme.LIGHT,
    sortOrder = SortOrder.ISSUER,
    startMinimised = true,
    minimiseToTray = false,
    window = OPENED,
)

// The window and the settings screen write one document through one holder. The recorder is the half
// that fires without the user asking, so it is the half that would put back what settings changed.
class PreferenceOwnershipTest {
    private val saves = mutableListOf<Preferences>()

    // Unconfined runs each resumption on the thread that causes it, and a wait that ends at once
    // leaves nothing pending by the time the assertion runs.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val preferences = PreferencesState(OPENING) { document ->
        saves += document
        Outcome.Success(Unit)
    }

    private val recorder = WindowGeometryRecorder(scope, OPENED, SettleDelay {}) { geometry ->
        preferences.update { it.copy(window = geometry) }
    }

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    @Test
    fun `a geometry write carries a theme chosen since the window opened`() {
        runBlocking { preferences.update { it.copy(theme = Theme.DARK) } }

        recorder.sample(MOVED)

        assertEquals(Theme.DARK, saves.last().theme)
    }

    @Test
    fun `a geometry write carries an ordering chosen since the window opened`() {
        runBlocking { preferences.update { it.copy(sortOrder = SortOrder.RECENTLY_ADDED) } }

        recorder.sample(MOVED)

        assertEquals(SortOrder.RECENTLY_ADDED, saves.last().sortOrder)
    }

    // The pair above says nothing on its own if the geometry never reaches the file.
    @Test
    fun `a geometry write carries the geometry it sampled`() {
        runBlocking { preferences.update { it.copy(theme = Theme.DARK) } }

        recorder.sample(MOVED)

        assertEquals(MOVED, saves.last().window)
    }

    @Test
    fun `a theme write carries the geometry recorded before it`() {
        recorder.sample(MOVED)

        runBlocking { preferences.update { it.copy(theme = Theme.DARK) } }

        assertEquals(MOVED, saves.last().window)
    }
}
