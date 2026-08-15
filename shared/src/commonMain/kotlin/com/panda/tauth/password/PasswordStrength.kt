package com.panda.tauth.password

// The shortest master password the create flow accepts. It is the one length rule that refuses a
// password; every rule below only describes one.
const val MIN_MASTER_PASSWORD_LENGTH = 8

private const val LONG_LENGTH = 12
private const val VERY_LONG_LENGTH = 16

private const val FAIR_SCORE = 3
private const val GOOD_SCORE = 5
private const val STRONG_SCORE = 7

enum class PasswordStrength {
    Weak,
    Fair,
    Good,
    Strong,
}

// Advisory: it describes a password and refuses none. The password stays a CharArray throughout; a
// String built from it would sit in the heap with nothing able to zero it.
fun passwordStrength(password: CharArray): PasswordStrength {
    if (isCommonPassword(password)) return PasswordStrength.Weak
    val score = lengthPoints(password.size) + classPoints(password)
    return when {
        score >= STRONG_SCORE -> PasswordStrength.Strong
        score >= GOOD_SCORE -> PasswordStrength.Good
        score >= FAIR_SCORE -> PasswordStrength.Fair
        else -> PasswordStrength.Weak
    }
}

private fun lengthPoints(length: Int): Int {
    var points = 0
    if (length >= MIN_MASTER_PASSWORD_LENGTH) points++
    if (length >= LONG_LENGTH) points++
    if (length >= VERY_LONG_LENGTH) points++
    return points
}

private fun classPoints(password: CharArray): Int {
    var hasLower = false
    var hasUpper = false
    var hasDigit = false
    var hasOther = false
    for (character in password) {
        when {
            character.isLowerCase() -> hasLower = true
            character.isUpperCase() -> hasUpper = true
            character.isDigit() -> hasDigit = true
            else -> hasOther = true
        }
    }
    return listOf(hasLower, hasUpper, hasDigit, hasOther).count { it }
}
