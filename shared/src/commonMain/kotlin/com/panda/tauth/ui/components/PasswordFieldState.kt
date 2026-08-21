package com.panda.tauth.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

private const val INITIAL_CAPACITY = 32

// The master password as characters that can be overwritten; a String cannot be. The copy an edit
// arrives in belongs to Compose and to the platform, and nothing here can wipe it.
@Stable
class PasswordFieldState(initialCapacity: Int = INITIAL_CAPACITY) {
    // Growth copies into a larger array and zeroes the one it grew out of, which would otherwise be
    // a full copy of the password that nothing wipes. Visible to tests so that array is observable.
    internal var chars: CharArray = CharArray(initialCapacity.coerceAtLeast(1))
        private set

    var length: Int by mutableIntStateOf(0)
        private set

    var selection: TextRange by mutableStateOf(TextRange.Zero)
        private set

    var isDestroyed: Boolean by mutableStateOf(false)
        private set

    // Every mutation bumps this. The array is not snapshot state, so an edit that leaves the length
    // and the cursor where they were changes nothing else a composable reading the text observes.
    internal var revision: Int by mutableIntStateOf(0)
        private set

    // The text the field holds, which each edit is measured against. Null once the characters move
    // underneath the field with no render since, when an edit's coordinates would address stale text.
    private var base: String? = null

    // The single editing primitive: start == end inserts, an empty source range deletes, and both
    // together replace a selection.
    fun replace(start: Int, end: Int, source: CharSequence, sourceStart: Int = 0, sourceEnd: Int = source.length) {
        if (isDestroyed) return
        val from = start.coerceIn(0, length)
        val to = end.coerceIn(from, length)
        val sourceFrom = sourceStart.coerceIn(0, source.length)
        val sourceTo = sourceEnd.coerceIn(sourceFrom, source.length)
        val inserted = sourceTo - sourceFrom
        val previousLength = length
        val newLength = previousLength - (to - from) + inserted

        ensureCapacity(newLength)
        chars.copyInto(chars, from + inserted, to, previousLength)
        for (offset in 0 until inserted) {
            chars[from + offset] = source[sourceFrom + offset]
        }
        // The tail moved down over its own former positions, leaving live characters above the new
        // length. They are as much of the password as any others and are zeroed here.
        if (newLength < previousLength) {
            chars.fill(Char.MIN_VALUE, newLength, previousLength)
        }

        length = newLength
        selection = TextRange(from + inserted)
        revision++
        base = null
    }

    // A text field reports one contiguous edit and a cursor at its end. Masked, two runs of the mask
    // character are indistinguishable, so the cursor is what says which run changed.
    internal fun applyEdit(after: TextFieldValue) {
        // Refused before anything moves. A destroyed holder writes no character, so a cursor taken
        // from the edit would sit outside the text this holds and outside what the field is given.
        if (isDestroyed) return
        val before = base ?: return
        val text = after.text
        val cursor = after.selection.end.coerceIn(0, text.length)
        val keptTail = text.length - cursor
        val removeEnd = (before.length - keptTail).coerceIn(0, before.length)
        var start = 0
        val sharedPrefixLimit = minOf(removeEnd, cursor)
        while (start < sharedPrefixLimit && before[start] == text[start]) {
            start++
        }
        replace(start, removeEnd, text, start, cursor)
        selection = after.selection
        // The field keeps what it just reported until a render replaces it, and the edit after this
        // one is measured against it.
        base = text
    }

    // Called when the field leaves composition, which a screen can do and undo: the array is zeroed
    // and the holder goes on taking characters when the field comes back.
    fun clear() {
        chars.fill(Char.MIN_VALUE)
        length = 0
        selection = TextRange.Zero
        revision++
        base = null
    }

    // The end of the holder, for the owner that is finished with it. It takes no further character.
    fun destroy() {
        clear()
        isDestroyed = true
    }

    // The caller owns the returned array and zeroes it once the derivation is done; a copy that
    // outlives the KDF call is a copy of the password.
    fun copyValue(): CharArray = chars.copyOf(length)

    // Compared as CharArrays: routing either side through a String would leave a copy of the password
    // nothing can zero. Neither side is a stored secret, so the early exit is no oracle.
    fun matches(other: PasswordFieldState): Boolean {
        val left = copyValue()
        val right = other.copyValue()
        return try {
            left.contentEquals(right)
        } finally {
            left.fill(Char.MIN_VALUE)
            right.fill(Char.MIN_VALUE)
        }
    }

    // The text the field is given. Revealed, the characters go into a String that nothing can wipe;
    // masked, only the mask character does.
    internal fun renderText(isRevealed: Boolean, mask: Char): String =
        CharArray(length) { index -> if (isRevealed) chars[index] else mask }.concatToString()

    // The render the field received, reported from a path that runs only once the composition
    // applies: a render it never received would send the next edit's coordinates to the wrong place.
    internal fun noteRendered(text: String) {
        base = text
    }

    override fun toString(): String = "PasswordFieldState(length=$length, destroyed=$isDestroyed)"

    private fun ensureCapacity(required: Int) {
        if (required <= chars.size) return
        val grown = CharArray(maxOf(required, chars.size * 2))
        chars.copyInto(grown, 0, 0, length)
        chars.fill(Char.MIN_VALUE)
        chars = grown
    }
}
