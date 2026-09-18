package me.raddatz.cindy.core.calibration

import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.Sample
import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.SyntheticSignal
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.signal.RepDirection
import me.raddatz.cindy.core.signal.RepThresholds
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationAnalyzerTests {
    private fun trace(samples: List<Sample>): List<CalibrationSample> = samples.mapNotNull { sample ->
        val value = sample.value
        if (value == null || sample.confidence < 0.5f) null else CalibrationSample(sample.t, value)
    }

    private fun tempDirectory(): File = Files.createTempDirectory("cindy-calibration").toFile().apply { deleteOnExit() }

    @Test
    fun detectsOnePeakCycle() {
        val samples = SyntheticSignal(rest = 0.05f, peak = 0.20f).reps(1, period = 1.6, leadIn = 0.8, leadOut = 0.5)
        val analyzer = CalibrationAnalyzer(source = SignalSource.FACE)
        val calibration = assertNotNull(analyzer.evaluate(trace(samples)))
        assertEquals(RepDirection.PEAK, calibration.direction)
        assertTrue(abs(calibration.minValue - 0.05f) < 0.01f)
        assertTrue(abs(calibration.maxValue - 0.20f) < 0.01f)
        assertTrue(calibration.low > calibration.minValue)
        assertTrue(calibration.high < calibration.maxValue)
        assertTrue(calibration.low < calibration.high)
        assertTrue(calibration.repDuration > 0.8 && calibration.repDuration < 2.0)
        assertTrue(abs(calibration.baseline - 0.05f) < 0.01f)
    }

    @Test
    fun calibratesAtFiveFramesPerSecond() {
        // Galaxy A20e: half a second of rest holds three samples, not the five the 30 fps rule wants.
        val samples = SyntheticSignal(fps = 5.0, rest = 0.05f, peak = 0.20f).reps(1, period = 1.6, leadIn = 0.8, leadOut = 0.5)
        val calibration = assertNotNull(CalibrationAnalyzer(source = SignalSource.FACE).evaluate(trace(samples)))
        assertEquals(RepDirection.PEAK, calibration.direction)
        assertTrue(abs(calibration.baseline - 0.05f) < 0.01f)
    }

    @Test
    fun aPatchyBaselineAtThirtyFramesPerSecondIsStillRejected() {
        val samples = SyntheticSignal(rest = 0.05f, peak = 0.20f).reps(1, period = 1.6, leadIn = 0.8, leadOut = 0.5)
        // Only four confident samples in the first half second; the rest of the trace at full rate.
        val patchy = samples.mapIndexed { i, s -> if (s.t <= 0.5 && i % 4 != 0) Sample(s.t, s.value, 0f) else s }
        assertNull(CalibrationAnalyzer(source = SignalSource.FACE).evaluate(trace(patchy)))
    }

    @Test
    fun detectsTroughCycle() {
        val peak = SyntheticSignal(rest = 0.05f, peak = 0.20f).reps(1, leadIn = 0.8, leadOut = 0.5)
        val inverted = peak.map { Sample(it.t, it.value?.let { v -> 0.25f - v }, it.confidence) }
        val calibration = assertNotNull(CalibrationAnalyzer(source = SignalSource.FACE).evaluate(trace(inverted)))
        assertEquals(RepDirection.TROUGH, calibration.direction)
        assertTrue(abs(calibration.baseline - 0.20f) < 0.01f)
    }

    @Test
    fun incompleteCycleIsNotAccepted() {
        // Cut the trace right after the peak: no return to the rest band.
        val samples = SyntheticSignal().reps(1, leadIn = 0.8, leadOut = 0.0)
        var peakIndex = 0
        for (i in samples.indices) if ((samples[peakIndex].value ?: 0f) < (samples[i].value ?: 0f)) peakIndex = i
        val cut = samples.subList(0, peakIndex + 1)
        val analyzer = CalibrationAnalyzer(source = SignalSource.FACE)
        assertNull(analyzer.evaluate(trace(cut)))
        assertEquals(CalibrationFailure.NoReturn, analyzer.diagnose(trace(cut)))
    }

    @Test
    fun weakSignalIsDiagnosed() {
        val samples = SyntheticSignal(rest = 0.05f, peak = 0.055f).reps(1, leadIn = 0.8)
        val analyzer = CalibrationAnalyzer(source = SignalSource.FACE)
        assertNull(analyzer.evaluate(trace(samples)))
        assertEquals(CalibrationFailure.TooWeak, analyzer.diagnose(trace(samples)))
    }

    @Test
    fun noSamplesMeansNoFace() {
        val analyzer = CalibrationAnalyzer(source = SignalSource.FACE)
        assertEquals(CalibrationFailure.NoFace, analyzer.diagnose(emptyList()))
        assertNull(analyzer.evaluate(emptyList()))
    }

    @Test
    fun tooFastCycleIsDiagnosed() {
        val samples = SyntheticSignal().reps(1, period = 0.2, leadIn = 0.8)
        val analyzer = CalibrationAnalyzer(source = SignalSource.FACE)
        assertNull(analyzer.evaluate(trace(samples)))
        val failure = assertIs<CalibrationFailure.ImplausibleDuration>(analyzer.diagnose(trace(samples)))
        assertTrue(failure.duration < 0.5)
    }

    @Test
    fun profileRoundTripsThroughJSON() {
        val store = CalibrationStore(File(tempDirectory(), "calibration.json"))
        var profile = CalibrationProfile()
        assertFalse(profile.isComplete)
        for (exercise in Exercise.entries) {
            profile = profile.withCalibration(
                ExerciseCalibration(
                    source = SignalConfig.default.source(exercise), minValue = 0.1f, maxValue = 0.3f, baseline = 0.1f,
                    low = 0.15f, high = 0.25f, direction = exercise.defaultRepDirection, repDuration = 1.2,
                    calibratedAt = Instant.now(),
                ),
                exercise,
            )
        }
        assertTrue(profile.isComplete)
        store.save(profile)
        val loaded = assertNotNull(store.load())
        assertTrue(loaded.isComplete)
        assertEquals(RepDirection.TROUGH, loaded.calibration(Exercise.PULL_UP)?.direction)
        assertEquals(
            RepThresholds(0.15f, 0.25f, RepDirection.PEAK, baseline = 0.1f),
            loaded.calibration(Exercise.SQUAT)?.thresholds,
        )
    }

    @Test
    fun calibrationsOnAnOldSignalSourceAreDropped() {
        val store = CalibrationStore(File(tempDirectory(), "calibration.json"))
        var profile = CalibrationProfile()
        for (exercise in Exercise.entries) {
            // Every exercise on the face, as squats were calibrated before they moved to body pose.
            profile = profile.withCalibration(
                ExerciseCalibration(
                    source = SignalSource.FACE, minValue = 0.1f, maxValue = 0.3f, baseline = 0.1f, low = 0.15f,
                    high = 0.25f, direction = exercise.defaultRepDirection, repDuration = 1.2,
                    calibratedAt = Instant.now(),
                ),
                exercise,
            )
        }
        store.save(profile)
        val loaded = assertNotNull(store.load())
        assertEquals(listOf(Exercise.SQUAT), loaded.missingExercises(WorkoutPlan.cindy))
        assertNotNull(loaded.calibration(Exercise.PUSH_UP))
    }

    @Test
    fun thePlankNeedsNoCalibration() {
        val plan = WorkoutPlan.cindy.withEnabled(Exercise.PLANK, true)
        var profile = CalibrationProfile()
        for (exercise in WorkoutPlan.cindy.exercises) {
            profile = profile.withCalibration(
                ExerciseCalibration(
                    source = SignalConfig.default.source(exercise), minValue = 0.1f, maxValue = 0.3f, baseline = 0.1f,
                    low = 0.15f, high = 0.25f, direction = exercise.defaultRepDirection, repDuration = 1.2,
                    calibratedAt = Instant.now(),
                ),
                exercise,
            )
        }
        assertTrue(profile.missingExercises(plan).isEmpty())
        assertTrue(profile.isComplete(plan))
        // A plank calibration from before the timer is dropped on load.
        profile = profile.withCalibration(
            ExerciseCalibration(
                source = SignalConfig.default.source(Exercise.PLANK), minValue = 0.1f, maxValue = 0.3f,
                baseline = 0.2f, low = 0.15f, high = 0.25f, direction = RepDirection.PEAK, repDuration = 3.0,
                calibratedAt = Instant.now(),
            ),
            Exercise.PLANK,
        )
        assertNull(profile.removingOutdated(SignalConfig.default).calibration(Exercise.PLANK))
    }
}
