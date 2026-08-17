package com.panda.tauth.ui

import com.panda.tauth.Outcome
import com.panda.tauth.settings.Preferences
import com.panda.tauth.settings.PreferencesState

// Every document the holder tried to write, in order, so a test reads what reached the file as well
// as what reached the screen.
internal class RecordingPreferences(initial: Preferences = Preferences()) {
    val written = mutableListOf<Preferences>()

    val state = PreferencesState(initial) { preferences ->
        written += preferences
        Outcome.Success(Unit)
    }

    val last: Preferences? get() = written.lastOrNull()
}
