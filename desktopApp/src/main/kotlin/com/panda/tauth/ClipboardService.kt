package com.panda.tauth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds

private val LOGGER = System.getLogger("com.panda.tauth.ClipboardService")

// A clipboard holding anything else is left alone rather than reported as a failure.
enum class ClipboardClear {
    CLEARED,
    SUPERSEDED,
    NOTHING_PLACED,
}

// Never thrown; returned. Separate from VaultError because a clipboard failure says nothing about
// the vault and must not reach a message written about one.
sealed interface ClipboardError {
    data class Unavailable(val cause: Throwable) : ClipboardError

    // The scope was already cancelled when the copy was asked for, so nothing was placed.
    data object ShuttingDown : ClipboardError

    data class InvalidDelay(val seconds: Int) : ClipboardError
}

// The service reaches the platform clipboard through these two calls and no others, so a headless
// test stands in for a clipboard it cannot have.
internal interface SystemClipboard {
    fun setText(text: String): Outcome<Unit, ClipboardError>

    // Success(null) means the clipboard holds something that is not text. That is not TAuth's
    // string, so it is left alone.
    fun readText(): Outcome<String?, ClipboardError>
}

internal object AwtClipboard : SystemClipboard {
    override fun setText(text: String): Outcome<Unit, ClipboardError> = try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        Outcome.Success(Unit)
    } catch (e: IllegalStateException) {
        Outcome.Failure(ClipboardError.Unavailable(e))
    } catch (e: HeadlessException) {
        Outcome.Failure(ClipboardError.Unavailable(e))
    }

    override fun readText(): Outcome<String?, ClipboardError> = try {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        val text = contents
            ?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
            ?.getTransferData(DataFlavor.stringFlavor) as? String
        Outcome.Success(text)
    } catch (e: IllegalStateException) {
        Outcome.Failure(ClipboardError.Unavailable(e))
    } catch (e: HeadlessException) {
        Outcome.Failure(ClipboardError.Unavailable(e))
    } catch (e: UnsupportedFlavorException) {
        // The contents changed between the flavour check and the read, so what is there is
        // unknown and the clear is skipped rather than aimed at it.
        Outcome.Failure(ClipboardError.Unavailable(e))
    } catch (e: IOException) {
        Outcome.Failure(ClipboardError.Unavailable(e))
    }
}

internal fun interface ClearDelay {
    suspend fun elapse(seconds: Int)
}

internal object SuspendingClearDelay : ClearDelay {
    override suspend fun elapse(seconds: Int) = delay(seconds.seconds)
}

// The delay arrives per call rather than from a policy the service holds: the policy lives in the
// unlocked vault body, and a stored copy would answer with a stale value.
class ClipboardService internal constructor(
    private val scope: CoroutineScope,
    private val clipboard: SystemClipboard,
    private val clearDelay: ClearDelay,
) {
    constructor(scope: CoroutineScope) : this(scope, AwtClipboard, SuspendingClearDelay)

    // Guards both fields and spans the clipboard calls that read and write them, so a lock-driven
    // clear and a copy cannot interleave over one clipboard.
    private val lock = ReentrantLock()

    // The one string TAuth put on the clipboard. A copied URI is a complete credential in a String
    // nothing can wipe, so the reference is dropped the moment the clipboard stops holding it.
    private var placed: String? = null

    private var pending: Job? = null

    fun copy(text: String, clearAfterSeconds: Int): Outcome<Unit, ClipboardError> = lock.withLock {
        if (clearAfterSeconds < 0) {
            return@withLock Outcome.Failure(ClipboardError.InvalidDelay(clearAfterSeconds))
        }
        // Best effort, against a shutdown already visible here: a cancellation landing after the
        // launch below leaves the text placed with a job that never clears it.
        if (clearAfterSeconds > 0 && !scope.isActive) {
            return@withLock Outcome.Failure(ClipboardError.ShuttingDown)
        }
        when (val written = clipboard.setText(text)) {
            // Nothing was placed, so the previous string and its pending clear still describe the
            // clipboard and stand untouched.
            is Outcome.Failure -> written

            is Outcome.Success -> {
                // The previous string is off the clipboard, so its timer could only match this one
                // and would take it early.
                pending?.cancel()
                placed = text
                pending = if (clearAfterSeconds > 0) scheduleClear(clearAfterSeconds) else null
                Outcome.Success(Unit)
            }
        }
    }

    fun clearIfHoldsOwnValue(): Outcome<ClipboardClear, ClipboardError> = lock.withLock {
        val result = clearNow()
        // A clipboard that could not be read is contended rather than broken, so the pending job —
        // the only retry left once the vault is locked — is dropped only on a known-clear clipboard.
        if (result is Outcome.Success) {
            pending?.cancel()
            pending = null
        }
        result
    }

    private fun scheduleClear(seconds: Int): Job = scope.launch {
        clearDelay.elapse(seconds)
        lock.withLock {
            // A copy that took the lock first cancelled this job and put its own string on the
            // clipboard; clearing here would take that string before its own delay elapsed.
            ensureActive()
            if (clearNow() is Outcome.Failure) {
                // The contents are a credential, so the message names none of them.
                LOGGER.log(System.Logger.Level.WARNING, "the clipboard could not be cleared on time")
            }
        }
    }

    // Only the exact string TAuth placed is removed, so the timer never destroys what the user copied
    // in the meantime. The comparison need not be constant-time: whoever can measure it can read it.
    private fun clearNow(): Outcome<ClipboardClear, ClipboardError> {
        val ours = placed ?: return Outcome.Success(ClipboardClear.NOTHING_PLACED)
        val current = when (val read = clipboard.readText()) {
            is Outcome.Failure -> return read
            is Outcome.Success -> read.value
        }
        if (current != ours) {
            placed = null
            return Outcome.Success(ClipboardClear.SUPERSEDED)
        }
        return when (val written = clipboard.setText("")) {
            is Outcome.Failure -> written

            is Outcome.Success -> {
                placed = null
                Outcome.Success(ClipboardClear.CLEARED)
            }
        }
    }

    // The placed string can be a complete credential; no rendering of this object carries it.
    override fun toString(): String = "ClipboardService"
}
