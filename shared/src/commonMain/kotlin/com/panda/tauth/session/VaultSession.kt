package com.panda.tauth.session

import com.panda.tauth.Outcome
import com.panda.tauth.crypto.SecureBytes
import com.panda.tauth.crypto.exclusively
import com.panda.tauth.flatMap
import com.panda.tauth.map
import com.panda.tauth.settings.SecurityPolicy
import com.panda.tauth.totp.Base32
import com.panda.tauth.totp.Hotp
import com.panda.tauth.vault.EntryEdit
import com.panda.tauth.vault.OpenVault
import com.panda.tauth.vault.VaultBody
import com.panda.tauth.vault.VaultCodec
import com.panda.tauth.vault.VaultEntry
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultFile
import com.panda.tauth.vault.edited
import com.panda.tauth.vault.toOtpAuthUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

// The wait is a suspension rather than a timer thread, so cancelling the scope that owns it drops a
// pending lock instead of leaving a thread to fire one at a session that has moved on.
internal fun interface LockDelay {
    suspend fun elapse(seconds: Int)
}

internal object SuspendingLockDelay : LockDelay {
    override suspend fun elapse(seconds: Int) = delay(seconds.seconds)
}

// What an unlock has to still be true about when its derivation finishes.
private class Attempt(val previous: SessionState, val epoch: Int)

private fun VaultBody.replacing(entry: VaultEntry): VaultBody =
    copy(entries = entries.map { if (it.id == entry.id) entry else it })

