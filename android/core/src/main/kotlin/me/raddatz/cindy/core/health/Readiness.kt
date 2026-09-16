package me.raddatz.cindy.core.health

import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.core.roundedHalfAwayFromZero
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How ready the body is for the next hard session, on a 0–100 scale.
 *
 * Neither Apple Health nor Health Connect publishes a readiness figure, so Cindy computes one
 * from the values it can actually read and from its own workout history. Every input is optional
 * and the score is the weighted mean of the components that exist, so the estimate degrades
 * instead of disappearing when there is no watch (or no health access at all).
 *
 * Create with [Readiness.from].
 */
@ConsistentCopyVisibility
data class Readiness private constructor(
    val score: Int,
    val components: List<Component>,
    /** False when the score rests on the workout history alone. */
    val usesHealthData: Boolean,
) {
    enum class Band { REST, EASY, READY, PRIMED }

    /** One input, already translated to the common 0–100 scale. */
    data class Component(
        val kind: Kind,
        val score: Double,
        val weight: Double,
        /** What was measured, for the one-liner the UI shows (iOS: a localized `detail` string). */
        val detail: ReadinessDetail? = null,
    ) {
        enum class Kind { RECOVERY, SLEEP, HEART_RATE_VARIABILITY, RESTING_HEART_RATE, TRAINING_LOAD }

        val id: Kind get() = kind

        /** How far this component pulls the total away from neutral. */
        val pull: Double get() = (score - ReadinessScoring.NEUTRAL) * weight
    }

    val band: Band
        get() = when {
            score < 40 -> Band.REST
            score < 60 -> Band.EASY
            score < 80 -> Band.READY
            else -> Band.PRIMED
        }

    /**
     * Weighted score of everything except the plain hours-since-the-last-session rule: what the
     * body says beyond what the calendar already knows. `null` when recovery is the only thing
     * known.
     *
     * The reminder uses this rather than the total, which already contains the waiting time it is
     * about to add.
     */
    val signalScore: Int?
        get() {
            val signals = components.filter { it.kind != Component.Kind.RECOVERY }
            val totalWeight = signals.fold(0.0) { sum, c -> sum + c.weight }
            if (signals.isEmpty() || !(totalWeight > 0)) return null
            return (signals.fold(0.0) { sum, c -> sum + c.score * c.weight } / totalWeight)
                .roundedHalfAwayFromZero().toInt()
        }

    /** The components that move the score most, strongest first. */
    fun reasons(limit: Int = 3): List<Component> =
        components.sortedByDescending { abs(it.pull) }.take(limit)

    companion object {
        /** `null` when nothing at all is known — no workout in the history and no health data. */
        fun from(components: List<Component>, usesHealthData: Boolean): Readiness? {
            val totalWeight = components.fold(0.0) { sum, c -> sum + c.weight }
            if (components.isEmpty() || !(totalWeight > 0)) return null
            val weighted = components.fold(0.0) { sum, c -> sum + c.score * c.weight }
            return Readiness(
                score = (weighted / totalWeight).roundedHalfAwayFromZero().toInt(),
                components = components,
                usesHealthData = usesHealthData,
            )
        }
    }
}

/** Typed replacement for the localized iOS `Readiness.Component.detail`. */
sealed interface ReadinessDetail {
    /** "\(hours) h since the last workout, \(needed) h suggested" (both rounded to whole hours). */
    data class SinceLastWorkout(val hoursSince: Int, val hoursSuggested: Int) : ReadinessDetail

    /** "This week's volume is \(ratio.formattedDecimal(1))× your average" */
    data class TrainingLoad(val ratio: Double) : ReadinessDetail

    /** "\(hours.formattedDecimal(1)) h of sleep last night" */
    data class Sleep(val hours: Double) : ReadinessDetail

    /**
     * "HRV \(value) ms, your average \(mean) ms" (rounded). Whatever HRV metric the platform
     * delivers — SDNN on iOS, RMSSD on Health Connect — both in milliseconds.
     */
    data class HeartRateVariability(val milliseconds: Int, val averageMilliseconds: Int) : ReadinessDetail

    /** "Resting pulse \(value) bpm, your average \(mean) bpm" (rounded). */
    data class RestingHeartRate(val beatsPerMinute: Int, val averageBeatsPerMinute: Int) : ReadinessDetail
}

/**
 * The curves that turn each raw measurement into a 0–100 component score.
 *
 * Deliberately piecewise linear and readable: these are judgement calls from the training
 * literature, not a validated model, and they should be easy to argue with and to adjust.
 */
object ReadinessScoring {
    /** Score of an average day. Components sit around this when nothing stands out. */
    const val NEUTRAL: Double = 78.0

