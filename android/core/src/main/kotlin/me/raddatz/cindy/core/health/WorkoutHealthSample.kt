package me.raddatz.cindy.core.health

import me.raddatz.cindy.core.addingSeconds
import me.raddatz.cindy.core.persistence.WorkoutRecord
import java.time.Instant

/**
 * The values the health store needs for one finished workout, derived from a [WorkoutRecord].
 * Free of any health API so the mapping can be unit-tested.
 *
 * Cindy's clock counts active time only — pausing leaves no gap in `roundTimestamps`. The sample
 * therefore spans the active time and ends when the workout ended: a paused session appears in
 * the health store as having started later than it really did, but its duration and round
 * segments stay exact.
 */
data class WorkoutHealthSample(
    val start: Instant,
    val end: Instant,
    val rounds: List<Round>,
    /** MET estimate; `null` when the body mass is unknown — better no number than a made-up one. */
    val activeEnergyKilocalories: Double?,
    /** Score in the app's notation ("12 + 7"), for the health metadata. */
    val score: String,
    val totalReps: Int,
    val completed: Boolean,
) {
    /** One completed round as a segment of the session. */
    data class Round(
        /** 1-based, as shown in the app. */
        val index: Int,
        val start: Instant,
        val end: Instant,
    )

    companion object {
        /** Calisthenics circuit, vigorous effort (Compendium of Physical Activities). */
        const val METABOLIC_EQUIVALENT: Double = 8.0

        /** Swift `init(record:bodyMassKilograms:)`. */
        fun from(record: WorkoutRecord, bodyMassKilograms: Double?): WorkoutHealthSample {
            val duration = maxOf(record.durationSeconds, 0.0)
            val start = record.date.addingSeconds(-duration)

            var previous = 0.0
            val rounds = (record.roundTimestamps ?: emptyList()).mapIndexedNotNull { index, rawStamp ->
                // Rounds finishing after the recorded duration can only be rounding noise from
                // the final tick, so they are clamped rather than dropped.
                val stamp = minOf(rawStamp, duration)
                val round = if (stamp > previous) {
                    Round(index + 1, start.addingSeconds(previous), start.addingSeconds(stamp))
                } else {
                    null
                }
                previous = maxOf(previous, stamp)
                round
            }

            val energy = if (bodyMassKilograms != null && bodyMassKilograms > 0 && duration > 0) {
                METABOLIC_EQUIVALENT * 3.5 * bodyMassKilograms / 200 * (duration / 60)
            } else {
                null
            }
            return WorkoutHealthSample(
                start = start,
                end = record.date,
                rounds = rounds,
                activeEnergyKilocalories = energy,
                score = record.score.notation,
                totalReps = record.score.totalReps,
                completed = record.completed,
            )
        }
    }
}