// The key material is private and no member hands it out: callers ask for an operation, not a key.
// The scope belongs to the application, so shutting the application down cancels a pending lock.
class VaultSession internal constructor(
    private val file: VaultFile,
    private val clipboard: SessionClipboard,
    private val scope: CoroutineScope,
    private val lockDelay: LockDelay,
) {
    constructor(file: VaultFile, clipboard: SessionClipboard, scope: CoroutineScope) :
        this(file, clipboard, scope, SuspendingLockDelay)

    // Guards every field below. A lock arrives from the tray while an unlock runs on a worker, and
    // the two must not interleave over one key.
    private val guard = Any()

    // Serialises the suspending vault operations, which the guard cannot: two derivations installing
    // over one another would leave one set of key holders unzeroed.
    private val vaultWork = Mutex()

    private val _state = MutableStateFlow<SessionState>(
        if (file.exists()) SessionState.Locked() else SessionState.NoVault,
    )

    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var vault: OpenVault? = null

    // The decoded key of every entry, which is the form a lock zeroes. The base32 text it came from
    // is a String nothing can wipe and lives on in the open vault's parsed body.
    private val secrets = mutableMapOf<String, SecureBytes>()

    private var pendingLock: Job? = null

    // Bumped by every lock(). An unlock the lock outran finds its own count stale and destroys what
    // it derived instead of installing it over a vault the user has just closed.
    private var epoch = 0

    // Triggers that arrived mid-derivation, held in order because the policy that says whether each
    // is armed is still encrypted. All are replayed once the body is open.
    private val deferredReasons = mutableListOf<LockReason>()

    suspend fun create(password: CharArray): Outcome<Unit, VaultError> = vaultWork.withLock {
        // Not atomic with the write below, and not meant to be: it stops the create flow from
        // replacing a vault, not a second process from doing so mid-write.
        if (file.exists()) return@withLock Outcome.Failure(VaultError.VaultFileExists)
        val attempt = beginDerivation()
        // Two derivations, one to wrap the key and one to open what was written. Creation happens
        // once per vault, and carrying the key over instead would mean a codec that hands it back.
        val opened = withContext(Dispatchers.Default) {
            VaultCodec.create(password)
                .flatMap { bytes -> file.write(bytes).map { bytes } }
                .flatMap { bytes -> VaultCodec.open(bytes, password) }
        }
        adopt(opened, attempt)
    }

    suspend fun unlock(password: CharArray): Outcome<Unit, VaultError> = vaultWork.withLock {
        val attempt = beginDerivation()
        val opened = withContext(Dispatchers.Default) {
            file.read().flatMap { bytes -> VaultCodec.open(bytes, password) }
        }
        adopt(opened, attempt)
    }

    // The current password is checked by a derivation inside the codec rather than against anything
    // the session holds, so a wrong one leaves the file, the key material and the state where it is.
    suspend fun changePassword(currentPassword: CharArray, newPassword: CharArray): Outcome<Unit, VaultError> =
        rewritten(newPassword) { bytes -> VaultCodec.changePassword(bytes, currentPassword, newPassword) }

    // A rotation replaces the key the body is encrypted under, which a password change leaves in
    // place and a leaked one therefore survives.
    suspend fun rotateDek(password: CharArray): Outcome<Unit, VaultError> =
        rewritten(password) { bytes -> VaultCodec.rotateDek(bytes, password) }

    // The vault takes the policy only once the file has, so a refused write leaves the stored policy
    // and the published one in agreement.
    suspend fun setPolicy(policy: SecurityPolicy): Outcome<Unit, VaultError> = onOpenVault { open ->
        commit(open, open.body.copy(policy = policy), Unit)
    }

    // A copy of the vault file, for the caller to place where it likes. What leaves is the ciphertext
    // the file already holds, so it carries no disclosure gate.
    suspend fun exportEncrypted(): Outcome<ByteArray, VaultError> = withContext(Dispatchers.IO) {
        // Behind the lock the session's writes take, so the copy is of the file its state describes.
        vaultWork.withLock { file.read() }
    }

    // Installs nothing: neither outcome replaces the data key, touches a decoded secret, bumps the
    // epoch or moves the published state, so a wrong answer at the gate is not a lock.
    suspend fun verifyPassword(password: CharArray): Outcome<Unit, VaultError> = vaultWork.withLock {
        // A closed vault has nothing to disclose, and answering here would turn the gate into an
        // unlock that leaves the session locked.
        val header = exclusively(guard) { vault?.header } ?: return@withLock Outcome.Failure(VaultError.VaultClosed)
        withContext(Dispatchers.Default) { VaultCodec.verify(header, password) }
    }

    // A complete credential leaves the vault here. The password is checked first and the entry is
    // reached only once it has passed, so no path returns a secret on a failed check.
    suspend fun discloseUri(id: String, password: CharArray): Outcome<String, VaultError> =
        verifyPassword(password).flatMap { uriFor(id) }

    fun lock(reason: LockReason) {
        exclusively(guard) {
            pendingLock?.cancel()
            pendingLock = null
            deferredReasons.clear()
            // Zeroed before the state says locked, so nothing ever observes a locked session that
            // is still holding a key.
            release()
            epoch++
            // A session with no vault has no key to discard and no password to ask for, so a lock
            // leaves it where it is rather than sending the UI to an unlock screen for no file.
            if (_state.value !is SessionState.NoVault) {
                _state.value = SessionState.Locked(reason)
            }
        }
        // Outside the guard: the platform clipboard blocks under contention, and holding the guard
        // across it would stall every other operation on the session.
        clipboard.clearIfHoldsOwnValue()
    }

    fun scheduleLock(reason: LockReason) {
        when (val grace = graceFor(reason)) {
            null -> Unit
            0 -> lock(reason)
            else -> startLockTimer(reason, grace)
        }
    }

    fun cancelScheduledLock() = exclusively(guard) {
        pendingLock?.cancel()
        pendingLock = null
        deferredReasons.clear()
    }

    suspend fun addEntry(entry: VaultEntry): Outcome<Unit, VaultError> = onOpenVault { open ->
        if (open.body.entries.any { it.id == entry.id }) {
            // Two entries under one id leave the map of decoded keys holding one of them, with
            // nothing able to reach the other to zero it.
            Outcome.Failure(VaultError.InvalidEntry("an entry with that id is already in the vault"))
        } else {
            add(open, entry)
        }
    }

    // The secret is not among the fields an edit carries, so the key decoded for this entry is still
    // its key afterwards and the holder the session keeps for it is untouched.
    suspend fun editEntry(id: String, edit: EntryEdit): Outcome<Unit, VaultError> = onOpenVault { open ->
        val entry = open.body.entries.find { it.id == id }
            ?: return@onOpenVault Outcome.Failure(VaultError.NoSuchEntry)
        entry.edited(edit).flatMap { edited -> commit(open, open.body.replacing(edited), Unit) }
    }

    suspend fun deleteEntry(id: String): Outcome<Unit, VaultError> = onOpenVault { open ->
        val remaining = open.body.entries.filterNot { it.id == id }
        if (remaining.size == open.body.entries.size) {
            return@onOpenVault Outcome.Failure(VaultError.NoSuchEntry)
        }
        // The gap this leaves in the order is closed by the renumbering the write does.
        val outcome = commit(open, open.body.copy(entries = remaining), Unit)
        // The key is zeroed only once the file has stopped carrying the entry it belongs to. A write
        // that fails leaves that entry in the vault, and the session still generates its codes.
        if (outcome is Outcome.Success) secrets.remove(id)?.destroy()
        outcome
    }

    // The index is a position in the list the account list draws, which is the entries in order.
    suspend fun moveEntry(id: String, toIndex: Int): Outcome<Unit, VaultError> = onOpenVault { open ->
        val ordered = open.body.entries.sortedBy { it.orderIndex }.toMutableList()
        val from = ordered.indexOfFirst { it.id == id }
        if (from < 0) return@onOpenVault Outcome.Failure(VaultError.NoSuchEntry)
        val moved = ordered.removeAt(from)
        // A drop lands somewhere in the list it was dragged over, and an index past either end of it
        // is that end.
        ordered.add(toIndex.coerceIn(0, ordered.size), moved)
        val renumbered = ordered.mapIndexed { index, entry -> entry.copy(orderIndex = index) }
        commit(open, open.body.copy(entries = renumbered), Unit)
    }

    // The advanced counter reaches the file before the code is returned: the reverse order would let
    // a crash between the two reissue a code the server has already consumed.
    suspend fun generateHotpCode(id: String): Outcome<String, VaultError> = onOpenVault { open ->
        val entry = open.body.entries.find { it.id == id }
            ?: return@onOpenVault Outcome.Failure(VaultError.NoSuchEntry)
        // A counter belongs to a hotp entry and a period to a totp one, and the model admits no
        // entry carrying both or neither.
        val counter = entry.counter ?: return@onOpenVault Outcome.Failure(
            VaultError.InvalidEntry("only a hotp entry has a counter to advance"),
        )
        if (counter == ULong.MAX_VALUE) {
            // ULong arithmetic wraps, and a counter wrapping to zero would reissue every code the
            // server has already consumed. Editing the counter is how this entry moves again.
            return@onOpenVault Outcome.Failure(VaultError.InvalidEntry("the counter is at its maximum"))
        }
        // The key is lent for the length of the generation and never copied out of the block.
        val code = secrets[id]?.lendOrNull { key -> Hotp.generate(key, counter, entry.algorithm, entry.digits) }
            ?: return@onOpenVault Outcome.Failure(VaultError.VaultClosed)
        commit(open, open.body.replacing(entry.copy(counter = counter + 1uL)), code)
    }

    // The secret goes into the URI as the vault stores it: re-encoding the decoded key bytes would
    // give the same key a different spelling, losing the original's case, padding and whitespace.
    private suspend fun uriFor(id: String): Outcome<String, VaultError> = onOpenVault { open ->
        val entry = open.body.entries.find { it.id == id }
            ?: return@onOpenVault Outcome.Failure(VaultError.NoSuchEntry)
        Outcome.Success(entry.toOtpAuthUri().build())
    }

    // Internal so a test can hold on to the array a lock has to zero. Nothing outside the module
    // reaches the key, and the lend is bounded by the block the way the codec's is.
    internal fun <T> useDek(block: (ByteArray) -> T): T? = exclusively(guard) { vault?.useDek(block) }

    // The decoded secret is lent for the length of a block rather than handed over, so a lock that
    // arrives during a code generation waits for it instead of zeroing the key under it.
    internal fun <T> withSecret(id: String, block: (ByteArray) -> T): T? =
        exclusively(guard) { secrets[id]?.lendOrNull(block) }

    // Neither the key nor an entry's secret appears in any rendering of a session.
    override fun toString(): String = "VaultSession(state=${_state.value::class.simpleName})"

    // A rewrite produces a new header, and the header an open vault holds is the associated data of
    // every write made through it, so the session adopts the vault reopened from the bytes written.
    private suspend fun rewritten(
        password: CharArray,
        rewrite: (ByteArray) -> Outcome<ByteArray, VaultError>,
    ): Outcome<Unit, VaultError> = vaultWork.withLock {
        // Without this a settings action would open a locked vault, which is the unlock screen's job.
        if (exclusively(guard) { vault } == null) return@withLock Outcome.Failure(VaultError.VaultClosed)
        val attempt = beginDerivation()
        val opened = withContext(Dispatchers.Default) {
            file.read()
                .flatMap(rewrite)
                .flatMap { bytes -> file.write(bytes).map { bytes } }
                .flatMap { bytes -> VaultCodec.open(bytes, password) }
        }
        adopt(opened, attempt)
    }

    // Runs whole under the guard, so a lock arriving during an operation waits for it rather than
    // zeroing the key the seal is running under.
    private suspend fun <T> onOpenVault(block: (OpenVault) -> Outcome<T, VaultError>): Outcome<T, VaultError> =
        withContext(Dispatchers.IO) {
            vaultWork.withLock {
                exclusively<Outcome<T, VaultError>>(guard) {
                    val open = vault ?: return@exclusively Outcome.Failure(VaultError.VaultClosed)
                    block(open)
                }
            }
        }

    // The file holds the new body before any of the session's own state moves, so the entries, the
    // decoded keys and the published state describe the file as it stands, failure paths included.
    private fun <T> commit(open: OpenVault, body: VaultBody, value: T): Outcome<T, VaultError> {
        // Renumbered here rather than left to the encode, so the body published below is the body
        // the file carries rather than one that differs from it by an order index.
        val next = body.renumbered()
        val bytes = when (val encoded = VaultCodec.encode(open, next)) {
            is Outcome.Failure -> return encoded
            is Outcome.Success -> encoded.value
        }
        val written = file.write(bytes)
        if (written is Outcome.Failure) return written
        open.body = next
        publish(open)
        return Outcome.Success(value)
    }

    private fun add(open: OpenVault, entry: VaultEntry): Outcome<Unit, VaultError> =
        when (val decoded = Base32.decode(entry.secret)) {
            is Outcome.Failure -> decoded

            is Outcome.Success -> {
                // Every write renumbers densely from zero, so the count is the index past the end.
                val placed = entry.copy(orderIndex = open.body.entries.size)
                installedOrZeroed(entry.id, SecureBytes.adopt(decoded.value)) {
                    commit(open, open.body.copy(entries = open.body.entries + placed), Unit)
                }
            }
        }

    // The decoded key joins the session's holders only on the path that commits the write; every
    // other path zeroes it. Internal so a test can watch the zeroing.
    internal fun <T> installedOrZeroed(
        id: String,
        secret: SecureBytes,
        block: () -> Outcome<T, VaultError>,
    ): Outcome<T, VaultError> {
        var installed = false
        return try {
            block().also { outcome ->
                if (outcome is Outcome.Success) {
                    secrets[id] = secret
                    installed = true
                }
            }
        } finally {
            if (!installed) secret.destroy()
        }
    }

    private fun publish(open: OpenVault) {
        _state.value = SessionState.Unlocked(
            entries = open.body.entries.sortedBy { it.orderIndex }.map { it.withoutSecret() },
            policy = open.body.policy,
        )
    }

    private fun beginDerivation(): Attempt = exclusively(guard) {
        val previous = _state.value
        _state.value = SessionState.Unlocking
        Attempt(previous, epoch)
    }

    private fun adopt(opened: Outcome<OpenVault, VaultError>, attempt: Attempt): Outcome<Unit, VaultError> {
        val vault = when (opened) {
            is Outcome.Failure -> return abandon(opened, attempt)
            is Outcome.Success -> opened.value
        }
        val decoded = mutableMapOf<String, SecureBytes>()
        decode(vault, decoded)?.let { error ->
            discard(vault, decoded)
            return abandon(Outcome.Failure(error), attempt)
        }
        if (!install(vault, decoded, attempt)) {
            discard(vault, decoded)
            // A lock overtook this derivation and has already zeroed what it held. The vault is
            // shut, and opening it again is another password entry.
            return Outcome.Failure(VaultError.VaultClosed)
        }
        applyDeferredLock()
        return Outcome.Success(Unit)
    }

    // A base32 secret is a String and nothing can wipe one. Decoding at the unlock puts the bytes in
    // a holder that zeroes on destroy, leaving the text only in the open vault's parsed body.
    private fun decode(vault: OpenVault, into: MutableMap<String, SecureBytes>): VaultError? {
        for (entry in vault.body.entries) {
            decode(entry, into)?.let { return it }
        }
        return null
    }

    private fun decode(entry: VaultEntry, into: MutableMap<String, SecureBytes>): VaultError? {
        // A second entry under one id would drop the first holder without zeroing it, leaving a key
        // in the heap that nothing can reach to destroy.
        if (into.containsKey(entry.id)) return VaultError.Corrupt("two entries share an id")
        return when (val decoded = Base32.decode(entry.secret)) {
            is Outcome.Failure -> decoded.error

            is Outcome.Success -> {
                into[entry.id] = SecureBytes.adopt(decoded.value)
                null
            }
        }
    }

    private fun install(opened: OpenVault, decoded: Map<String, SecureBytes>, attempt: Attempt): Boolean =
        exclusively(guard) {
            if (epoch != attempt.epoch) {
                // A lock landed while this key was being derived. Installing it would hand back the
                // vault the user has just closed, and the caller destroys what this refuses.
                false
            } else {
                // An unlock over an open vault replaces its material rather than stacking on it.
                release()
                vault = opened
                secrets.putAll(decoded)
                publish(opened)
                true
            }
        }

    private fun abandon(failure: Outcome.Failure<VaultError>, attempt: Attempt): Outcome<Unit, VaultError> {
        exclusively(guard) {
            // A lock that landed during the derivation owns the state and keeps it.
            if (epoch == attempt.epoch) _state.value = attempt.previous
        }
        return failure
    }

    // Replayed in arrival order against the policy that is now readable. The first armed one takes
    // effect and the rest fall away against the lock or the timer it leaves behind.
    private fun applyDeferredLock() {
        val reasons = exclusively(guard) { deferredReasons.toList().also { deferredReasons.clear() } }
        reasons.forEach { scheduleLock(it) }
    }

    // Every caller runs under the guard: this leaves the session without a key for as long as it
    // takes to install the next one.
    private fun release() {
        secrets.values.forEach { it.destroy() }
        secrets.clear()
        vault?.close()
        vault = null
    }

    private fun discard(opened: OpenVault, decoded: MutableMap<String, SecureBytes>) {
        decoded.values.forEach { it.destroy() }
        decoded.clear()
        opened.close()
    }

    // Null means there is nothing to schedule: a vault that is not open has no policy to read and
    // no key to discard, and a disarmed reason is one the user switched off.
    private fun graceFor(reason: LockReason): Int? = exclusively(guard) {
        when (val current = _state.value) {
            is SessionState.Unlocking -> {
                if (reason !in deferredReasons) deferredReasons += reason
                null
            }

            is SessionState.Unlocked -> armedGrace(reason, current.policy)

            else -> null
        }
    }

    private fun armedGrace(reason: LockReason, policy: SecurityPolicy): Int? =
        if (reason.isArmedBy(policy)) reason.graceSeconds(policy) else null

    private fun startLockTimer(reason: LockReason, grace: Int) {
        // Lazy, so the timer runs only once it is the pending one: one that finished early would
        // otherwise clear the field the assignment below is about to write.
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            lockDelay.elapse(grace)
            lock(reason)
        }
        val isPending = exclusively(guard) {
            // A trigger arriving after another has set a deadline never pushes it out, and a vault
            // locked between the policy read and here needs no timer at all.
            if (pendingLock == null && _state.value is SessionState.Unlocked) {
                pendingLock = timer
                true
            } else {
                false
            }
        }
        if (isPending) timer.start() else timer.cancel()
    }
}
