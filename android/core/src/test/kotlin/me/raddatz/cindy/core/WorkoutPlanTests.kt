package me.raddatz.cindy.core

import me.raddatz.cindy.core.calibration.CalibrationProfile
import me.raddatz.cindy.core.calibration.ExerciseCalibration
import me.raddatz.cindy.core.signal.RepDirection
import me.raddatz.cindy.core.workout.WorkoutEvent
import me.raddatz.cindy.core.workout.WorkoutScore
import me.raddatz.cindy.core.workout.WorkoutStateMachine
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iOS suite checks the localized `summary`/`label` strings (German and English). :core has no
 * localization, so those assertions check the typed labels the UI renders from instead.
 */
class WorkoutPlanTests {
    @Test
    fun cindyPlanOrderAndReps() {
        val plan = WorkoutPlan.cindy
        assertEquals(30, plan.repsPerRound)
        assertEquals(Exercise.PULL_UP, plan.next(after = Exercise.SQUAT))
        assertEquals(Exercise.SQUAT, plan.previous(before = Exercise.PULL_UP))
        assertEquals(15, plan.repsBefore(Exercise.SQUAT))
        // iOS (German): "5 Klimmzüge · 10 Liegestütze · 15 Kniebeugen"
        assertEquals(
            listOf(
                ExerciseSetLabel.Reps(5, Exercise.PULL_UP, singular = false),
                ExerciseSetLabel.Reps(10, Exercise.PUSH_UP, singular = false),
                ExerciseSetLabel.Reps(15, Exercise.SQUAT, singular = false),
            ),
            plan.summary,
        )
    }

    @Test
    fun planWithoutPullUpsRunsRoundsOfTwoExercises() {
        val plan = WorkoutPlan.withoutPullUps
        assertEquals(25, plan.repsPerRound)
        assertEquals(Exercise.PUSH_UP, plan.first)
        assertEquals(Exercise.PUSH_UP, plan.next(after = Exercise.SQUAT))
        assertEquals(10, plan.repsBefore(Exercise.SQUAT))

        val machine = WorkoutStateMachine(plan)
        machine.beginCountdown()
        machine.start()
        machine.activate()
        assertEquals(Exercise.PUSH_UP, machine.exercise)
        repeat(10) { machine.registerRep() }
        assertEquals(Exercise.SQUAT, machine.exercise)
        machine.activate()
        val events = mutableListOf<WorkoutEvent>()
        repeat(15) { events += machine.registerRep() }
        assertTrue(events.contains(WorkoutEvent.RoundCompleted(1)))
        assertEquals(Exercise.PUSH_UP, machine.exercise)
        assertEquals(WorkoutScore(rounds = 1, reps = 0, repsPerRound = 25), machine.score)
        assertEquals(25, machine.score.totalReps)
        assertTrue(machine.adjust(by = -1).contains(WorkoutEvent.RoundReopened(1)))
        assertEquals(Exercise.SQUAT, machine.exercise)
        assertEquals(14, machine.repCount)
    }

    @Test
    fun customTargetsAndHoldsAreRespected() {
        val plan = WorkoutPlan.withoutPullUps
            .withTarget(3, Exercise.PUSH_UP)
            .withEnabled(Exercise.PLANK, true)
            .withTarget(20, Exercise.PLANK)
        assertEquals(listOf(Exercise.PUSH_UP, Exercise.SQUAT, Exercise.PLANK), plan.exercises)
        assertEquals(1, plan.countTarget(Exercise.PLANK))
        assertEquals(3 + 15 + 1, plan.repsPerRound)
        // iOS (German): "3 Liegestütze · 15 Kniebeugen · 20 s Plank"
        assertEquals(
            listOf(
                ExerciseSetLabel.Reps(3, Exercise.PUSH_UP, singular = false),
                ExerciseSetLabel.Reps(15, Exercise.SQUAT, singular = false),
                ExerciseSetLabel.Hold(20, Exercise.PLANK),
            ),
            plan.summary,
        )

        val machine = WorkoutStateMachine(plan)
        machine.beginCountdown(); machine.start(); machine.activate()
        repeat(3) { machine.registerRep() }
        assertEquals(Exercise.SQUAT, machine.exercise)
        machine.activate()
        repeat(15) { machine.registerRep() }
        assertEquals(Exercise.PLANK, machine.exercise)
        machine.activate()
        val events = machine.registerRep()
        assertTrue(events.contains(WorkoutEvent.RoundCompleted(1)))
        assertEquals(19, machine.score.totalReps)
    }

