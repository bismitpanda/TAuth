package com.panda.tauth.crypto

import kotlin.concurrent.Volatile

// Every path that finishes with key material must call destroy(), error paths included.
class SecureBytes private constructor(private val bytes: ByteArray) : AutoCloseable {
    private val guard = Any()

    // Written after the zeroing and read before the array, so a thread seeing the flag set also
    // sees zeroed bytes rather than a cached view of a live key.
    @Volatile
    private var destroyed = false

    val isDestroyed: Boolean get() = destroyed

    // The only route to the bytes: a destroy() waits for the block instead of zeroing the array under
    // it. A block that keeps the array past its own return holds bytes a later destroy() zeroes.
    fun <T> lendOrNull(block: (ByteArray) -> T): T? = exclusively(guard) {
        if (destroyed) null else block(bytes)
    }

    fun destroy() = exclusively(guard) {
        bytes.fill(0)
        destroyed = true
    }

    override fun close() = destroy()

    override fun toString(): String = "SecureBytes(size=${bytes.size}, destroyed=$destroyed)"

    companion object {
        // Ownership passes to the returned instance. Copying instead would leave a second live copy
        // of the key that nothing zeroes.
        fun adopt(bytes: ByteArray): SecureBytes = SecureBytes(bytes)
    }
}
