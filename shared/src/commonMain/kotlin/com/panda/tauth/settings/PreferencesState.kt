package com.panda.tauth.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.panda.tauth.Outcome

// The one owner of the preference document. Every write is derived from the value held here, so a
// window geometry reaching the file cannot put back a theme or an ordering chosen since it opened.
@Stable
class PreferencesState(
    initial: Preferences,
    private val write: suspend (Preferences) -> Outcome<Unit, PreferencesError>,
) {
    var value: Preferences by mutableStateOf(initial)
        private set

    // The change takes effect as it is made, and a file that refuses it costs the next launch the
    // setting rather than this one.
    suspend fun update(change: (Preferences) -> Preferences): Outcome<Unit, PreferencesError> {
        val next = change(value)
        value = next
        return write(next)
    }
}
