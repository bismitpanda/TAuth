package com.panda.tauth.ui.list

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

private val ROWS = listOf(
    RowBounds(index = 0, top = 0, height = 60),
    RowBounds(index = 1, top = 68, height = 100),
    RowBounds(index = 2, top = 176, height = 60),
    RowBounds(index = 3, top = 244, height = 100),
)

private val IDS = listOf("a", "b", "c", "d")

class RowDragTest {
    @Test
    fun `a centre inside a row lands on that row`() {
        assertEquals(2, dropIndex(centreY = 200f, rows = ROWS, fallback = 0))
    }

    @Test
    fun `a centre in the gap above a row lands on that row`() {
        assertEquals(2, dropIndex(centreY = 170f, rows = ROWS, fallback = 0))
    }

    @Test
    fun `a centre above every row lands on the first`() {
        assertEquals(0, dropIndex(centreY = -400f, rows = ROWS, fallback = 3))
    }

    @Test
    fun `a centre below every row lands on the last`() {
        assertEquals(3, dropIndex(centreY = 9000f, rows = ROWS, fallback = 0))
    }

    @Test
    fun `an unequal row above the target is not counted as more than one row of travel`() {
        // Row 1 is 100 tall against row 0's 60. Dividing 168 pixels by row 0's pitch reaches row 2.
        assertEquals(1, dropIndex(centreY = 100f, rows = ROWS, fallback = 0))
    }

    @Test
    fun `an empty layout keeps the target it was given`() {
        assertEquals(2, dropIndex(centreY = 100f, rows = emptyList(), fallback = 2))
    }

    @Test
    fun `a row away from both edges does not scroll`() {
        assertEquals(0f, edgeScroll(centreY = 300f, viewportHeight = 600, margin = 64f, perFrame = 16f))
    }

    @Test
    fun `a row at the top edge scrolls towards the start`() {
        assertEquals(-16f, edgeScroll(centreY = 0f, viewportHeight = 600, margin = 64f, perFrame = 16f))
    }

    @Test
    fun `a row at the bottom edge scrolls towards the end`() {
        assertEquals(16f, edgeScroll(centreY = 600f, viewportHeight = 600, margin = 64f, perFrame = 16f))
    }

    @Test
    fun `a row part way into the margin scrolls at part of the rate`() {
        assertEquals(-8f, edgeScroll(centreY = 32f, viewportHeight = 600, margin = 64f, perFrame = 16f))
    }

    @Test
    fun `a row past the viewport does not scroll faster than the rate`() {
        assertEquals(16f, edgeScroll(centreY = 5000f, viewportHeight = 600, margin = 64f, perFrame = 16f))
    }

    @Test
    fun `an unmeasured list does not scroll`() {
        assertEquals(0f, edgeScroll(centreY = 10f, viewportHeight = 0, margin = 64f, perFrame = 16f))
    }

    @Test
    fun `a row moved down lands at the position it was given`() {
        assertEquals(listOf("b", "c", "a", "d"), reordered(IDS, from = 0, to = 2))
    }

    @Test
    fun `a row moved up lands at the position it was given`() {
        assertEquals(listOf("d", "a", "b", "c"), reordered(IDS, from = 3, to = 0))
    }

    @Test
    fun `a row moved onto itself leaves the order alone`() {
        assertSame(IDS, reordered(IDS, from = 1, to = 1))
    }

    @Test
    fun `a move from outside the list leaves the order alone`() {
        assertSame(IDS, reordered(IDS, from = NO_ROW, to = 2))
    }

    @Test
    fun `a move past the end of the list leaves the order alone`() {
        assertSame(IDS, reordered(IDS, from = 0, to = 9))
    }

    @Test
    fun `rearranging twice towards the same target gives the same order`() {
        val once = reordered(IDS, from = 0, to = 2)

        assertEquals(once, reordered(IDS, from = 0, to = 2))
    }

    @Test
    fun `a drag far enough down retargets to the row it is over`() {
        val drag = started()

        drag.dragBy(amountY = 180f, rows = ROWS)

        assertEquals(2, drag.targetIndex)
    }

    @Test
    fun `a drag shorter than the row it started on stays where it is`() {
        val drag = started()

        drag.dragBy(amountY = 20f, rows = ROWS)

        assertEquals(0, drag.targetIndex)
    }

    @Test
    fun `the dragged row is drawn by the distance the pointer carried it`() {
        val drag = started()

        drag.dragBy(amountY = 180f, rows = ROWS)

        assertEquals(180f, drag.translationFor(currentTop = 0))
    }

    @Test
    fun `the drawing is measured against the slot the rearrangement gave the row`() {
        val drag = started()

        drag.dragBy(amountY = 180f, rows = ROWS)

        assertEquals(4f, drag.translationFor(currentTop = 176))
    }

    @Test
    fun `nothing is drawn out of place while no row is being dragged`() {
        assertEquals(0f, RowDragState().translationFor(currentTop = 176))
    }

    @Test
    fun `the release reports the row and where it landed`() {
        val drag = started()
        drag.dragBy(amountY = 180f, rows = ROWS)

        assertEquals(RowMove("a", 2), drag.release())
    }

    @Test
    fun `a release back where it started reports no move`() {
        val drag = started()
        drag.dragBy(amountY = 180f, rows = ROWS)
        drag.dragBy(amountY = -180f, rows = ROWS)

        assertNull(drag.release())
    }

    @Test
    fun `the release ends the gesture`() {
        val drag = started()
        drag.dragBy(amountY = 180f, rows = ROWS)

        drag.release()

        assertFalse(drag.isDragging)
    }

    @Test
    fun `the arrangement outlives the release`() {
        val drag = started()
        drag.dragBy(amountY = 180f, rows = ROWS)

        drag.release()

        assertEquals(listOf("b", "c", "a", "d"), reordered(IDS, drag.startIndex, drag.targetIndex))
    }

    @Test
    fun `a release that moved nothing gives the arrangement up at once`() {
        val drag = started()

        drag.release()

        assertSame(IDS, reordered(IDS, drag.startIndex, drag.targetIndex))
    }

    @Test
    fun `settling gives the arrangement up`() {
        val drag = started()
        drag.dragBy(amountY = 180f, rows = ROWS)
        drag.release()

        drag.settle()

        assertSame(IDS, reordered(IDS, drag.startIndex, drag.targetIndex))
    }

    @Test
    fun `a cancelled drag leaves the order alone`() {
        val drag = started()
        drag.dragBy(amountY = 180f, rows = ROWS)

        drag.settle()

        assertSame(IDS, reordered(IDS, drag.startIndex, drag.targetIndex))
    }

    @Test
    fun `the target follows the list scrolling under a pointer that has not moved`() {
        val drag = started()
        drag.dragBy(amountY = 180f, rows = ROWS)

        drag.retarget(ROWS.map { it.copy(top = it.top - 150) })

        assertEquals(3, drag.targetIndex)
    }

    @Test
    fun `a drag that started on a row the list has not laid out does not begin`() {
        val drag = RowDragState()

        drag.start(id = "a", index = 9, rows = ROWS)

        assertFalse(drag.isDragging)
    }

    private fun started(): RowDragState = RowDragState().apply { start(id = "a", index = 0, rows = ROWS) }
}
