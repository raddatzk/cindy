package me.raddatz.cindy.core.health

import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.addingSeconds
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.core.secondsSince
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutHealthSampleTests {
    private val end = Instant.ofEpochSecond(1_700_000_000)

    private fun record(duration: Double, stamps: List<Double>?, completed: Boolean = true): WorkoutRecord =
        WorkoutRecord(
            date = end, rounds = stamps?.size ?: 0, extraReps = 3, durationSeconds = duration,
            completed = completed, repsPerRound = 30, roundTimestamps = stamps, plan = WorkoutPlan.cindy,
        )

    @Test
    fun sampleSpansTheActiveTimeEndingWhenTheWorkoutEnded() {
        val sample = WorkoutHealthSample.from(record(1200.0, listOf(60.0, 130.0)), bodyMassKilograms = null)
        assertEquals(end, sample.end)
        assertEquals(end.addingSeconds(-1200.0), sample.start)
        assertEquals("2 + 3", sample.score)
        assertEquals(63, sample.totalReps)
    }

    @Test
    fun roundsBecomeBackToBackSegments() {
        val sample = WorkoutHealthSample.from(record(300.0, listOf(60.0, 130.0, 210.0)), bodyMassKilograms = null)
        assertEquals(listOf(1, 2, 3), sample.rounds.map { it.index })
        assertEquals(sample.start, sample.rounds[0].start)
        assertEquals(sample.start.addingSeconds(60.0), sample.rounds[0].end)
        assertEquals(sample.rounds[0].end, sample.rounds[1].start)
        assertEquals(sample.start.addingSeconds(210.0), sample.rounds[2].end)
    }

    @Test
    fun roundsBeyondTheRecordedDurationAreClampedNotDropped() {
        // The final tick can push the last stamp a hair past the truncated duration.
        val sample = WorkoutHealthSample.from(record(200.0, listOf(100.0, 200.4)), bodyMassKilograms = null)
        assertEquals(2, sample.rounds.size)
        assertEquals(sample.end, sample.rounds[1].end)
        // A duplicate stamp after the clamp adds no zero-length segment.
        val clamped = WorkoutHealthSample.from(record(200.0, listOf(100.0, 200.4, 200.9)), bodyMassKilograms = null)
        assertEquals(2, clamped.rounds.size)
    }

    @Test
    fun recordWithoutRoundTimestampsStillMaps() {
        val sample = WorkoutHealthSample.from(record(600.0, null), bodyMassKilograms = 80.0)
        assertTrue(sample.rounds.isEmpty())
        assertEquals(600.0, sample.end.secondsSince(sample.start))
    }

    @Test
    fun energyIsEstimatedOnlyWithABodyMass() {
        val withoutMass = WorkoutHealthSample.from(record(1200.0, listOf(60.0)), bodyMassKilograms = null)
        assertNull(withoutMass.activeEnergyKilocalories)
        val zeroMass = WorkoutHealthSample.from(record(1200.0, listOf(60.0)), bodyMassKilograms = 0.0)
        assertNull(zeroMass.activeEnergyKilocalories)

        // 8 MET · 3.5 · 80 kg / 200 · 20 min = 224 kcal
        val sample = WorkoutHealthSample.from(record(1200.0, listOf(60.0)), bodyMassKilograms = 80.0)
        assertEquals(224.0, sample.activeEnergyKilocalories)
    }

    @Test
    fun abortedWorkoutKeepsItsFlag() {
        val sample = WorkoutHealthSample.from(record(400.0, listOf(90.0), completed = false), bodyMassKilograms = null)
        assertFalse(sample.completed)
    }
}
