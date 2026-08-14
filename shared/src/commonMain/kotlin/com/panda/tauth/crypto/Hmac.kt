package com.panda.tauth.crypto

import com.panda.tauth.totp.HashAlgorithm

// RFC 2104. The key must be non-empty; an empty decoded secret is rejected before it reaches here.
expect fun hmac(algorithm: HashAlgorithm, key: ByteArray, message: ByteArray): ByteArray
