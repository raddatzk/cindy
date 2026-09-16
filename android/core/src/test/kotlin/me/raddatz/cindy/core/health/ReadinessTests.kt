package me.raddatz.cindy.core.health

import me.raddatz.cindy.core.DateInterval
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.addingSeconds
import me.raddatz.cindy.core.persistence.WorkoutRecord
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadinessTests {
    private val now = Instant.ofEpochSecond(1_700_000_000)

    private fun record(
        hoursAgo: Double,
        duration: Double = 1200.0,
        reps: Int = 300,
        plan: WorkoutPlan = WorkoutPlan.cindy,
    ): WorkoutRecord = WorkoutRecord(
        date = now.addingSeconds(-hoursAgo * 3600), rounds = reps / 30, extraReps = 0,
        durationSeconds = duration, completed = duration >= plan.duration,
        repsPerRound = 30, roundTimestamps = null, plan = plan,
    )

    // Scoring curves

    @Test
    fun interpolationIsLinearBetweenPointsAndFlatOutside() {
        val points = listOf(0.0 to 0.0, 1.0 to 10.0, 2.0 to 20.0)
        assertEquals(0.0, ReadinessScoring.interpolate(-5.0, points))
        assertEquals(5.0, ReadinessScoring.interpolate(0.5, points))
        assertEquals(15.0, ReadinessScoring.interpolate(1.5, points))
        assertEquals(20.0, ReadinessScoring.interpolate(99.0, points))
    }

    @Test
    fun recoveryRisesWithRestTaken() {
        assertEquals(10.0, ReadinessScoring.recovery(fraction = 0.0))
        assertEquals(85.0, ReadinessScoring.recovery(fraction = 1.0))
        assertTrue(ReadinessScoring.recovery(fraction = 0.5) < ReadinessScoring.recovery(fraction = 1.0))
        assertEquals(100.0, ReadinessScoring.recovery(fraction = 3.0))
    }

    @Test
    fun aFullSessionNeedsTwoDaysAndAShortOneLess() {
        assertEquals(48.0, ReadinessScoring.recoveryHours(record(hoursAgo = 1.0)))
        assertEquals(36.0, ReadinessScoring.recoveryHours(record(hoursAgo = 1.0, duration = 600.0)))
    }

    @Test
    fun heartRateBelowBaselineIsGoodAndVariabilityBelowBaselineIsBad() {
        assertTrue(ReadinessScoring.heartRateVariability(zScore = -2.0) < ReadinessScoring.heartRateVariability(zScore = 0.0))
        assertTrue(ReadinessScoring.heartRateVariability(zScore = 2.0) > ReadinessScoring.heartRateVariability(zScore = 0.0))
        assertTrue(ReadinessScoring.restingHeartRate(zScore = 2.0) < ReadinessScoring.restingHeartRate(zScore = 0.0))
        assertTrue(ReadinessScoring.restingHeartRate(zScore = -2.0) > ReadinessScoring.restingHeartRate(zScore = 0.0))
    }

    @Test
    fun aVolumeSpikeScoresWorseThanASteadyWeek() {
        assertTrue(ReadinessScoring.trainingLoad(ratio = 1.0) > ReadinessScoring.trainingLoad(ratio = 1.8))
        assertTrue(ReadinessScoring.trainingLoad(ratio = 1.0) > ReadinessScoring.trainingLoad(ratio = 0.2))
    }

    // Baseline

    @Test
    fun baselineNeedsEnoughDaysAndSomeVariation() {
        assertNull(MetricBaseline.fromHistory(current = 50.0, earlierDailyValues = List(5) { 45.0 }))
        val flat = MetricBaseline.fromHistory(current = 50.0, earlierDailyValues = List(20) { 45.0 })
        assertNotNull(flat)
        assertNull(flat.zScore) // no spread: a z-score would be meaningless
        val varied = MetricBaseline.fromHistory(current = 60.0, earlierDailyValues = (0 until 20).map { 40 + (it % 5) * 5.0 })
        assertTrue((varied?.zScore ?: 0.0) > 0)
        assertTrue((varied?.relativeDeviation ?: 0.0) > 0)
    }

    @Test
    fun zScoreIsClampedToThreeSigma() {
        val extreme = MetricBaseline(value = 1000.0, mean = 50.0, standardDeviation = 5.0)
        assertEquals(3.0, extreme.zScore)
    }

    // Series helpers

    @Test
    fun staleSeriesYieldsNoBaseline() {
        val fresh = (0 until 20).map {
            HealthSeries.DailyValue(date = now.addingSeconds(-it * 86400.0), value = 40 + (it % 4).toDouble())
        }
        assertNotNull(HealthSeries.baseline(fresh, now = now))
        val stale = fresh.map { HealthSeries.DailyValue(date = it.date.addingSeconds(-5 * 86400.0), value = it.value) }
        assertNull(HealthSeries.baseline(stale, now = now))
    }

    @Test
    fun overlappingSleepFromTwoSourcesIsCountedOnce() {
        val start = now.addingSeconds(-8 * 3600.0)
        val watch = DateInterval(start, start.addingSeconds(6 * 3600.0))
        val phone = DateInterval(start.addingSeconds(3 * 3600.0), start.addingSeconds(7 * 3600.0))
        assertEquals(7.0, HealthSeries.asleepHours(listOf(watch, phone)))
        val gap = DateInterval(start.addingSeconds(8 * 3600.0), start.addingSeconds(9 * 3600.0))
        assertEquals(8.0, HealthSeries.asleepHours(listOf(watch, phone, gap)))
    }

    // Estimator

    @Test
    fun withoutAnyDataThereIsNoEstimate() {
        assertNull(ReadinessEstimator(now = now).estimate(history = emptyList()))
    }

    @Test
    fun historyAloneCarriesTheEstimate() {
        val estimator = ReadinessEstimator(now = now)
        val rested = estimator.estimate(history = listOf(record(hoursAgo = 48.0)))
        assertEquals(false, rested?.usesHealthData)
        assertEquals(85, rested?.score)

        val justFinished = estimator.estimate(history = listOf(record(hoursAgo = 0.0)))
        assertEquals(Readiness.Band.REST, justFinished?.band)
    }

    @Test
    fun aVolumeSpikePullsTheScoreDown() {
        val estimator = ReadinessEstimator(now = now)
        val spike = listOf(record(hoursAgo = 48.0), record(hoursAgo = 60.0), record(hoursAgo = 72.0))
        val spread = listOf(record(hoursAgo = 48.0), record(hoursAgo = 24.0 * 12), record(hoursAgo = 24.0 * 20))
        val spiked = estimator.estimate(history = spike)
        val steady = estimator.estimate(history = spread)
        assertEquals(4.0, ReadinessEstimator.loadRatio(history = spike, now = now))
        assertTrue((spiked?.score ?: 0) < (steady?.score ?: 0))
    }

    @Test
    fun loadRatioNeedsThreeSessions() {
        assertNull(ReadinessEstimator.loadRatio(history = listOf(record(hoursAgo = 24.0), record(hoursAgo = 48.0)), now = now))
    }

    @Test
    fun healthDataMovesTheScoreAndIsMarkedAsUsed() {
        val estimator = ReadinessEstimator(now = now)
        val history = listOf(record(hoursAgo = 48.0))
        val bad = HealthMetrics(
            heartRateVariability = MetricBaseline(value = 30.0, mean = 50.0, standardDeviation = 8.0),
            restingHeartRate = MetricBaseline(value = 62.0, mean = 52.0, standardDeviation = 4.0),
            sleepHours = 4.5, bodyMassKilograms = 80.0,
        )
        val good = HealthMetrics(
            heartRateVariability = MetricBaseline(value = 70.0, mean = 50.0, standardDeviation = 8.0),
            restingHeartRate = MetricBaseline(value = 48.0, mean = 52.0, standardDeviation = 4.0),
            sleepHours = 8.0, bodyMassKilograms = 80.0,
        )
        val poor = estimator.estimate(history, metrics = bad)
        val strong = estimator.estimate(history, metrics = good)
        assertEquals(true, poor?.usesHealthData)
        assertTrue((poor?.score ?: 100) < 60)
        assertTrue((strong?.score ?: 0) > (poor?.score ?: 100))
        assertEquals(4, strong?.components?.size) // recovery, sleep, HRV, resting pulse
    }

    @Test
    fun theStrongestDeviationIsNamedFirst() {
        val metrics = HealthMetrics(heartRateVariability = null, restingHeartRate = null, sleepHours = 3.0, bodyMassKilograms = null)
        val readiness = ReadinessEstimator(now = now).estimate(history = listOf(record(hoursAgo = 48.0)), metrics = metrics)
        assertEquals(Readiness.Component.Kind.SLEEP, readiness?.reasons()?.first()?.kind)
        assertEquals(2, readiness?.reasons()?.size)
    }

    @Test
    fun bandsFollowTheScore() {
        fun band(score: Double): Readiness.Band? =
            Readiness.from(listOf(Readiness.Component(Readiness.Component.Kind.SLEEP, score, 1.0)), usesHealthData = false)?.band
        assertEquals(Readiness.Band.REST, band(20.0))
        assertEquals(Readiness.Band.EASY, band(50.0))
        assertEquals(Readiness.Band.READY, band(70.0))
        assertEquals(Readiness.Band.PRIMED, band(90.0))
    }
}
