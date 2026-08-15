package com.panda.tauth.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

private const val MASK = '•'

private fun masked(count: Int) = MASK.toString().repeat(count)

private fun holding(text: String, capacity: Int = 32) = PasswordFieldState(capacity).apply { replace(0, 0, text) }

// Stands in for a render that reaches the field: what the field is given is what its next edit is
// measured against.
private fun PasswordFieldState.rendered(isRevealed: Boolean = false) =
    renderText(isRevealed, MASK).also { noteRendered(it) }

class PasswordFieldStateTest {
    @Test
    fun `typing appends the characters`() {
        val state = holding("ab")

        state.replace(2, 2, "c")

        assertContentEquals(charArrayOf('a', 'b', 'c'), state.copyValue())
    }

    @Test
    fun `an insertion lands where the cursor is`() {
        val state = holding("ac")

        state.replace(1, 1, "b")

        assertContentEquals(charArrayOf('a', 'b', 'c'), state.copyValue())
    }

    @Test
    fun `a deletion removes the character it covers`() {
        val state = holding("abc")

        state.replace(1, 2, "")

        assertContentEquals(charArrayOf('a', 'c'), state.copyValue())
    }

    @Test
    fun `a deletion zeroes the characters it removed`() {
        val state = holding("abcd")

        state.replace(2, 4, "")

        assertTrue(state.chars.drop(2).all { it == Char.MIN_VALUE })
    }

    @Test
    fun `replacing a selection substitutes the characters it covered`() {
        val state = holding("abcd")

        state.replace(1, 3, "xy")

        assertContentEquals(charArrayOf('a', 'x', 'y', 'd'), state.copyValue())
    }

    @Test
    fun `an edit past the end of the text lands at the end`() {
        val state = holding("abc")

        state.replace(9, 12, "d")

        assertContentEquals(charArrayOf('a', 'b', 'c', 'd'), state.copyValue())
    }

    @Test
    fun `clearing leaves no characters`() {
        val state = holding("abc")

        state.clear()

        assertEquals(0, state.copyValue().size)
    }

    @Test
    fun `clearing zeroes the array`() {
        val state = holding("abc")

        state.clear()

        assertTrue(state.chars.all { it == Char.MIN_VALUE })
    }

    @Test
    fun `destroying zeroes the array`() {
        val state = holding("abc")

        state.destroy()

        assertTrue(state.chars.all { it == Char.MIN_VALUE })
    }

    @Test
    fun `a destroyed holder takes no further characters`() {
        val state = holding("abc")

        state.destroy()
        state.replace(0, 0, "de")

        assertEquals(0, state.copyValue().size)
    }

    @Test
    fun `growing zeroes the array it grew out of`() {
        val state = holding("abcd", capacity = 4)
        val outgrown = state.chars

        state.replace(4, 4, "e")

        assertTrue(outgrown.all { it == Char.MIN_VALUE })
    }

    @Test
    fun `growing carries the characters over`() {
        val state = holding("abcd", capacity = 4)

        state.replace(4, 4, "e")

        assertContentEquals(charArrayOf('a', 'b', 'c', 'd', 'e'), state.copyValue())
    }

    @Test
    fun `the value handed out is a copy`() {
        val state = holding("abc")

        val handedOut = state.copyValue()

        assertNotSame(state.chars, handedOut)
    }

    @Test
    fun `zeroing the value handed out leaves the holder intact`() {
        val state = holding("abc")

        state.copyValue().fill(Char.MIN_VALUE)

        assertContentEquals(charArrayOf('a', 'b', 'c'), state.copyValue())
    }

    @Test
    fun `toString renders no character of the password`() {
        val state = holding("ZQX!7")

        val rendered = state.toString()

        assertFalse("ZQX!7".any { rendered.contains(it) })
    }

    @Test
    fun `a character typed into the masked field lands at the cursor`() {
        val state = holding("abc")
        state.rendered()

        state.applyEdit(TextFieldValue("${MASK}x$MASK$MASK", TextRange(2)))

        assertContentEquals(charArrayOf('a', 'x', 'b', 'c'), state.copyValue())
    }

