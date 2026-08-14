package com.panda.tauth.crypto

// The only randomness source for salts, nonces, keys and identifiers.
expect fun secureRandomBytes(size: Int): ByteArray
