package com.panda.tauth

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

// Counted rather than switched: a chooser opened while another is still closing would otherwise drop
// the hold the first one is still under.
@Stable
class ModalHold {
    private var depth by mutableIntStateOf(0)

    val isHeld: Boolean get() = depth > 0

    suspend fun <T> around(chooser: suspend () -> T): T {
        depth++
        return try {
            chooser()
        } finally {
            depth--
        }
    }
}
