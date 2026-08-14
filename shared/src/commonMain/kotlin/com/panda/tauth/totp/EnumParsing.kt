package com.panda.tauth.totp

internal inline fun <reified E : Enum<E>> parseIgnoreCase(value: String): E? =
    enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }

// The ABNF's DIGIT is %x30-39. toIntOrNull accepts every Unicode Nd digit and a leading '+', which
// would give one payload two meanings across readers.
private fun String.isAsciiDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

internal fun String.toAsciiIntOrNull(): Int? = if (isAsciiDigits()) toIntOrNull() else null

internal fun String.toAsciiULongOrNull(): ULong? = if (isAsciiDigits()) toULongOrNull() else null
