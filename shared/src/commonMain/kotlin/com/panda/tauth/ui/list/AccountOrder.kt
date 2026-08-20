package com.panda.tauth.ui.list

import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.settings.SortOrder
import kotlin.math.roundToInt

fun matchesQuery(entry: UnlockedEntry, query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    return entry.accountName.contains(trimmed, ignoreCase = true) ||
        entry.issuer?.contains(trimmed, ignoreCase = true) == true
}

// Manual order is the one the vault stores; the other two are views over it and write nothing.
fun sorted(entries: List<UnlockedEntry>, order: SortOrder, isDescending: Boolean = false): List<UnlockedEntry> {
    val named = when (order) {
        SortOrder.MANUAL -> entries.sortedBy { it.orderIndex }

        SortOrder.ISSUER -> entries.sortedWith(
            compareBy<UnlockedEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.issuer ?: it.accountName }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.accountName },
        )

        SortOrder.RECENTLY_ADDED -> entries.sortedByDescending { it.createdAt }
    }
    return if (isDescending && order.hasDirection) named.reversed() else named
}

val SortOrder.hasDirection: Boolean get() = this != SortOrder.MANUAL

fun dropIndex(from: Int, draggedPixels: Float, rowPitchPixels: Float, count: Int): Int {
    if (rowPitchPixels <= 0f || count <= 0) return from
    return (from + (draggedPixels / rowPitchPixels).roundToInt()).coerceIn(0, count - 1)
}

// The distance from one row to the next, which is a row's height plus the gap the list arranges with.
// Dividing a drag by the height alone counts every gap it crossed as extra travel.
fun rowPitch(offsets: List<Int>, firstSize: Int): Int = if (offsets.size >= 2) offsets[1] - offsets[0] else firstSize
