package com.panda.tauth.password

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Every expected value below is read off the rule: a point per length step at 8, 12 and 16, a point
// per character class, Fair from 3, Good from 5, Strong at 7. Each pair differs in one input.
class PasswordStrengthTest {
    @Test
    fun `a lowercase password at the minimum length is weak`() {
        assertEquals(PasswordStrength.Weak, passwordStrength("abcdefgh".toCharArray()))
    }

    @Test
    fun `an uppercase character adds a class point`() {
        assertEquals(PasswordStrength.Fair, passwordStrength("abcdefghiJ".toCharArray()))
    }

    @Test
    fun `the same length in one case scores below it`() {
        assertEquals(PasswordStrength.Weak, passwordStrength("abcdefghij".toCharArray()))
    }

    @Test
    fun `a lowercase character adds a class point`() {
        assertEquals(PasswordStrength.Fair, passwordStrength("ABCDEFGHIj".toCharArray()))
    }

    @Test
    fun `the same length in upper case alone scores below it`() {
        assertEquals(PasswordStrength.Weak, passwordStrength("ABCDEFGHIJ".toCharArray()))
    }

    @Test
    fun `a digit adds a class point`() {
        assertEquals(PasswordStrength.Fair, passwordStrength("abcdefghij4".toCharArray()))
    }

    @Test
    fun `a symbol adds a class point`() {
        assertEquals(PasswordStrength.Fair, passwordStrength("abcdefghij!".toCharArray()))
    }

    @Test
    fun `eleven lowercase characters score below either`() {
        assertEquals(PasswordStrength.Weak, passwordStrength("abcdefghijk".toCharArray()))
    }

    @Test
    fun `seven characters earn no length point`() {
        assertEquals(PasswordStrength.Fair, passwordStrength("aB3!def".toCharArray()))
    }

    @Test
    fun `the eighth character earns the first length point`() {
        assertEquals(PasswordStrength.Good, passwordStrength("aB3!defg".toCharArray()))
    }

    @Test
    fun `eleven characters carry one length point`() {
        assertEquals(PasswordStrength.Fair, passwordStrength("aB3efghijkl".toCharArray()))
    }

    @Test
    fun `the twelfth character earns a second length point`() {
        assertEquals(PasswordStrength.Good, passwordStrength("aB3efghijklm".toCharArray()))
    }

    @Test
    fun `fifteen characters carry two length points`() {
        assertEquals(PasswordStrength.Good, passwordStrength("aB3!efghijklmno".toCharArray()))
    }

    @Test
    fun `the sixteenth character earns a third length point`() {
        assertEquals(PasswordStrength.Strong, passwordStrength("aB3!efghijklmnop".toCharArray()))
    }

    @Test
    fun `a password off the common list keeps the score its shape earns`() {
        assertEquals(PasswordStrength.Fair, passwordStrength("Passwerd1".toCharArray()))
    }

    @Test
    fun `a common password of the same shape is weak`() {
        assertEquals(PasswordStrength.Weak, passwordStrength("Password1".toCharArray()))
    }

    @Test
    fun `the common list is matched regardless of case`() {
        assertEquals(PasswordStrength.Weak, passwordStrength("PASSWORD1".toCharArray()))
    }

    @Test
    fun `a common password holding a digit for a letter is weak`() {
        assertEquals(PasswordStrength.Weak, passwordStrength("Passw0rd".toCharArray()))
    }

    @Test
    fun `a listed password is common`() {
        assertTrue(isCommonPassword("letmein".toCharArray()))
    }

    @Test
    fun `a listed password with a character appended is not common`() {
        assertFalse(isCommonPassword("letmeinx".toCharArray()))
    }

    @Test
    fun `a prefix of a listed password is not common`() {
        assertFalse(isCommonPassword("letmei".toCharArray()))
    }
}
