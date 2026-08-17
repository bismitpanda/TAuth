package com.panda.tauth.settings

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Every field off its default, so a document written from anywhere other than the value the holder
// carries disagrees with this in every field rather than in none.
private val OPENING = Preferences(
    theme = Theme.LIGHT,
    sortOrder = SortOrder.ISSUER,
    startMinimised = true,
    minimiseToTray = false,
    window = WindowGeometry(width = 1024, height = 800, x = 12, y = 34),
)

private val MOVED = WindowGeometry(width = 1024, height = 800, x = 400, y = 34)

private val REFUSED = PreferencesError.Io(IOException("the file is read only"))

class PreferencesStateTest {
    private val written = mutableListOf<Preferences>()

    private var refusal: PreferencesError? = null

    private val preferences = PreferencesState(OPENING) { document ->
        written += document
        refusal?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
    }

    private fun update(change: (Preferences) -> Preferences) = runBlocking { preferences.update(change) }

    @Test
    fun `the value it opens with is the document it was given`() {
        assertEquals(OPENING, preferences.value)
    }

    @Test
    fun `an update publishes the change`() {
        update { it.copy(theme = Theme.DARK) }

        assertEquals(Theme.DARK, preferences.value.theme)
    }

    @Test
    fun `an update writes the whole document`() {
        update { it.copy(theme = Theme.DARK) }

        assertEquals(preferences.value, written.single())
    }

    @Test
    fun `an update leaves the fields it does not name`() {
        update { it.copy(theme = Theme.DARK) }

        assertEquals(SortOrder.ISSUER, written.single().sortOrder)
    }

    // The defect this holder exists to stop: a write derived from the document as the file held it
    // at launch puts back everything chosen since.
    @Test
    fun `a later update carries the field an earlier one set`() {
        update { it.copy(theme = Theme.DARK) }

        update { it.copy(window = MOVED) }

        assertEquals(Theme.DARK, written.last().theme)
    }

    @Test
    fun `a later update carries its own field`() {
        update { it.copy(theme = Theme.DARK) }

        update { it.copy(window = MOVED) }

        assertEquals(MOVED, written.last().window)
    }

    @Test
    fun `a refused write reports the failure`() {
        refusal = REFUSED

        val outcome = update { it.copy(theme = Theme.DARK) }

        assertEquals(REFUSED, outcome.errorOrNull)
    }

    // The change is in effect the moment it is made, and the file refusing it costs the next launch
    // the setting rather than this one.
    @Test
    fun `a refused write leaves the change published`() {
        refusal = REFUSED

        update { it.copy(theme = Theme.DARK) }

        assertEquals(Theme.DARK, preferences.value.theme)
    }

    @Test
    fun `an accepted write reports success`() {
        assertIs<Outcome.Success<Unit>>(update { it.copy(theme = Theme.DARK) })
    }
}
