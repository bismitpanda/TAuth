package com.panda.tauth.ui.imports

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.panda.tauth.Outcome
import com.panda.tauth.vault.EntryAddError
import com.panda.tauth.vault.ImportReadError
import com.panda.tauth.vault.ImportRow
import com.panda.tauth.vault.VaultEntry
import com.panda.tauth.vault.accepted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// What an import has read and what the user has decided about it. The rows carry every secret the
// file offered, so they are dropped the moment the preview is left by any path.
@Stable
internal class ImportWork {
    var rows: List<ImportRow> by mutableStateOf(emptyList())
        private set

    // Positions of duplicates the user chose over the default, which is to skip one the vault holds.
    var addAnyway: Set<Int> by mutableStateOf(emptySet())
        private set

    var isBusy: Boolean by mutableStateOf(false)
        private set

    // What reading the file reported, which is shown where the file was chosen: there is no preview
    // to carry it when the read is what failed.
    var readError: ImportReadError? by mutableStateOf(null)
        private set

    var addError: EntryAddError? by mutableStateOf(null)
        private set

    val isPreviewing: Boolean get() = rows.isNotEmpty()

    fun toggle(position: Int) {
        addAnyway = if (position in addAnyway) addAnyway - position else addAnyway + position
    }

    fun clear() {
        rows = emptyList()
        addAnyway = emptySet()
        addError = null
    }

    fun clearReadError() {
        readError = null
    }

    // Choosing the file and reading it are one action to the user, and a file declined is neither a
    // failure nor a preview.
    fun open(
        scope: CoroutineScope,
        source: suspend () -> Outcome<String?, ImportReadError>,
        read: suspend (String) -> Outcome<List<ImportRow>, ImportReadError>,
    ) {
        isBusy = true
        readError = null
        scope.launch {
            try {
                val text = when (val chosen = source()) {
                    is Outcome.Failure -> {
                        readError = chosen.error
                        return@launch
                    }

                    is Outcome.Success -> chosen.value ?: return@launch
                }
                when (val offered = read(text)) {
                    is Outcome.Failure -> readError = offered.error

                    is Outcome.Success -> {
                        rows = offered.value
                        addAnyway = emptySet()
                        addError = null
                    }
                }
            } finally {
                isBusy = false
            }
        }
    }

    // The preview is left on the path that lands the accounts and stands on the path that reports
    // why they did not, so the rows never outlive the decision they were read for.
    fun add(scope: CoroutineScope, write: suspend (List<VaultEntry>) -> Outcome<Unit, EntryAddError>) {
        val entries = rows.accepted(addAnyway)
        isBusy = true
        addError = null
        scope.launch {
            try {
                when (val written = write(entries)) {
                    is Outcome.Failure -> addError = written.error
                    is Outcome.Success -> clear()
                }
            } finally {
                isBusy = false
            }
        }
    }
}
