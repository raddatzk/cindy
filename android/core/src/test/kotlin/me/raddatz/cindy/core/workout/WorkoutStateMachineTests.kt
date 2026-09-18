package me.raddatz.cindy.core.workout

import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.WorkoutPlan
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkoutStateMachineTests {
    private fun runningMachine(): WorkoutStateMachine {
        val machine = WorkoutStateMachine()
        machine.beginCountdown()
        machine.start()
        machine.activate()
        return machine
    }

    @Test
    fun startsWithPullUpsInTransition() {
        val machine = WorkoutStateMachine()
        machine.beginCountdown()
        assertEquals(WorkoutPhase.COUNTDOWN, machine.phase)
        assertEquals(listOf<WorkoutEvent>(WorkoutEvent.Started), machine.start())
        assertEquals(WorkoutPhase.TRANSITION, machine.phase)
        assertEquals(Exercise.PULL_UP, machine.exercise)
        assertEquals(listOf<WorkoutEvent>(WorkoutEvent.ExerciseStarted(Exercise.PULL_UP)), machine.activate())
        assertEquals(WorkoutPhase.ACTIVE, machine.phase)
    }

    @Test
    fun repsAreIgnoredWhileInTransition() {
        val machine = WorkoutStateMachine()
        machine.beginCountdown()
        machine.start()
        assertTrue(machine.registerRep().isEmpty())
        assertEquals(0, machine.repCount)
    }

    @Test
    fun fullRoundAdvancesExercisesAndRound() {
        val machine = runningMachine()
        repeat(4) { machine.registerRep() }
        assertEquals(4, machine.repCount)
        val fifth = machine.registerRep()
        assertTrue(fifth.contains(WorkoutEvent.ExerciseCompleted(Exercise.PULL_UP, next = Exercise.PUSH_UP)))
        assertEquals(WorkoutPhase.TRANSITION, machine.phase)
        assertEquals(Exercise.PUSH_UP, machine.exercise)
        assertEquals(0, machine.repCount)
        assertEquals(WorkoutScore(rounds = 0, reps = 5), machine.score)

        machine.activate()
        repeat(10) { machine.registerRep() }
        assertEquals(Exercise.SQUAT, machine.exercise)
        assertEquals(15, machine.score.reps)

        machine.activate()
        val events = mutableListOf<WorkoutEvent>()
        repeat(15) { events += machine.registerRep() }
        assertTrue(events.contains(WorkoutEvent.RoundCompleted(1)))
        assertEquals(Exercise.PULL_UP, machine.exercise)
        assertEquals(1, machine.completedRounds)
        assertEquals(2, machine.currentRound)
        assertEquals(WorkoutScore(rounds = 1, reps = 0), machine.score)
    }

    @Test
    fun scoreNotationFollowsCrossFitConvention() {
        assertEquals("14 + 7", WorkoutScore(rounds = 14, reps = 7).notation)
        assertEquals("14", WorkoutScore(rounds = 14, reps = 0).notation)
        assertEquals(77, WorkoutScore(rounds = 2, reps = 17).totalReps)
    }

    @Test
    fun manualPlusOneCompletesExercise() {
        val machine = runningMachine()
        repeat(4) { machine.registerRep() }
        val events = machine.adjust(by = 1)
        assertTrue(events.contains(WorkoutEvent.ExerciseCompleted(Exercise.PULL_UP, next = Exercise.PUSH_UP)))
        assertEquals(WorkoutPhase.TRANSITION, machine.phase)
    }

    @Test
    fun manualMinusOneStepsBackIntoPreviousExercise() {
        val machine = runningMachine()
        repeat(5) { machine.registerRep() } // now push-ups, transition
        val events = machine.adjust(by = -1)
        assertTrue(events.contains(WorkoutEvent.ExerciseReopened(Exercise.PULL_UP)))
        assertEquals(Exercise.PULL_UP, machine.exercise)
        assertEquals(4, machine.repCount)
        assertEquals(WorkoutScore(rounds = 0, reps = 4), machine.score)
    }

    @Test
    fun minusOneAcrossRoundBoundaryReopensRound() {
        val machine = runningMachine()
        repeat(5) { machine.registerRep() }
        machine.activate()
        repeat(10) { machine.registerRep() }
        machine.activate()
        repeat(15) { machine.registerRep() }
        assertEquals(1, machine.completedRounds)
        val events = machine.adjust(by = -1)
        assertTrue(events.contains(WorkoutEvent.RoundReopened(1)))
        assertEquals(0, machine.completedRounds)
        assertEquals(Exercise.SQUAT, machine.exercise)
        assertEquals(14, machine.repCount)
    }

    @Test
    fun minusOneAtVeryStartDoesNothing() {
        val machine = runningMachine()
        assertTrue(machine.adjust(by = -1).isEmpty())
        assertEquals(0, machine.repCount)
        assertEquals(Exercise.PULL_UP, machine.exercise)
    }

    @Test
    fun pauseAndResumeGoThroughTransition() {
        val machine = runningMachine()
        machine.registerRep()
        machine.pause()
        assertEquals(WorkoutPhase.PAUSED, machine.phase)
        assertTrue(machine.registerRep().isEmpty())
        assertTrue(machine.adjust(by = 1).contains(WorkoutEvent.RepCounted(Exercise.PULL_UP, count = 2)))
        machine.resume()
        assertEquals(WorkoutPhase.TRANSITION, machine.phase)
        machine.activate()
        assertEquals(WorkoutPhase.ACTIVE, machine.phase)
        assertEquals(2, machine.repCount)
    }

    @Test
    fun finishStopsCounting() {
        val machine = runningMachine()
        machine.registerRep()
        assertEquals(listOf<WorkoutEvent>(WorkoutEvent.Finished), machine.finish())
        assertEquals(WorkoutPhase.FINISHED, machine.phase)
        assertTrue(machine.registerRep().isEmpty())
        assertTrue(machine.finish().isEmpty())
        assertEquals(WorkoutScore(rounds = 0, reps = 1), machine.score)
    }

    // Changing the plan mid-workout

    /** Pull-ups done, `pushUps` push-ups counted. */
    private fun machineInPushUps(pushUps: Int): WorkoutStateMachine {
        val machine = runningMachine()
        repeat(5) { machine.registerRep() }
        machine.activate()
        repeat(pushUps) { machine.registerRep() }
        return machine
    }

    @Test
    fun changedTargetKeepsTheRepsOfTheCurrentExercise() {
        val machine = machineInPushUps(4)
        val plan = WorkoutPlan.cindy.withTarget(8, Exercise.PUSH_UP)
        assertTrue(machine.replacePlan(plan).isEmpty())
        assertEquals(Exercise.PUSH_UP, machine.exercise)
        assertEquals(4, machine.repCount)
        assertEquals(WorkoutPhase.ACTIVE, machine.phase)
        assertEquals(WorkoutScore(rounds = 0, reps = 9, repsPerRound = 28), machine.score)
    }

    @Test
    fun targetBelowTheRepsDoneFinishesTheExercise() {
        val machine = machineInPushUps(7)
        machine.pause()
        val plan = WorkoutPlan.cindy.withTarget(5, Exercise.PUSH_UP)
        assertEquals(
            listOf<WorkoutEvent>(WorkoutEvent.ExerciseCompleted(Exercise.PUSH_UP, next = Exercise.SQUAT)),
            machine.replacePlan(plan),
        )
        assertEquals(Exercise.SQUAT, machine.exercise)
        assertEquals(0, machine.repCount)
        assertEquals(WorkoutPhase.PAUSED, machine.phase)
    }

    @Test
    fun removingTheCurrentExerciseMovesOnToTheNextOne() {
        val machine = machineInPushUps(3)
        val plan = WorkoutPlan.cindy.withEnabled(Exercise.PUSH_UP, false)
        assertTrue(machine.replacePlan(plan).isEmpty())
        assertEquals(Exercise.SQUAT, machine.exercise)
        assertEquals(0, machine.repCount)
        assertEquals(WorkoutPhase.TRANSITION, machine.phase)
        assertEquals(WorkoutScore(rounds = 0, reps = 5, repsPerRound = 20), machine.score)
    }

    @Test
    fun removingTheRestOfTheRoundCompletesIt() {
        val machine = machineInPushUps(10)
        machine.activate()
        repeat(3) { machine.registerRep() }
        val plan = WorkoutPlan.cindy.withEnabled(Exercise.SQUAT, false)
        assertEquals(listOf<WorkoutEvent>(WorkoutEvent.RoundCompleted(1)), machine.replacePlan(plan))
        assertEquals(Exercise.PULL_UP, machine.exercise)
        assertEquals(2, machine.currentRound)
        assertEquals(WorkoutScore(rounds = 1, reps = 0, repsPerRound = 15), machine.score)
    }

    @Test
    fun addedExerciseJoinsTheCurrentRound() {
        val machine = machineInPushUps(10)
        val plan = WorkoutPlan.cindy.withEnabled(Exercise.PLANK, true)
        machine.replacePlan(plan)
        machine.activate()
        // The plank waits for the end of the AMRAP: the squats still complete the round.
        val events = (0 until 15).flatMap { machine.registerRep() }
        assertTrue(events.contains(WorkoutEvent.RoundCompleted(1)))
        assertEquals(Exercise.PULL_UP, machine.exercise)
    }

    @Test
    fun thePlankOnlyFollowsARunningWorkoutWithAPlank() {
        val machine = machineInPushUps(0)
        machine.beginPlank()
        assertNotEquals(WorkoutPhase.PLANK, machine.phase) // Cindy has no plank
        machine.replacePlan(WorkoutPlan.cindy.withEnabled(Exercise.PLANK, true))
        machine.beginPlank()
        assertEquals(WorkoutPhase.PLANK, machine.phase)
        assertFalse(machine.isRunning)
    }

    @Test
    fun finishedWorkoutKeepsItsPlan() {
        val machine = runningMachine()
        machine.finish()
        assertTrue(machine.replacePlan(WorkoutPlan.withoutPullUps).isEmpty())
        assertEquals(WorkoutPlan.cindy, machine.plan)
    }
}
