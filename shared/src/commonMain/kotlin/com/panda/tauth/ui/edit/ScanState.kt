package com.panda.tauth.ui.edit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.panda.tauth.Outcome
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.vault.ImageReadError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// What an image offered. The accounts hold every secret their codes carried, so they are dropped as
// soon as one is chosen or the choice is abandoned.
@Stable
internal class ScanState {
    var isBusy: Boolean by mutableStateOf(false)
        private set

    // What the image itself reported, apart from the sentence below about what it held.
    var error: ImageReadError? by mutableStateOf(null)
        private set

    var notice: String? by mutableStateOf(null)
        private set

    // More than one account in one image; which was meant is the user's to say.
    var choices: List<OtpAuthUri> by mutableStateOf(emptyList())
        private set

    fun cancelChoice() {
        choices = emptyList()
    }

    // An image the user declines is not a failure and offers nothing.
    fun read(scope: CoroutineScope, scanning: QrScanning, onAccount: (String) -> Unit) {
        isBusy = true
        error = null
        notice = null
        choices = emptyList()
        scope.launch {
            try {
                when (val scanned = scanning.scan()) {
                    is Outcome.Failure -> error = scanned.error
                    is Outcome.Success -> scanned.value?.let { offer(accountsIn(it), it.size, onAccount) }
                }
            } finally {
                isBusy = false
            }
        }
    }

    fun choose(uri: OtpAuthUri, onAccount: (String) -> Unit) {
        choices = emptyList()
        onAccount(uri.build())
    }

    // A code that is not an account and no code at all are different things to the person holding
    // the image, so they are not one sentence.
    private fun offer(accounts: List<OtpAuthUri>, codes: Int, onAccount: (String) -> Unit) {
        when {
            accounts.size == 1 -> onAccount(accounts.single().build())
            accounts.size > 1 -> choices = accounts
            codes > 0 -> notice = SCAN_NOT_AN_ACCOUNT
            else -> notice = SCAN_NO_CODE
        }
    }
}
