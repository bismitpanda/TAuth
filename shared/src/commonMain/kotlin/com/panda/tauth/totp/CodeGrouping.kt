package com.panda.tauth.totp

private const val GROUP_SEPARATOR = ' '

// One break, placed so the left group is never the shorter: six digits split three and three, seven
// split four and three, eight split four and four.
fun groupedCode(code: String): String {
    val tail = code.length / 2
    if (tail == 0) return code
    return code.substring(0, code.length - tail) + GROUP_SEPARATOR + code.substring(code.length - tail)
}
