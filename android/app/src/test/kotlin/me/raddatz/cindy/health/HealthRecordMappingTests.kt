package me.raddatz.cindy.health

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.health.WorkoutHealthSample
import me.raddatz.cindy.core.persistence.WorkoutRecord
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthRecordMappingTests {
    private val zone = ZoneId.of("Europe/Berlin")
    private val id = UUID.fromString("0f8e2c1a-5b6d-4e7f-8a9b-0c1d2e3f4a5b")

    private fun record(duration: Double = 1_200.0, rounds: List<Double> = listOf(300.0, 610.0, 950.0)) = WorkoutRecord(
        id = id,
        date = Instant.parse("2026-09-15T17:20:00Z"),
        rounds = 3,
        extraReps = 7,
        durationSeconds = duration,
        completed = true,
        roundTimestamps = rounds,
        plan = WorkoutPlan.cindy,
    )

    @Test
    fun sessionHasOneSegmentPerRoundAndTheWorkoutIdAsClientRecordId() {
        val record = record()
        val sample = WorkoutHealthSample.from(record, bodyMassKilograms = 80.0)
        val session = HealthRecordMapping.exerciseSession(record, sample, zone)

        assertEquals(Instant.parse("2026-09-15T17:00:00Z"), session.startTime)
        assertEquals(record.date, session.endTime)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS, session.exerciseType)
        assertEquals("Cindy", session.title)
        assertEquals("3 + 7", session.notes)
        assertEquals("0F8E2C1A-5B6D-4E7F-8A9B-0C1D2E3F4A5B", session.metadata.clientRecordId)
        assertEquals(3, session.segments.size)
        assertEquals(Instant.parse("2026-09-15T17:05:00Z"), session.segments[0].endTime)
        assertEquals(Instant.parse("2026-09-15T17:10:10Z"), session.segments[1].endTime)
        assertTrue(session.segments.all { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT })
        assertEquals(zone.rules.getOffset(session.startTime), session.startZoneOffset)
    }

    @Test
    fun energyNeedsABodyMass() {
        val record = record()
        assertNull(HealthRecordMapping.activeCalories(record, WorkoutHealthSample.from(record, null), zone))
        val energy = HealthRecordMapping.activeCalories(record, WorkoutHealthSample.from(record, 80.0), zone)!!
        assertEquals(8.0 * 3.5 * 80 / 200 * 20, energy.energy.inKilocalories, 1e-9)
        assertEquals("0F8E2C1A-5B6D-4E7F-8A9B-0C1D2E3F4A5B-energy", energy.metadata.clientRecordId)
    }

    @Test
    fun aWorkoutWithoutDurationIsNotExportable() {
        assertFalse(HealthRecordMapping.isExportable(WorkoutHealthSample.from(record(duration = 0.0, rounds = emptyList()), null)))
        assertTrue(HealthRecordMapping.isExportable(WorkoutHealthSample.from(record(), null)))
    }

    @Test
    fun dailyAveragesGroupByLocalDay() {
        val samples = listOf(
            Instant.parse("2026-09-13T22:30:00Z") to 40.0, // 00:30 on the 14th in Berlin
            Instant.parse("2026-09-14T06:00:00Z") to 50.0,
            Instant.parse("2026-09-13T21:30:00Z") to 70.0, // 23:30 on the 13th
        )
        val daily = HealthRecordMapping.dailyAverages(samples, zone)
        assertEquals(2, daily.size)
        assertEquals(Instant.parse("2026-09-12T22:00:00Z"), daily[0].date)
        assertEquals(70.0, daily[0].value)
        assertEquals(Instant.parse("2026-09-13T22:00:00Z"), daily[1].date)
        assertEquals(45.0, daily[1].value)
    }

    @Test
    fun sleepWindowStartsAtNoonTheDayBefore() {
        assertEquals(
            Instant.parse("2026-09-14T10:00:00Z"),
            HealthRecordMapping.sleepWindowStart(Instant.parse("2026-09-15T04:00:00Z"), zone),
        )
        assertEquals(
            Instant.parse("2026-09-14T10:00:00Z"),
            HealthRecordMapping.sleepWindowStart(Instant.parse("2026-09-15T21:00:00Z"), zone),
        )
    }

    @Test
    fun onlyAsleepStagesCount() {
        val t = Instant.parse("2026-09-14T22:00:00Z")
        fun part(from: Long, to: Long, stage: Int?) =
            HealthRecordMapping.SleepPart(t.plusSeconds(from * 60), t.plusSeconds(to * 60), stage)
        val intervals = HealthRecordMapping.asleepIntervals(
            listOf(
                part(0, 10, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
                part(10, 70, SleepSessionRecord.STAGE_TYPE_LIGHT),
                part(70, 130, SleepSessionRecord.STAGE_TYPE_DEEP),
                part(130, 140, SleepSessionRecord.STAGE_TYPE_AWAKE),
                part(140, 200, SleepSessionRecord.STAGE_TYPE_REM),
                part(200, 210, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED),
                part(210, 240, SleepSessionRecord.STAGE_TYPE_SLEEPING),
                part(240, 250, SleepSessionRecord.STAGE_TYPE_UNKNOWN),
                part(300, 360, null), // a session without stages
            ),
        )
        assertEquals(listOf(60L, 60L, 60L, 30L, 60L), intervals.map { (it.duration / 60).toLong() })
    }
}
