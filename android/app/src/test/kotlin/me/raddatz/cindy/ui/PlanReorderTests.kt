package me.raddatz.cindy.ui

import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.ui.plan.PlanReorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanReorderTests {
    private val heights = listOf(100, 100, 140)

    @Test
    fun staysPutUntilHalfOfTheNeighbourIsPassed() {
        assertNull(PlanReorder.step(offset = 50f, index = 0, heights = heights))
        assertNull(PlanReorder.step(offset = -50f, index = 1, heights = heights))
        assertNull(PlanReorder.step(offset = 0f, index = 1, heights = heights))
    }

    @Test
    fun swapsDownAndKeepsTheRowUnderTheFinger() {
        assertEquals(PlanReorder.Step(1, -49f), PlanReorder.step(offset = 51f, index = 0, heights = heights))
        // The taller last row needs more travel.
        assertNull(PlanReorder.step(offset = 70f, index = 1, heights = heights))
        assertEquals(PlanReorder.Step(1, -69f), PlanReorder.step(offset = 71f, index = 1, heights = heights))
    }

    @Test
    fun swapsUp() {
        assertEquals(PlanReorder.Step(-1, 40f), PlanReorder.step(offset = -60f, index = 2, heights = heights))
    }

    @Test
    fun neverMovesPastTheEnds() {
        assertNull(PlanReorder.step(offset = -500f, index = 0, heights = heights))
        assertNull(PlanReorder.step(offset = 500f, index = 2, heights = heights))
        assertNull(PlanReorder.step(offset = 500f, index = 7, heights = heights))
    }

    @Test
    fun aDragAcrossTwoRowsMovesTheExerciseToTheEnd() {
        var plan = WorkoutPlan.cindy
        var offset = 0f
        val rowHeights = plan.sets.map { 100 }
        // 10 px per frame, 220 px in total.
        repeat(22) {
            offset += 10f
            val index = plan.sets.indexOfFirst { it.exercise == Exercise.PULL_UP }
            PlanReorder.step(offset, index, rowHeights)?.let { step ->
                offset = step.remainingOffset
                plan = plan.moving(Exercise.PULL_UP, step.by)
            }
        }
        assertEquals(listOf(Exercise.PUSH_UP, Exercise.SQUAT, Exercise.PULL_UP), plan.exercises)
        assertEquals(20f, offset)
    }
}
