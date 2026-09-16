package me.raddatz.cindy.health

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import me.raddatz.cindy.core.DateInterval
import me.raddatz.cindy.core.health.HealthSeries
import me.raddatz.cindy.core.health.WorkoutHealthSample
import me.raddatz.cindy.core.persistence.WorkoutRecord
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Builds the Health Connect records for one workout (iOS: the `HKWorkoutBuilder` calls in
 * `HealthExporter`). Kept free of the client so the mapping can be unit-tested.
 */
object HealthRecordMapping {
    /** Session title, the WOD's name in every language. */
    const val TITLE: String = "Cindy"

    /**
     * Calisthenics is Health Connect's closest match for iOS's `.crossTraining` with pull-ups,
     * push-ups and squats. Rounds are "other workout" segments, which Health Connect accepts in
     * any session type.
     */
    const val EXERCISE_TYPE: Int = ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
    const val ROUND_SEGMENT_TYPE: Int = ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT

    /**
     * The workout's UUID as Health Connect client record id. Inserting a record with an id that is
     * already stored updates it instead of adding a second one, so a re-export (reinstall, lost
     * preferences) cannot duplicate a workout — what iOS needs `healthExportedWorkoutIDs` for.
     */
    fun sessionClientRecordId(id: UUID): String = id.toString().uppercase()

    fun energyClientRecordId(id: UUID): String = "${sessionClientRecordId(id)}-energy"

    /** Bump when the mapping changes, so a re-export replaces records written by an older version. */
    const val CLIENT_RECORD_VERSION: Long = 1

    private val device = Device(type = Device.TYPE_PHONE)

    /** Whether the workout has a duration Health Connect can store (start strictly before end). */
    fun isExportable(sample: WorkoutHealthSample): Boolean = sample.start.isBefore(sample.end)

    /**
     * One session with a segment per completed round. The score (language-neutral, "12 + 7") goes
     * into the notes; Health Connect has no free-form metadata like HealthKit's.
     */
    fun exerciseSession(record: WorkoutRecord, sample: WorkoutHealthSample, zone: ZoneId): ExerciseSessionRecord =
        ExerciseSessionRecord(
            startTime = sample.start,
            startZoneOffset = zone.rules.getOffset(sample.start),
            endTime = sample.end,
            endZoneOffset = zone.rules.getOffset(sample.end),
            metadata = Metadata.activelyRecorded(device, sessionClientRecordId(record.id), CLIENT_RECORD_VERSION),
            exerciseType = EXERCISE_TYPE,
            title = TITLE,
            notes = sample.score,
            segments = sample.rounds.map { round ->
                ExerciseSegment(
                    startTime = round.start,
                    endTime = round.end,
                    segmentType = ROUND_SEGMENT_TYPE,
                )
            },
        )

    /** The MET estimate as active energy; `null` without a body mass, like iOS. */
    fun activeCalories(record: WorkoutRecord, sample: WorkoutHealthSample, zone: ZoneId): ActiveCaloriesBurnedRecord? {
        val kilocalories = sample.activeEnergyKilocalories ?: return null
        return ActiveCaloriesBurnedRecord(
            startTime = sample.start,
            startZoneOffset = zone.rules.getOffset(sample.start),
            endTime = sample.end,
            endZoneOffset = zone.rules.getOffset(sample.end),
            energy = Energy.kilocalories(kilocalories),
            metadata = Metadata.activelyRecorded(device, energyClientRecordId(record.id), CLIENT_RECORD_VERSION),
        )
    }

    /**
     * Daily means of a metric, one value per local calendar day, dated at the start of that day —
     * what iOS's `HKStatisticsCollectionQuery` with `.discreteAverage` and a daily interval
     * anchored at midnight returns.
     */
    fun dailyAverages(samples: List<Pair<Instant, Double>>, zone: ZoneId): List<HealthSeries.DailyValue> =
        samples
            .groupBy { it.first.atZone(zone).toLocalDate() }
            .map { (day, values) ->
                HealthSeries.DailyValue(
                    date = day.atStartOfDay(zone).toInstant(),
                    value = values.sumOf { it.second } / values.size,
                )
            }
            .sortedBy { it.date }

    /**
     * Start of the "last night" window: noon the day before, so it covers the night whether the
     * app asks at 6 in the morning or at 11 at night.
     */
    fun sleepWindowStart(now: Instant, zone: ZoneId): Instant =
        now.atZone(zone).toLocalDate().minusDays(1).atStartOfDay(zone).plusHours(12).toInstant()

    /** A sleep stage (or a stage-less session) as it counts towards time asleep. */
    data class SleepPart(val start: Instant, val end: Instant, val stage: Int?)

    /**
     * Time in bed and awake phases do not count as sleep; iOS keeps asleepUnspecified, Core, Deep
     * and REM, which are Health Connect's SLEEPING, LIGHT, DEEP and REM. A session without stages
     * only says that someone slept then, so it counts as a whole, like `asleepUnspecified`.
     */
    fun asleepIntervals(parts: List<SleepPart>): List<DateInterval> =
        parts
            .filter { it.stage == null || it.stage in ASLEEP_STAGES }
            .map { DateInterval(it.start, maxOf(it.start, it.end)) }

    val ASLEEP_STAGES: Set<Int> = setOf(
        androidx.health.connect.client.records.SleepSessionRecord.STAGE_TYPE_SLEEPING,
        androidx.health.connect.client.records.SleepSessionRecord.STAGE_TYPE_LIGHT,
        androidx.health.connect.client.records.SleepSessionRecord.STAGE_TYPE_DEEP,
        androidx.health.connect.client.records.SleepSessionRecord.STAGE_TYPE_REM,
    )
}
