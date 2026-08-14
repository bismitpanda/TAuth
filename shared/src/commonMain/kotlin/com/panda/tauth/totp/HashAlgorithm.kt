package com.panda.tauth.totp

import kotlinx.serialization.Serializable

@Serializable
enum class HashAlgorithm {
    SHA1,
    SHA256,
    SHA512,
    ;

    companion object {
        fun parse(value: String): HashAlgorithm? = parseIgnoreCase<HashAlgorithm>(value)
    }
}
