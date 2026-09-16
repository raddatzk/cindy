package me.raddatz.cindy.core.workout

import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.persistence.WorkoutRecord

/**
 * Suggests the plan for the next workout from the last result.
 *
 * Rule: measure the reserve as the mean round time of the first quarter divided by the mean
 * round time of the last quarter (1 = steady pace). With enough reserve the difficulty goes up
 * one notch in a fixed order: duration up to 20 min first, then +1 rep on the exercise furthest
 * below the Cindy prescription, then +5 s plank, and finally a higher round goal (density). A
 * collapse in rounds after a harder plan steps back to the previous plan.
 */
data class ProgressionAdvisor(
    /** Reserve ratio at or above this → progress. */
    val reserveThreshold: Double = 0.85,
    /** Rounds falling by more than this fraction after a harder plan → step back. */
    val collapseFraction: Double = 0.2,
    val maxDurationMinutes: Int = 20,
) {
    data class Recommendation(
        val kind: Kind,
        val plan: WorkoutPlan,
        val reason: ProgressionReason,
    ) {
        sealed interface Kind {
            data object Hold : Kind
            data object Duration : Kind
            data class Reps(val exercise: Exercise) : Kind
            data object Plank : Kind
            data class Density(val targetRounds: Int) : Kind
            data object StepBack : Kind
        }

        val changesPlan: Boolean get() = kind != Kind.Hold && kind !is Kind.Density
    }

    fun recommend(after: WorkoutRecord, previous: WorkoutRecord?): Recommendation {
        val record = after
        val plan = record.plan ?: WorkoutPlan.cindy

        if (!record.completed) {
            return Recommendation(Recommendation.Kind.Hold, plan, ProgressionReason.FinishFullDuration(plan.durationMinutes))
        }
        val previousPlan = previous?.plan
        if (previous != null && previousPlan != null && previous.completed &&
            (plan.repsPerRound > previousPlan.repsPerRound || plan.durationMinutes > previousPlan.durationMinutes) &&
            record.rounds.toDouble() < previous.rounds.toDouble() * (1 - collapseFraction)
        ) {
            return Recommendation(
                Recommendation.Kind.StepBack, previousPlan,
                ProgressionReason.RoundsDropped(previous.rounds, record.rounds),
            )
        }
        val reserve = reserveRatio(record)
            ?: return Recommendation(Recommendation.Kind.Hold, plan, ProgressionReason.TooFewRounds)
        if (!(reserve >= reserveThreshold)) {
            return Recommendation(Recommendation.Kind.Hold, plan, ProgressionReason.PaceFading(reserve))
        }

        if (plan.durationMinutes < maxDurationMinutes) {
            val next = plan.copy(durationMinutes = minOf(maxDurationMinutes, plan.durationMinutes + 5))
            return Recommendation(
                Recommendation.Kind.Duration, next,
                ProgressionReason.RaiseDuration(reserve, next.durationMinutes),
            )
        }
        val weakest = weakestExercise(plan)
        if (weakest != null) {
            val next = plan.withTarget(plan.target(weakest) + 1, weakest)
            return Recommendation(
                Recommendation.Kind.Reps(weakest), next,
                ProgressionReason.RaiseReps(reserve, weakest, next.target(weakest)),
            )
        }
        if (plan.contains(Exercise.PLANK)) {
            val next = plan.withTarget(plan.target(Exercise.PLANK) + 5, Exercise.PLANK)
            return Recommendation(
                Recommendation.Kind.Plank, next,
                ProgressionReason.RaisePlank(next.target(Exercise.PLANK)),
            )
        }
        return Recommendation(
            Recommendation.Kind.Density(targetRounds = record.rounds + 1), plan,
            ProgressionReason.RaiseDensity(record.rounds + 1),
        )
    }

    /** Rep exercise with the smallest current/Cindy ratio, if any is below the prescription. */
    private fun weakestExercise(plan: WorkoutPlan): Exercise? {
        val candidates = plan.sets
            .filter { !it.exercise.isHold && WorkoutPlan.cindy.contains(it.exercise) }
            .map { it.exercise to it.target.toDouble() / WorkoutPlan.cindy.target(it.exercise).toDouble() }
            .filter { it.second < 1 }
        var best: Pair<Exercise, Double>? = null
        for (candidate in candidates) {
            if (best == null || candidate.second < best.second) best = candidate
        }
        return best?.first
    }

    companion object {
        /**
         * Pace reserve: mean round duration in the first quarter divided by the mean round duration
         * in the last quarter (1 = steady, below 1 = slowing down), capped at 1.5.
         */
        fun reserveRatio(record: WorkoutRecord): Double? {
            val duration = record.plan?.duration ?: record.durationSeconds
            val stamps = record.roundTimestamps
            if (!(duration > 0) || stamps == null || stamps.size < 4) return null
            val durations = roundDurations(record)
            val quarter = duration / 4
            val pairs = stamps.zip(durations)
            val first = pairs.filter { it.first <= quarter }.map { it.second }
            val last = pairs.filter { it.first > duration - quarter }.map { it.second }
            if (first.isEmpty() || last.isEmpty()) return null
            val firstMean = first.fold(0.0) { a, b -> a + b } / first.size.toDouble()
            val lastMean = last.fold(0.0) { a, b -> a + b } / last.size.toDouble()
            if (!(lastMean > 0)) return null
            return minOf(firstMean / lastMean, 1.5)
        }

        /** Durations of the individual rounds in seconds. */
        fun roundDurations(record: WorkoutRecord): List<Double> {
            val stamps = record.roundTimestamps ?: return emptyList()
            var previous = 0.0
            return stamps.map { stamp ->
                val duration = stamp - previous
                previous = stamp
                duration
            }
        }
    }
}

/** Typed replacement for the localized iOS `Recommendation.reason`. */
sealed interface ProgressionReason {
    /** "Finish the full \(plan.durationMinutes) minutes first, then step up." */
    data class FinishFullDuration(val durationMinutes: Int) : ProgressionReason

    /** "Rounds dropped from \(previous.rounds) to \(record.rounds). Back to the previous plan." */
    data class RoundsDropped(val previousRounds: Int, val rounds: Int) : ProgressionReason

    /** "Too few rounds to judge. Keep the plan." */
    data object TooFewRounds : ProgressionReason

    /**
     * "Last quarter only \(reserveText) of the first. Keep the plan until the pace holds throughout."
     * [reserve] is a ratio (0.8 → "80 %", iOS `formattedPercent`).
     */
    data class PaceFading(val reserve: Double) : ProgressionReason

    /** "Pace steady (\(reserveText)). Raise the time to \(next.durationMinutes) minutes." */
    data class RaiseDuration(val reserve: Double, val durationMinutes: Int) : ProgressionReason

    /** "Pace steady (\(reserveText)). \(weakest.displayName) up to \(next.target(for: weakest)) per round." */
    data class RaiseReps(val reserve: Double, val exercise: Exercise, val target: Int) : ProgressionReason

    /** "Sets are at Cindy level. Plank up to \(next.target(for: .plank)) s." */
    data class RaisePlank(val seconds: Int) : ProgressionReason

    /** "Full Cindy, pace steady. Target next time: \(record.rounds + 1) rounds." */
    data class RaiseDensity(val targetRounds: Int) : ProgressionReason
}