    @Test
    fun targetsStayWithinTheCindyPrescription() {
        var plan = WorkoutPlan.cindy
            .withTarget(25, Exercise.PULL_UP)
            .withTarget(0, Exercise.SQUAT)
        assertEquals(5, plan.target(Exercise.PULL_UP))
        assertEquals(1, plan.target(Exercise.SQUAT))
        plan = plan.withEnabled(Exercise.PLANK, true).withTarget(2, Exercise.PLANK)
        assertEquals(5, plan.target(Exercise.PLANK))
    }

    @Test
    fun oldPlansAreNormalizedToTheAllowedValues() {
        var plan = WorkoutPlan.cindy
        // saved before the limits existed
        plan = plan.copy(durationMinutes = 12, sets = listOf(plan.sets[0].copy(target = 25)) + plan.sets.drop(1))
        val normalized = plan.normalized()
        assertEquals(10, normalized.durationMinutes)
        assertEquals(5, normalized.target(Exercise.PULL_UP))
        plan = plan.copy(durationMinutes = 45)
        assertEquals(20, plan.normalized().durationMinutes)
    }

    @Test
    fun exercisesMoveOnePlaceAtATime() {
        var plan = WorkoutPlan.cindy
        plan = plan.moving(Exercise.SQUAT, -1)
        assertEquals(listOf(Exercise.PULL_UP, Exercise.SQUAT, Exercise.PUSH_UP), plan.exercises)
        plan = plan.moving(Exercise.PULL_UP, -1)
        assertEquals(listOf(Exercise.PULL_UP, Exercise.SQUAT, Exercise.PUSH_UP), plan.exercises)
    }

    @Test
    fun planRoundTripsThroughJSON() {
        val plan = WorkoutPlan.cindy.copy(durationMinutes = 12).withEnabled(Exercise.PULL_UP, false)
        val data = CindyJson.encodeToString(WorkoutPlan.serializer(), plan)
        val decoded = CindyJson.decodeFromString(WorkoutPlan.serializer(), data)
        assertEquals(plan, decoded)
        assertEquals(720.0, decoded.duration)
    }

    @Test
    fun calibrationCompletenessDependsOnPlan() {
        var profile = CalibrationProfile()
        for (exercise in listOf(Exercise.PUSH_UP, Exercise.SQUAT)) {
            profile = profile.withCalibration(
                ExerciseCalibration(
                    source = SignalSource.FACE, minValue = 0f, maxValue = 1f, baseline = 0f, low = 0.25f, high = 0.75f,
                    direction = RepDirection.PEAK, repDuration = 1.0, calibratedAt = Instant.now(),
                ),
                exercise,
            )
        }
        assertTrue(profile.isComplete(WorkoutPlan.withoutPullUps))
        assertFalse(profile.isComplete(WorkoutPlan.cindy))
        assertEquals(listOf(Exercise.PULL_UP), profile.missingExercises(WorkoutPlan.cindy))
    }

    @Test
    fun setLabelAgreesWithItsCount() {
        // iOS: "1 Klimmzug" / "5 Klimmzüge" / "30 s Plank" (German), "1 Pull-up" / "5 Pull-ups" (English).
        assertEquals(ExerciseSetLabel.Reps(1, Exercise.PULL_UP, singular = true), ExerciseSet(Exercise.PULL_UP, 1).label)
        assertEquals(ExerciseSetLabel.Reps(5, Exercise.PULL_UP, singular = false), ExerciseSet(Exercise.PULL_UP, 5).label)
        assertEquals(ExerciseSetLabel.Hold(30, Exercise.PLANK), ExerciseSet(Exercise.PLANK, 30).label)
    }
}
