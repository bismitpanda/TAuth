package com.panda.tauth.ui.list

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

const val NO_ROW = -1

data class RowBounds(val index: Int, val top: Int, val height: Int)

data class RowMove(val id: String, val toIndex: Int)

fun dropIndex(centerY: Float, rows: List<RowBounds>, fallback: Int): Int =
    (rows.firstOrNull { centerY < it.top + it.height } ?: rows.lastOrNull())?.index ?: fallback

fun edgeScroll(centerY: Float, viewportHeight: Int, margin: Float, perFrame: Float): Float {
    if (viewportHeight <= 0 || margin <= 0f) return 0f
    val isAbove = centerY < margin
    val past = if (isAbove) margin - centerY else centerY - (viewportHeight - margin)
    if (past <= 0f) return 0f
    val speed = perFrame * (past / margin).coerceAtMost(1f)
    return if (isAbove) -speed else speed
}

fun <T> reordered(items: List<T>, from: Int, to: Int): List<T> {
    if (from !in items.indices || to !in items.indices || from == to) return items
    val moved = items.toMutableList()
    moved.add(to, moved.removeAt(from))
    return moved
}

// A row left in its stored place is carried off-screen by an edge scroll, and a lazy list disposes
// the item it left behind, gesture and all. So the list is rearranged under the pointer as it goes.
@Stable
class RowDragState {
    var draggedId: String? by mutableStateOf(null)
        private set

    var startIndex by mutableStateOf(NO_ROW)
        private set

    var targetIndex by mutableStateOf(NO_ROW)
        private set

    private var traveled by mutableStateOf(0f)
    private var startTop = 0
    private var height = 0

    val isDragging: Boolean get() = draggedId != null

    val centerY: Float get() = startTop + traveled + height / 2f

    fun start(id: String, index: Int, rows: List<RowBounds>) {
        val row = rows.firstOrNull { it.index == index } ?: return
        draggedId = id
        startIndex = index
        targetIndex = index
        startTop = row.top
        height = row.height
        traveled = 0f
    }

    fun dragBy(amountY: Float, rows: List<RowBounds>) {
        if (!isDragging) return
        traveled += amountY
        retarget(rows)
    }

    fun retarget(rows: List<RowBounds>) {
        if (isDragging) targetIndex = dropIndex(centerY, rows, targetIndex)
    }

    fun translationFor(currentTop: Int): Float = if (isDragging) startTop + traveled - currentTop else 0f

    fun release(): RowMove? {
        val move = draggedId
            ?.takeIf { targetIndex != startIndex && targetIndex != NO_ROW }
            ?.let { RowMove(it, targetIndex) }
        draggedId = null
        traveled = 0f
        if (move == null) settle()
        return move
    }

    fun settle() {
        draggedId = null
        startIndex = NO_ROW
        targetIndex = NO_ROW
        traveled = 0f
    }
}
