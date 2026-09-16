package me.raddatz.cindy.core.workout

import me.raddatz.cindy.core.CindyJson
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.persistence.WorkoutRecord
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressionAdvisorTests {
    private fun record(
        plan: WorkoutPlan,
        rounds: Int,
        secondsPerRound: List<Double>,
        completed: Boolean = true,
        date: Instant = Instant.now(),
    ): WorkoutRecord {
        val stamps = mutableListOf<Double>()
        var t = 0.0
        for (d in secondsPerRound) {
            t += d
            stamps += t
        }
        return WorkoutRecord(
            date = date, rounds = rounds, extraReps = 0, durationSeconds = plan.duration, completed = completed,
            repsPerRound = plan.repsPerRound, roundTimestamps = stamps, plan = plan,
        )
    }

    private fun repeated(seconds: Double, count: Int) = List(count) { seconds }

    private val homePlan: WorkoutPlan
        get() = WorkoutPlan.cindy
            .withTarget(2, Exercise.PULL_UP)
            .withTarget(4, Exercise.PUSH_UP)
            .withTarget(9, Exercise.SQUAT)
            .copy(durationMinutes = 15)

    private val fadingRounds = listOf(40.0, 40.0, 40.0, 40.0, 40.0, 60.0, 60.0, 80.0, 100.0, 100.0, 100.0, 120.0)

    @Test
    fun reserveRatioComparesLastAndFirstQuarter() {
        val steady = record(homePlan, 14, repeated(64.0, 14))
        assertEquals(1.0, ProgressionAdvisor.reserveRatio(steady))
        val fading = record(homePlan, 12, fadingRounds)
        val ratio = ProgressionAdvisor.reserveRatio(fading) ?: 1.0
        assertTrue(ratio < 0.85)
        assertEquals(14, ProgressionAdvisor.roundDurations(steady).size)
    }

    @Test
    fun durationComesFirst() {
        val r = record(homePlan, 14, repeated(64.0, 14))
        val rec = ProgressionAdvisor().recommend(after = r, previous = null)
        assertEquals(ProgressionAdvisor.Recommendation.Kind.Duration, rec.kind)
        assertEquals(20, rec.plan.durationMinutes)
        assertTrue(rec.changesPlan)
    }

    @Test
    fun thenWeakestExerciseGetsOneRep() {
        val plan = homePlan.copy(durationMinutes = 20)
        val r = record(plan, 18, repeated(66.0, 18))
        val rec = ProgressionAdvisor().recommend(after = r, previous = null)
        assertEquals(ProgressionAdvisor.Recommendation.Kind.Reps(Exercise.PULL_UP), rec.kind) // 2/5 is the smallest ratio
        assertEquals(3, rec.plan.target(Exercise.PULL_UP))
    }

    @Test
    fun fullCindyRecommendsDensity() {
        val r = record(WorkoutPlan.cindy, 12, repeated(100.0, 12))
        val rec = ProgressionAdvisor().recommend(after = r, previous = null)
        assertEquals(ProgressionAdvisor.Recommendation.Kind.Density(targetRounds = 13), rec.kind)
        assertFalse(rec.changesPlan)
    }

    @Test
    fun fadingPaceHolds() {
        val r = record(homePlan, 12, fadingRounds)
        assertEquals(ProgressionAdvisor.Recommendation.Kind.Hold, ProgressionAdvisor().recommend(after = r, previous = null).kind)
    }

    @Test
    fun abortedWorkoutHolds() {
        val r = record(homePlan, 5, repeated(60.0, 5), completed = false)
        assertEquals(ProgressionAdvisor.Recommendation.Kind.Hold, ProgressionAdvisor().recommend(after = r, previous = null).kind)
    }

    @Test
    fun collapseAfterHarderPlanStepsBack() {
        val previous = record(homePlan, 14, repeated(64.0, 14), date = Instant.now().minusSeconds(86_400))
        val harder = homePlan.withTarget(4, Exercise.PULL_UP)
        val current = record(harder, 9, repeated(100.0, 9))
        val rec = ProgressionAdvisor().recommend(after = current, previous = previous)
        assertEquals(ProgressionAdvisor.Recommendation.Kind.StepBack, rec.kind)
        assertEquals(homePlan, rec.plan)
    }

    @Test
    fun recordWithoutTimestampsStillDecodes() {
        val json = """{"id":"6C1B0B8E-5A0B-4B4F-9C53-1F2D7B2B1A11","date":"2026-09-01T10:00:00Z","rounds":3,"extraReps":2,"durationSeconds":1200,"completed":true,"repsPerRound":30}"""
        val record = CindyJson.decodeFromString(WorkoutRecord.serializer(), json)
        assertNull(record.roundTimestamps)
        assertNull(ProgressionAdvisor.reserveRatio(record))
    }
}
