package com.panda.tauth.totp

internal inline fun <reified E : Enum<E>> parseIgnoreCase(value: String): E? =
    enumValues<E>().firstOrNull { equalsAsciiIgnoreCase(it.name, value) }

// The otpauth ABNF admits only VCHAR, and Kotlin's ignoreCase folding is Unicode-wide: it folds
// U+017F onto 'S', so `algorithm=%C5%BFHA256` would name SHA-256 while carrying a character the
// grammar has no room for.
internal fun equalsAsciiIgnoreCase(name: String, value: String): Boolean =
    name.length == value.length && name.indices.all { name[it].asciiUppercase() == value[it].asciiUppercase() }

private fun Char.asciiUppercase(): Char = if (this in 'a'..'z') 'A' + (this - 'a') else this

// The ABNF's DIGIT is %x30-39. toIntOrNull accepts every Unicode Nd digit and a leading '+', which
// would give one payload two meanings across readers.
private fun String.isAsciiDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

internal fun String.toAsciiIntOrNull(): Int? = if (isAsciiDigits()) toIntOrNull() else null

internal fun String.toAsciiULongOrNull(): ULong? = if (isAsciiDigits()) toULongOrNull() else null