    @Test
    fun `a backspace in the masked field removes the character before the cursor`() {
        val state = holding("abcde")
        state.rendered()

        state.applyEdit(TextFieldValue(masked(4), TextRange(2)))

        assertContentEquals(charArrayOf('a', 'b', 'd', 'e'), state.copyValue())
    }

    @Test
    fun `a character typed over a selection in the masked field replaces it`() {
        val state = holding("abcde")
        state.rendered()

        state.applyEdit(TextFieldValue("$MASK${MASK}x$MASK", TextRange(3)))

        assertContentEquals(charArrayOf('a', 'b', 'x', 'e'), state.copyValue())
    }

    @Test
    fun `deleting the whole masked field empties the holder`() {
        val state = holding("abcde")
        state.rendered()

        state.applyEdit(TextFieldValue("", TextRange(0)))

        assertEquals(0, state.copyValue().size)
    }

    @Test
    fun `moving the cursor alone changes no characters`() {
        val state = holding("abcde")
        state.rendered()

        state.applyEdit(TextFieldValue(masked(5), TextRange(1)))

        assertContentEquals(charArrayOf('a', 'b', 'c', 'd', 'e'), state.copyValue())
    }

    @Test
    fun `the cursor follows what the field reports`() {
        val state = holding("abcde")
        state.rendered()

        state.applyEdit(TextFieldValue(masked(5), TextRange(1, 4)))

        assertEquals(TextRange(1, 4), state.selection)
    }

    @Test
    fun `a forward delete removes the character after the cursor`() {
        val state = holding("abc")
        state.rendered()

        state.applyEdit(TextFieldValue(masked(2), TextRange(1)))

        assertContentEquals(charArrayOf('a', 'c'), state.copyValue())
    }

    @Test
    fun `a pasted run lands whole at the cursor`() {
        val state = holding("ad")
        state.rendered()

        state.applyEdit(TextFieldValue("${MASK}bc$MASK", TextRange(3)))

        assertContentEquals(charArrayOf('a', 'b', 'c', 'd'), state.copyValue())
    }

    @Test
    fun `a mask character typed into the masked field lands as itself`() {
        val state = holding("ab")
        state.rendered()

        state.applyEdit(TextFieldValue(masked(3), TextRange(2)))

        assertContentEquals(charArrayOf('a', MASK, 'b'), state.copyValue())
    }

    @Test
    fun `a character typed into the revealed field lands at the cursor`() {
        val state = holding("abc")
        state.rendered(isRevealed = true)

        state.applyEdit(TextFieldValue("abXc", TextRange(3)))

        assertContentEquals(charArrayOf('a', 'b', 'X', 'c'), state.copyValue())
    }

    @Test
    fun `every render replaces the base the next edit is measured against`() {
        val state = holding("abc")
        state.rendered(isRevealed = true)
        state.rendered()

        state.applyEdit(TextFieldValue("${MASK}x$MASK$MASK", TextRange(2)))

        assertContentEquals(charArrayOf('a', 'x', 'b', 'c'), state.copyValue())
    }

    @Test
    fun `an edit on a destroyed holder leaves the cursor inside the text`() {
        val state = holding("abc")
        state.destroy()
        // The field is still on screen: it renders the empty holder and reports the next keystroke.
        state.rendered()

        state.applyEdit(TextFieldValue("x", TextRange(1)))

        assertTrue(state.selection.end <= state.length)
    }

    @Test
    fun `a second edit before the next render is measured against the first`() {
        val state = PasswordFieldState()
        state.rendered()

        state.applyEdit(TextFieldValue("a", TextRange(1)))
        state.applyEdit(TextFieldValue("ab", TextRange(2)))

        assertContentEquals(charArrayOf('a', 'b'), state.copyValue())
    }

    @Test
    fun `an edit with no render behind it is refused`() {
        val state = holding("abc")

        state.applyEdit(TextFieldValue("abcd", TextRange(4)))

        assertContentEquals(charArrayOf('a', 'b', 'c'), state.copyValue())
    }

    @Test
    fun `an edit after a clear is refused until the next render`() {
        val state = holding("abc")
        state.rendered()

        state.clear()
        state.applyEdit(TextFieldValue(masked(4), TextRange(4)))

        assertEquals(0, state.copyValue().size)
    }
}
