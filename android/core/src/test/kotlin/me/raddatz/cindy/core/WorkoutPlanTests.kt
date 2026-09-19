package me.raddatz.cindy.core

import me.raddatz.cindy.core.calibration.CalibrationProfile
import me.raddatz.cindy.core.calibration.ExerciseCalibration
import me.raddatz.cindy.core.signal.RepDirection
import me.raddatz.cindy.core.workout.WorkoutEvent
import me.raddatz.cindy.core.workout.WorkoutPhase
import me.raddatz.cindy.core.workout.WorkoutScore
import me.raddatz.cindy.core.workout.WorkoutStateMachine
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun thePlankFollowsTheAmrapOutsideTheRound() {
        val plan = WorkoutPlan.withoutPullUps
            .withTarget(3, Exercise.PUSH_UP)
            .withEnabled(Exercise.PLANK, true)
            .withTarget(20, Exercise.PLANK)
        assertEquals(listOf(Exercise.PUSH_UP, Exercise.SQUAT), plan.exercises)
        assertTrue(Exercise.PLANK in plan)
        assertEquals(20, plan.target(Exercise.PLANK))
        assertEquals(3 + 15, plan.repsPerRound)
        // iOS (German): "3 Liegestütze · 15 Kniebeugen" and "…, danach 20 s Plank"
        val round = listOf(
            ExerciseSetLabel.Reps(3, Exercise.PUSH_UP, singular = false),
            ExerciseSetLabel.Reps(15, Exercise.SQUAT, singular = false),
        )
        assertEquals(round, plan.summary)
        assertEquals(PlanSummary(round, ExerciseSetLabel.Hold(20, Exercise.PLANK)), plan.summaryWithPlank)

        val machine = WorkoutStateMachine(plan)
        machine.beginCountdown(); machine.start(); machine.activate()
        repeat(3) { machine.registerRep() }
        machine.activate()
        val events = (0 until 15).flatMap { machine.registerRep() }
        assertTrue(events.contains(WorkoutEvent.RoundCompleted(1)))
        assertEquals(Exercise.PUSH_UP, machine.exercise)
        machine.activate()
        machine.registerRep()
        machine.beginPlank()
        assertEquals(WorkoutPhase.PLANK, machine.phase)
        assertTrue(machine.adjust(by = 1).isEmpty()) // the score is final
        assertEquals(19, machine.score.totalReps)
        assertEquals(listOf<WorkoutEvent>(WorkoutEvent.Finished), machine.finish())
    }

    @Test
    fun thePlankCanHaveSeveralSets() {
        var plan = WorkoutPlan.withoutPullUps.withEnabled(Exercise.PLANK, true)
        assertEquals(1, plan.plankSets)
        plan = plan.withPlankSets(3)
        assertEquals(3, plan.summaryWithPlank.plankSets)
        assertEquals(1, plan.withPlankSets(0).plankSets)
        assertEquals(WorkoutPlan.plankSetRange.last, plan.withPlankSets(50).plankSets)
        assertEquals(25, plan.repsPerRound) // sets stay outside the score
        val decoded = CindyJson.decodeFromString(WorkoutPlan.serializer(), CindyJson.encodeToString(WorkoutPlan.serializer(), plan))
        assertEquals(3, decoded.plankSets)
        val older = """{"sets":[{"exercise":"squat","target":15}],"durationMinutes":20,"plankSeconds":30}"""
        assertEquals(1, CindyJson.decodeFromString(WorkoutPlan.serializer(), older).plankSets)
    }

    @Test
    fun plansWithThePlankInTheRoundMoveItBehindTheAmrap() {
        val saved = """{"sets":[{"exercise":"pushUp","target":10},{"exercise":"plank","target":45},""" +
            """{"exercise":"squat","target":15}],"durationMinutes":20}"""
        val plan = CindyJson.decodeFromString(WorkoutPlan.serializer(), saved)
        assertEquals(listOf(Exercise.PUSH_UP, Exercise.SQUAT), plan.exercises)
        assertEquals(45, plan.plankSeconds)
        val roundTripped = CindyJson.decodeFromString(WorkoutPlan.serializer(), CindyJson.encodeToString(WorkoutPlan.serializer(), plan))
        assertEquals(plan, roundTripped)
        val withoutPlank = CindyJson.decodeFromString(
            WorkoutPlan.serializer(),
            CindyJson.encodeToString(WorkoutPlan.serializer(), WorkoutPlan.cindy),
        )
        assertNull(withoutPlank.plankSeconds)
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
