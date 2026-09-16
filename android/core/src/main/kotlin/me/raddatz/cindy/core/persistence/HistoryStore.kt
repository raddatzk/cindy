package me.raddatz.cindy.core.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.raddatz.cindy.core.InstantIso8601Serializer
import me.raddatz.cindy.core.UUIDSerializer
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.workout.WorkoutScore
import java.io.File
import java.time.Instant
import java.util.UUID

/** One finished (or aborted) workout. */
@Serializable
data class WorkoutRecord(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID = UUID.randomUUID(),
    /** When the workout ended. */
    @Serializable(with = InstantIso8601Serializer::class)
    val date: Instant,
    val rounds: Int,
    val extraReps: Int,
    val durationSeconds: Double,
    /** false when the workout was stopped before the planned time was up. */
    val completed: Boolean,
    val repsPerRound: Int = WorkoutPlan.cindy.repsPerRound,
    /** Elapsed seconds at which each round was completed. */
    val roundTimestamps: List<Double>? = null,
    /** The plan that was performed. */
    val plan: WorkoutPlan? = null,
) {
    val score: WorkoutScore get() = WorkoutScore(rounds, extraReps, repsPerRound)

    /**
     * Full AMRAP duration of the plan that was performed, in seconds. Records written before
     * plans were stored fall back to the classic 20 minutes.
     */
    val plannedDuration: Double get() = plan?.duration ?: WorkoutPlan.cindy.duration
}

/** Workout history in one JSON file (iOS: `history.json` in Documents). */
class HistoryStore(file: File) {
    private val store = JSONFileStore(file, ListSerializer(WorkoutRecord.serializer()))

    /** Newest first. */
    fun load(): List<WorkoutRecord> = (store.load() ?: emptyList()).sortedByDescending { it.date }

    /** @throws java.io.IOException when the file cannot be written. */
    fun append(record: WorkoutRecord) {
        store.save(listOf(record) + load())
    }

    /** @throws java.io.IOException when the file cannot be written. */
    fun delete(id: UUID) {
        store.save(load().filter { it.id != id })
    }
}