    val weights: Map<Readiness.Component.Kind, Double> = mapOf(
        Readiness.Component.Kind.RECOVERY to 0.35,
        Readiness.Component.Kind.SLEEP to 0.20,
        Readiness.Component.Kind.HEART_RATE_VARIABILITY to 0.25,
        Readiness.Component.Kind.RESTING_HEART_RATE to 0.10,
        Readiness.Component.Kind.TRAINING_LOAD to 0.10,
    )

    /** Rest taken since the last session, as a fraction of the rest it needs. */
    fun recovery(fraction: Double): Double =
        interpolate(fraction, listOf(0.0 to 10.0, 0.5 to 45.0, 1.0 to 85.0, 2.0 to 100.0))

    /**
     * Hours a session needs before the next hard one: two days for a full AMRAP, proportionally
     * less for one that was cut short.
     */
    fun recoveryHours(record: WorkoutRecord): Double {
        val planned = maxOf(record.plannedDuration, 1.0)
        val effort = minOf(1.0, record.durationSeconds / planned)
        return 24 + 24 * effort
    }

    fun sleep(hours: Double): Double =
        interpolate(hours, listOf(0.0 to 0.0, 4.0 to 25.0, 6.0 to 60.0, 7.5 to 88.0, 8.5 to 100.0, 11.0 to 85.0))

    /**
     * Heart rate variability against the personal baseline: above is good. Only the z-score is
     * used, so it works for SDNN (iOS) and RMSSD (Health Connect) alike, as long as the baseline
     * is built from the same metric.
     */
    fun heartRateVariability(zScore: Double): Double =
        interpolate(zScore, listOf(-2.5 to 5.0, -1.0 to 45.0, 0.0 to 80.0, 1.0 to 93.0, 2.5 to 100.0))

    /** Resting heart rate against the personal baseline: above is bad. */
    fun restingHeartRate(zScore: Double): Double =
        interpolate(-zScore, listOf(-2.5 to 5.0, -1.0 to 45.0, 0.0 to 80.0, 1.0 to 93.0, 2.5 to 100.0))

    /**
     * Volume of the last 7 days divided by the weekly average of the last 28. Around 1 is
     * sustainable; a sharp spike is the classic overreaching sign.
     */
    fun trainingLoad(ratio: Double): Double =
        interpolate(ratio, listOf(0.0 to 70.0, 0.5 to 82.0, 0.8 to 92.0, 1.0 to 88.0, 1.3 to 75.0, 1.6 to 45.0, 2.5 to 20.0))

    /** Linear interpolation between the given points, flat outside their range. */
    fun interpolate(x: Double, points: List<Pair<Double, Double>>): Double {
        val first = points.firstOrNull() ?: return NEUTRAL
        val last = points.last()
        if (x <= first.first) return first.second
        if (x >= last.first) return last.second
        for ((lower, upper) in points.zipWithNext()) {
            if (!(x <= upper.first)) continue
            val span = upper.first - lower.first
            if (!(span > 0)) return upper.second
            return lower.second + (upper.second - lower.second) * (x - lower.first) / span
        }
        return last.second
    }
}

/**
 * A measurement together with the personal baseline it is judged against. Unit-agnostic: the
 * z-score compares like with like, so any HRV flavour (SDNN, RMSSD) works if value and history
 * share it.
 */
data class MetricBaseline(
    val value: Double,
    val mean: Double,
    val standardDeviation: Double,
) {
    /**
     * Deviation in standard deviations, clamped to ±3. `null` when the baseline barely varies,
     * where a z-score would explode.
     */
    val zScore: Double?
        get() {
            if (!(standardDeviation > mean * 0.01 && standardDeviation > 0)) return null
            return minOf(maxOf((value - mean) / standardDeviation, -3.0), 3.0)
        }

    /** Deviation from the baseline as a fraction, for the human-readable line. */
    val relativeDeviation: Double
        get() {
            if (mean == 0.0) return 0.0
            return (value - mean) / mean
        }

    companion object {
        /**
         * Baseline over earlier daily values, judged against the most recent one. `null` below
         * `minimumDays` of history, where "your normal" is guesswork. (Swift failable `init`.)
         */
        fun fromHistory(current: Double, earlierDailyValues: List<Double>, minimumDays: Int = 10): MetricBaseline? {
            if (earlierDailyValues.size < minimumDays) return null
            val count = earlierDailyValues.size.toDouble()
            val mean = earlierDailyValues.fold(0.0) { sum, v -> sum + v } / count
            val variance = earlierDailyValues.fold(0.0) { sum, v -> sum + (v - mean) * (v - mean) } / count
            return MetricBaseline(value = current, mean = mean, standardDeviation = sqrt(variance))
        }
    }
}
