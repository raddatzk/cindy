package me.raddatz.cindy.core

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** One entry of a round: an exercise with its target (reps, or seconds for holds). */
@Serializable
data class ExerciseSet(val exercise: Exercise, val target: Int) {
    val id: Exercise get() = exercise

    /** Typed replacement for the localized iOS `label`. */
    val label: ExerciseSetLabel
        get() = if (exercise.isHold) {
            ExerciseSetLabel.Hold(target, exercise)
        } else {
            ExerciseSetLabel.Reps(target, exercise, singular = exercise.usesSingularName(target))
        }
}

/** What the app layer needs to render an [ExerciseSet] label. */
sealed interface ExerciseSetLabel {
    /** Swift `"\(target) s \(exercise.displayName)"`, iOS key `%lld s %@` — e.g. "30 s Plank". */
    data class Hold(val seconds: Int, val exercise: Exercise) : ExerciseSetLabel

    /**
     * Swift `"\(target) \(exercise.name(for: target))"`, iOS key `%lld %@` — e.g. "5 Pull-ups".
     * [singular] picks the singular name ("1 Pull-up").
     */
    data class Reps(val count: Int, val exercise: Exercise, val singular: Boolean) : ExerciseSetLabel
}

/**
 * Ordered list of exercises that make up one round, plus the AMRAP duration.
 *
 * Immutable: the editing functions return a changed copy (Swift `mutating func`s).
 */
@Serializable
data class WorkoutPlan(
    val sets: List<ExerciseSet>,
    val durationMinutes: Int = 20,
) {
    val exercises: List<Exercise> get() = sets.map { it.exercise }
    val isValid: Boolean get() = sets.isNotEmpty() && durationMinutes > 0

    /** AMRAP duration in seconds. */
    val duration: Double get() = (durationMinutes * 60).toDouble()
    val first: Exercise get() = exercises[0]
    val last: Exercise get() = exercises[exercises.size - 1]

    /** Score units per round: reps for movements, 1 for a completed hold. */
    val repsPerRound: Int get() = sets.fold(0) { sum, set -> sum + scoreUnits(set) }

    operator fun contains(exercise: Exercise): Boolean = exercises.contains(exercise)
    fun isFirst(exercise: Exercise): Boolean = exercise == first
    fun isLast(exercise: Exercise): Boolean = exercise == last

    /** Target of the given exercise (reps or seconds). */
    fun target(exercise: Exercise): Int =
        sets.firstOrNull { it.exercise == exercise }?.target ?: exercise.defaultTarget

    /** Reps the state machine counts for one exercise: holds count as a single rep. */
    fun countTarget(exercise: Exercise): Int = if (exercise.isHold) 1 else target(exercise)

    fun next(after: Exercise): Exercise {
        val list = exercises
        val index = list.indexOf(after).takeIf { it >= 0 } ?: 0
        return list[(index + 1) % list.size]
    }

    fun previous(before: Exercise): Exercise {
        val list = exercises
        val index = list.indexOf(before).takeIf { it >= 0 } ?: 0
        return list[(index - 1 + list.size) % list.size]
    }

    /** Score units of the round completed before `exercise` starts. */
    fun repsBefore(exercise: Exercise): Int =
        sets.takeWhile { it.exercise != exercise }.fold(0) { sum, set -> sum + scoreUnits(set) }

    /** "5 pull-ups · 10 push-ups · 15 squats" on iOS: the labels, joined with " · " by the UI. */
    val summary: List<ExerciseSetLabel> get() = sets.map { it.label }

    private fun scoreUnits(set: ExerciseSet): Int = if (set.exercise.isHold) 1 else set.target

    // Editing

    /** Swift `setEnabled(_:_:)`. */
    fun withEnabled(exercise: Exercise, enabled: Boolean): WorkoutPlan =
        if (enabled) {
            if (contains(exercise)) this
            else copy(sets = sets + ExerciseSet(exercise, exercise.defaultTarget))
        } else {
            copy(sets = sets.filter { it.exercise != exercise })
        }

    /** Swift `setTarget(_:for:)`: clamped into [targetRange]. */
    fun withTarget(target: Int, exercise: Exercise): WorkoutPlan {
        val index = sets.indexOfFirst { it.exercise == exercise }
        if (index < 0) return this
        val range = targetRange(exercise)
        val clamped = minOf(maxOf(target, range.first), range.last)
        return copy(sets = sets.toMutableList().also { it[index] = it[index].copy(target = clamped) })
    }

    /** Moves an exercise one place up (-1) or down (+1) in the round (Swift `move(_:by:)`). */
    fun moving(exercise: Exercise, offset: Int): WorkoutPlan {
        val index = sets.indexOfFirst { it.exercise == exercise }
        if (index < 0) return this
        val destination = index + offset
        if (destination !in sets.indices) return this
        val list = sets.toMutableList()
        list[index] = sets[destination]
        list[destination] = sets[index]
        return copy(sets = list)
    }

    /**
     * The plan with durations and targets pulled into the allowed values, for plans saved
     * before those limits existed (e.g. 12 minutes or 25 pull-ups).
     */
    fun normalized(): WorkoutPlan {
        var plan = copy(
            durationMinutes = durationChoices.minByOrNull { abs(it - durationMinutes) } ?: 20,
        )
        for (set in sets) {
            plan = plan.withTarget(if (set.exercise.isHold) (set.target + 2) / 5 * 5 else set.target, set.exercise)
        }
        return plan
    }

    companion object {
        /** The real WOD: 5 pull-ups, 10 push-ups, 15 squats, 20 minutes. */
        val cindy: WorkoutPlan = WorkoutPlan(
            sets = listOf(
                ExerciseSet(Exercise.PULL_UP, 5),
                ExerciseSet(Exercise.PUSH_UP, 10),
                ExerciseSet(Exercise.SQUAT, 15),
            ),
        )

        /**
         * AMRAP lengths on offer: Cindy is 20 minutes, shorter ones are the way in, and the
         * progression advisor steps up by five.
         */
        val durationChoices: List<Int> = listOf(5, 10, 15, 20)

        /** Test plan for home use without a pull-up bar. */
        val withoutPullUps: WorkoutPlan = WorkoutPlan(
            sets = listOf(
                ExerciseSet(Exercise.PUSH_UP, 10),
                ExerciseSet(Exercise.SQUAT, 15),
            ),
        )

        /**
         * Allowed target per exercise: reps up to the Cindy prescription (more work comes from
         * more rounds, not bigger sets), plank seconds in steps of five.
         */
        fun targetRange(exercise: Exercise): IntRange =
            if (exercise.isHold) 5..300 else 1..cindy.target(exercise)
    }
}
