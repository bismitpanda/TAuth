package com.panda.tauth.crypto

import java.security.SecureRandom
import kotlin.concurrent.Volatile

// Every salt, nonce, key and vault id in the process is drawn from this instance. Entry ids are not:
// VaultEntry draws those from Uuid.generateV7(). The declared type excludes kotlin.random.Random and
// java.util.Random and nothing further — a SecureRandom given a seed through the setSeed it inherits
// is still a SecureRandom.
@Volatile
private var randomnessSource: SecureRandom = SecureRandom()

// The only assignment to the generator: it installs one for the length of the block and puts the
// process instance back on the way out, so no caller can leave a stand-in serving the draws after it.
internal fun <T> withRandomnessSource(source: SecureRandom, block: () -> T): T {
    val original = randomnessSource
    randomnessSource = source
    return try {
        block()
    } finally {
        randomnessSource = original
    }
}

actual fun secureRandomBytes(size: Int): ByteArray {
    require(size > 0) { "size must be positive" }
    val bytes = ByteArray(size)
    randomnessSource.nextBytes(bytes)
    return bytes
}
