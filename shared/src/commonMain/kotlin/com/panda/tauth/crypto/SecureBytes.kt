package com.panda.tauth.crypto

import kotlin.concurrent.Volatile

// Every path that finishes with key material must call destroy(), error paths included.
class SecureBytes private constructor(private val bytes: ByteArray) : AutoCloseable {
    // Written after the zeroing and read before the array, so a thread seeing the flag set also
    // sees zeroed bytes rather than a cached view of a live key.
    @Volatile
    private var destroyed = false

    val isDestroyed: Boolean get() = destroyed

    // The caller must not retain the array past destroy().
    fun reveal(): ByteArray {
        check(!destroyed) { "SecureBytes has been destroyed" }
        return bytes
    }

    fun destroy() {
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
