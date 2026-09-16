package me.raddatz.cindy.core.health

import me.raddatz.cindy.core.addingSeconds
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.core.roundedHalfAwayFromZero
import me.raddatz.cindy.core.secondsSince
import java.time.Instant

/**
 * What the health store contributes to the readiness estimate. All optional: without a watch
 * most of it stays empty, and the estimate falls back to the workout history alone.
 */
data class HealthMetrics(
    /**
     * Heart rate variability against its baseline. iOS reads SDNN, Health Connect only offers
     * RMSSD; the estimator only looks at the z-score, so either works — but never mix both in
     * one baseline.
     */
    val heartRateVariability: MetricBaseline? = null,
    val restingHeartRate: MetricBaseline? = null,
    /** Hours actually asleep last night (overlapping sources merged). */
    val sleepHours: Double? = null,
    val bodyMassKilograms: Double? = null,
) {
    val isEmpty: Boolean
        get() = heartRateVariability == null && restingHeartRate == null && sleepHours == null

    companion object {
        val none: HealthMetrics = HealthMetrics()
    }
}

/**
 * Turns the workout history and the health metrics into a [Readiness].
 * Pure: no health store, no clock of its own, so it can be unit-tested.
 */
data class ReadinessEstimator(val now: Instant = Instant.now()) {

    fun estimate(history: List<WorkoutRecord>, metrics: HealthMetrics = HealthMetrics.none): Readiness? {
        val components = mutableListOf<Readiness.Component>()

        val last = history.maxByOrNull { it.date }
        if (last != null) {
            val hours = now.secondsSince(last.date) / 3600
            val needed = ReadinessScoring.recoveryHours(last)
            components += component(
                Readiness.Component.Kind.RECOVERY,
                ReadinessScoring.recovery(fraction = maxOf(hours, 0.0) / needed),
                ReadinessDetail.SinceLastWorkout(
                    hoursSince = maxOf(hours, 0.0).roundedHalfAwayFromZero().toInt(),
                    hoursSuggested = needed.roundedHalfAwayFromZero().toInt(),
                ),
            )
        }
        val ratio = loadRatio(history, now)
        if (ratio != null) {
            components += component(
                Readiness.Component.Kind.TRAINING_LOAD,
                ReadinessScoring.trainingLoad(ratio),
                ReadinessDetail.TrainingLoad(ratio),
            )
        }
        val sleepHours = metrics.sleepHours
        if (sleepHours != null) {
            components += component(
                Readiness.Component.Kind.SLEEP,
                ReadinessScoring.sleep(sleepHours),
                ReadinessDetail.Sleep(sleepHours),
            )
        }
        val hrv = metrics.heartRateVariability
        val hrvZ = hrv?.zScore
        if (hrv != null && hrvZ != null) {
            components += component(
                Readiness.Component.Kind.HEART_RATE_VARIABILITY,
                ReadinessScoring.heartRateVariability(hrvZ),
                ReadinessDetail.HeartRateVariability(
                    hrv.value.roundedHalfAwayFromZero().toInt(),
                    hrv.mean.roundedHalfAwayFromZero().toInt(),
                ),
            )
        }
        val pulse = metrics.restingHeartRate
        val pulseZ = pulse?.zScore
        if (pulse != null && pulseZ != null) {
            components += component(
                Readiness.Component.Kind.RESTING_HEART_RATE,
                ReadinessScoring.restingHeartRate(pulseZ),
                ReadinessDetail.RestingHeartRate(
                    pulse.value.roundedHalfAwayFromZero().toInt(),
                    pulse.mean.roundedHalfAwayFromZero().toInt(),
                ),
            )
        }

        return Readiness.from(components, usesHealthData = !metrics.isEmpty)
    }

    private fun component(kind: Readiness.Component.Kind, score: Double, detail: ReadinessDetail) =
        Readiness.Component(kind, score, ReadinessScoring.weights[kind] ?: 0.0, detail)

    companion object {
        /**
         * Volume of the last 7 days divided by the weekly average of the last 28, counted in reps.
         * `null` below three sessions, where the average says nothing.
         */
        fun loadRatio(history: List<WorkoutRecord>, now: Instant, minimumSessions: Int = 3): Double? {
            val week = now.addingSeconds(-7.0 * 24 * 3600)
            val month = now.addingSeconds(-28.0 * 24 * 3600)
            val recent = history.filter { it.date > month && it.date <= now }
            if (recent.size < minimumSessions) return null
            val chronicWeekly = recent.fold(0) { sum, r -> sum + r.score.totalReps }.toDouble() / 4
            if (!(chronicWeekly > 0)) return null
            val acute = recent.filter { it.date > week }.fold(0) { sum, r -> sum + r.score.totalReps }.toDouble()
            return acute / chronicWeekly
        }
    }
}
