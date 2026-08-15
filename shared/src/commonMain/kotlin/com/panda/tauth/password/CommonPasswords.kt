package com.panda.tauth.password

// The passwords at the head of every published breach corpus, held lowercase. The meter it feeds is
// advisory, so a miss costs nothing but an optimistic reading.
private val COMMON_PASSWORDS: List<String> = listOf(
    "123456",
    "123456789",
    "12345678",
    "1234567890",
    "1234567",
    "12345",
    "123123",
    "111111",
    "000000",
    "password",
    "password1",
    "passw0rd",
    "qwerty",
    "qwerty123",
    "qwertyuiop",
    "abc123",
    "iloveyou",
    "letmein",
    "welcome",
    "monkey",
    "dragon",
    "sunshine",
    "princess",
    "football",
    "baseball",
    "superman",
    "starwars",
    "trustno1",
    "shadow",
    "master",
    "admin",
    "login",
)

// The password is matched character by character against each candidate. Building a String from it
// to look up in a set would leave a copy of the password in the heap that nothing can zero.
fun isCommonPassword(password: CharArray): Boolean = COMMON_PASSWORDS.any { candidate -> matches(candidate, password) }

// Case-insensitive because the list holds lowercase forms, and capitalising a common password
// leaves it as guessable as it was.
private fun matches(candidate: String, password: CharArray): Boolean {
    if (candidate.length != password.size) return false
    for (index in candidate.indices) {
        if (candidate[index] != password[index].lowercaseChar()) return false
    }
    return true
}
