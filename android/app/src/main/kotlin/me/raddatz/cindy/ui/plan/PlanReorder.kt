package me.raddatz.cindy.ui.plan

/**
 * Drag-to-reorder arithmetic for the plan editor, kept free of Compose so it can be unit-tested.
 *
 * A dragged row swaps with its neighbour once it has travelled past half of that neighbour's
 * height; the offset is then reduced by the neighbour's height so the row stays under the finger.
 */
object PlanReorder {
    /** A swap to perform: move the dragged row by [by] places and keep [remainingOffset] as its drag offset. */
    data class Step(val by: Int, val remainingOffset: Float)

    /**
     * @param offset how far the dragged row has been moved from its slot, in pixels (down is positive)
     * @param index the dragged row's current position
     * @param heights the measured heights of all rows, in order
     * @return the swap to make now, or null while the row has not passed a neighbour's midpoint
     */
    fun step(offset: Float, index: Int, heights: List<Int>): Step? {
        if (index !in heights.indices) return null
        if (offset > 0 && index + 1 < heights.size) {
            val neighbour = heights[index + 1]
            if (offset > neighbour / 2f) return Step(1, offset - neighbour)
        }
        if (offset < 0 && index - 1 >= 0) {
            val neighbour = heights[index - 1]
            if (-offset > neighbour / 2f) return Step(-1, offset + neighbour)
        }
        return null
    }
}
