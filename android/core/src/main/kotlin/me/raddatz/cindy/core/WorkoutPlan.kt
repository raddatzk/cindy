package me.raddatz.cindy.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
 * What the app layer needs to render [WorkoutPlan.summaryWithPlank]: iOS `"%@, then %@"`, the plank
 * as `"%lld × %@"` when it has more than one set.
 */
data class PlanSummary(val round: List<ExerciseSetLabel>, val plank: ExerciseSetLabel.Hold?, val plankSets: Int = 1)

/**
 * Ordered list of exercises that make up one round, the AMRAP duration, and an optional plank
 * held once after the clock has run out.
 *
 * Immutable: the editing functions return a changed copy (Swift `mutating func`s).
 */
@Serializable(with = WorkoutPlanSerializer::class)
data class WorkoutPlan(
    /** The round: rep exercises only. */
    val sets: List<ExerciseSet>,
    val durationMinutes: Int = 20,
    /** Seconds of each plank set after the AMRAP; null = no plank. */
    val plankSeconds: Int? = null,
    /** Plank sets after the AMRAP; the athlete starts each one, there is no set rest. */
    val plankSets: Int = 1,
) {
    /** The round's exercises; the plank is not one of them. */
    val exercises: List<Exercise> get() = sets.map { it.exercise }
    val hasPlank: Boolean get() = plankSeconds != null
    val isValid: Boolean get() = sets.isNotEmpty() && durationMinutes > 0

    /** AMRAP duration in seconds. */
    val duration: Double get() = (durationMinutes * 60).toDouble()
    val first: Exercise get() = exercises[0]
    val last: Exercise get() = exercises[exercises.size - 1]

    /** Score units per round: the reps of the round's exercises. */
    val repsPerRound: Int get() = sets.fold(0) { sum, set -> sum + scoreUnits(set) }

    operator fun contains(exercise: Exercise): Boolean = if (exercise.isHold) hasPlank else exercises.contains(exercise)
    fun isFirst(exercise: Exercise): Boolean = exercise == first
    fun isLast(exercise: Exercise): Boolean = exercise == last

    /** Target of the given exercise (reps or seconds). */
    fun target(exercise: Exercise): Int {
        if (exercise.isHold) return plankSeconds ?: exercise.defaultTarget
        return sets.firstOrNull { it.exercise == exercise }?.target ?: exercise.defaultTarget
    }

    /** Reps the state machine counts for one round exercise. */
    fun countTarget(exercise: Exercise): Int = target(exercise)

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

    /** "5 pull-ups · 10 push-ups · 15 squats" on iOS: one round, the labels joined with " · " by the UI. */
    val summary: List<ExerciseSetLabel> get() = sets.map { it.label }

    /** The round plus the plank after it: "5 pull-ups · 10 push-ups · 15 squats, then 30 s plank". */
    val summaryWithPlank: PlanSummary
        get() = PlanSummary(summary, plankSeconds?.let { ExerciseSetLabel.Hold(it, Exercise.PLANK) }, plankSets)

    private fun scoreUnits(set: ExerciseSet): Int = set.target

    // Editing

    /** Swift `setEnabled(_:_:)`. */
    fun withEnabled(exercise: Exercise, enabled: Boolean): WorkoutPlan =
        if (exercise.isHold) {
            copy(plankSeconds = if (enabled) plankSeconds ?: exercise.defaultTarget else null)
        } else if (enabled) {
            if (contains(exercise)) this
            else copy(sets = sets + ExerciseSet(exercise, exercise.defaultTarget))
        } else {
            copy(sets = sets.filter { it.exercise != exercise })
        }

    /** Swift `setTarget(_:for:)`: clamped into [targetRange]. */
    fun withTarget(target: Int, exercise: Exercise): WorkoutPlan {
        val range = targetRange(exercise)
        val clamped = minOf(maxOf(target, range.first), range.last)
        if (exercise.isHold) return if (plankSeconds == null) this else copy(plankSeconds = clamped)
        val index = sets.indexOfFirst { it.exercise == exercise }
        if (index < 0) return this
        return copy(sets = sets.toMutableList().also { it[index] = it[index].copy(target = clamped) })
    }

    /** Swift `setPlankSets(_:)`: clamped into [plankSetRange]. */
    fun withPlankSets(count: Int): WorkoutPlan =
        copy(plankSets = minOf(maxOf(count, plankSetRange.first), plankSetRange.last))

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
            plan = plan.withTarget(set.target, set.exercise)
        }
        plankSeconds?.let { plan = plan.withTarget((it + 2) / 5 * 5, Exercise.PLANK) }
        return plan.withPlankSets(plankSets)
    }

    companion object {
        /** Plank sets on offer after the AMRAP. */
        val plankSetRange: IntRange = 1..10

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

/** The stored shape of [WorkoutPlan]. */
@Serializable
private data class StoredWorkoutPlan(
    val sets: List<ExerciseSet>,
    val durationMinutes: Int = 20,
    val plankSeconds: Int? = null,
    val plankSets: Int = 1,
)

/**
 * Plans saved while the plank was part of the round (and history records carrying them) had it
 * in `sets`; decoding makes it the finisher (Swift `init(from:)`).
 */
object WorkoutPlanSerializer : KSerializer<WorkoutPlan> {
    private val stored = StoredWorkoutPlan.serializer()
    override val descriptor: SerialDescriptor = stored.descriptor

    override fun serialize(encoder: Encoder, value: WorkoutPlan) {
        encoder.encodeSerializableValue(stored, StoredWorkoutPlan(value.sets, value.durationMinutes, value.plankSeconds, value.plankSets))
    }

    override fun deserialize(decoder: Decoder): WorkoutPlan {
        val plan = decoder.decodeSerializableValue(stored)
        return WorkoutPlan(
            sets = plan.sets.filter { !it.exercise.isHold },
            durationMinutes = plan.durationMinutes,
            plankSeconds = plan.plankSeconds ?: plan.sets.firstOrNull { it.exercise.isHold }?.target,
            plankSets = plan.plankSets,
        )
    }
}
