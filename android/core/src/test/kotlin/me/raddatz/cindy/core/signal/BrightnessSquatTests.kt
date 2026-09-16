package me.raddatz.cindy.core.signal

import me.raddatz.cindy.core.CSVSignalReplay
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.FixtureLocator
import me.raddatz.cindy.core.Rect
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.SyntheticSignal
import me.raddatz.cindy.core.calibration.CalibrationAnalyzer
import me.raddatz.cindy.core.calibration.CalibrationFailure
import me.raddatz.cindy.core.calibration.CalibrationSample
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Squats on the image brightness, replayed from device recordings (2026-09-14) with the face and
 * pose next to it, so calibration, rest shift and `BodyEvidence` all run.
 */
class BrightnessSquatTests {
    companion object {
        /**
         * Bottoms of the squats, read off the recording protocol: 3 s still, 5 squats looking
         * ahead, 3 s still, 5 squats looking down at the phone.
         */
        val cleanBottoms = listOf(5.9, 8.1, 10.2, 12.3, 14.5, 19.6, 21.6, 23.8, 26.0, 28.4)

        /** Mixed gaze: 1–4 looking down, 5–8 looking ahead. */
        val mixedBottoms = listOf(4.3, 6.1, 8.2, 10.2, 12.5, 14.5, 16.5, 18.5)
    }

    private fun frames(name: String, lumaOffset: Float = 0f): List<FrameObservation> =
        CSVSignalReplay.observations(FixtureLocator.require(name), lumaOffset)

    private data class Match(val hits: Int, val extra: Int)

    /** Each counted rep must land within 1.6 s after a distinct bottom. */
    private fun matchedBottoms(reps: List<Double>, bottoms: List<Double>): Match {
        val used = mutableSetOf<Int>()
        var extra = 0
        for (rep in reps) {
            val index = bottoms.indices.firstOrNull {
                it !in used && rep >= bottoms[it] - 0.2 && rep <= bottoms[it] + 1.6
            }
            if (index != null) used += index else extra += 1
        }
        return Match(used.size, extra)
    }

    @Test
    fun cleanRecordingCountsAllTenSquats() {
        for (lumaOffset in listOf(-0.06f, 0f, 0.06f)) {
            // Calibrated on the still stand and the first squat looking ahead; the offset shifts the
            // whole workout (not the calibration) brighter or darker.
            val calibration = assertNotNull(
                CSVSignalReplay.calibrate(
                    frames("recorded_squats_10_clean"), from = 3.4, to = 7.4,
                    exercise = Exercise.SQUAT, source = SignalSource.BRIGHTNESS,
                ),
            )
            assertEquals(RepDirection.TROUGH, calibration.direction)
            val result = CSVSignalReplay.countReps(
                frames("recorded_squats_10_clean", lumaOffset), Exercise.SQUAT,
                calibration.thresholds, SignalSource.BRIGHTNESS,
            )
            val match = matchedBottoms(result.reps, cleanBottoms)
            assertEquals(10, match.hits, "hits at offset $lumaOffset")
            assertEquals(0, match.extra, "extra at offset $lumaOffset")
            assertEquals(0, result.rejected, "rejected at offset $lumaOffset")
        }
    }

    @Test
    fun mixedGazeRecordingCountsAllEightSquats() {
        val recording = frames("recorded_squats_8_mixed_gaze")
        val calibration = assertNotNull(
            CSVSignalReplay.calibrate(recording, from = 3.0, to = 5.6, exercise = Exercise.SQUAT, source = SignalSource.BRIGHTNESS),
        )
        val result = CSVSignalReplay.countReps(recording, Exercise.SQUAT, calibration.thresholds, SignalSource.BRIGHTNESS)
        val match = matchedBottoms(result.reps, mixedBottoms)
        assertEquals(8, match.hits)
        assertEquals(0, match.extra)
    }

    @Test
    fun darkeningWithoutAMovingBodyIsRejected() {
        // A cloud passes while the athlete stands still: brightness dips like a squat,
        // but face and pose stay put.
        val thresholds = RepThresholds(0.44f, 0.47f, RepDirection.TROUGH, baseline = 0.49f, adaptation = RestAdaptation.SHIFT)
        val pipeline = SignalPipeline(Exercise.SQUAT, thresholds, SignalSource.BRIGHTNESS)
        val still = BodyPoseObservation(
            mapOf(
                PoseJoint.LEFT_SHOULDER to PosePoint(0f, 0f, 0.7f),
                PoseJoint.RIGHT_SHOULDER to PosePoint(0.14f, 0f, 0.7f),
            ),
        )
        val face = FaceObservation(Rect(0.4, 0.4, 0.08, 0.08), 0.9f)
        val events = mutableListOf<RepDetectorEvent>()
        for (sample in SyntheticSignal(rest = 0.49f, peak = 0.40f).reps(3)) {
            val frame = FrameObservation(sample.t, face, still, metrics = FrameMetrics(lumaMean = sample.value))
            pipeline.process(frame).event?.let { events += it }
        }
        assertTrue(events.contains(RepDetectorEvent.Armed))
        assertFalse(events.any { it is RepDetectorEvent.RepCompleted })
        assertEquals(3, events.count { it is RepDetectorEvent.RepRejected })
    }

    @Test
    fun darkeningWithAGrowingFaceCounts() {
        val thresholds = RepThresholds(0.44f, 0.47f, RepDirection.TROUGH, baseline = 0.49f, adaptation = RestAdaptation.SHIFT)
        val pipeline = SignalPipeline(Exercise.SQUAT, thresholds, SignalSource.BRIGHTNESS)
        var reps = 0
        for (sample in SyntheticSignal(rest = 0.49f, peak = 0.40f).reps(3)) {
            // The face grows as the brightness falls (looking down, coming closer).
            val depth = ((0.49f - (sample.value ?: 0.49f)) / 0.09f).toDouble()
            val side = 0.08 + 0.1 * depth
            val frame = FrameObservation(
                sample.t,
                face = FaceObservation(Rect(0.4, 0.4, side, side), 0.9f),
                metrics = FrameMetrics(lumaMean = sample.value),
            )
            if (pipeline.process(frame).event is RepDetectorEvent.RepCompleted) reps += 1
        }
        assertEquals(3, reps)
    }

    @Test
    fun calibrationFollowsTheFirstExcursionPastAnOvershoot() {
        // Darker first (the squat), then brighter than rest while standing up: still a trough.
        val samples = mutableListOf<CalibrationSample>()
        var t = 0.0
        fun add(value: Float, frames: Int) {
            repeat(frames) {
                samples += CalibrationSample(t, value)
                t += 1.0 / 30
            }
        }
        add(0.50f, 20)
        for (i in 0 until 20) add(0.50f - 0.04f * sin(i.toFloat() / 19 * PI.toFloat()), 1)
        for (i in 0 until 15) add(0.50f + 0.05f * sin(i.toFloat() / 14 * PI.toFloat()), 1)
        add(0.50f, 15)
        val calibration = CalibrationAnalyzer(source = SignalSource.BRIGHTNESS).evaluate(samples)
        assertEquals(RepDirection.TROUGH, calibration?.direction)
        // Rest-anchored: leave at 30 % and peak at 70 % of the 0.04 dip.
        assertTrue(abs((calibration?.high ?: 0f) - 0.488f) < 0.002f)
        assertTrue(abs((calibration?.low ?: 0f) - 0.472f) < 0.002f)
    }

    @Test
    fun tooLittleDarkeningIsLowContrast() {
        val samples = (0 until 90).map { i ->
            val dip = if (i in 30 until 50) 0.008f else 0f
            CalibrationSample(i.toDouble() / 30, 0.5f - dip)
        }
        assertEquals(CalibrationFailure.LowContrast, CalibrationAnalyzer(source = SignalSource.BRIGHTNESS).diagnose(samples))
    }

    @Test
    fun shiftedThresholdsMoveWithTheRestLevel() {
        val thresholds = RepThresholds(0.44f, 0.47f, RepDirection.TROUGH, baseline = 0.49f, adaptation = RestAdaptation.SHIFT)
        val adapted = thresholds.adapted(toRest = 0.55f)
        assertTrue(abs(adapted.low - 0.50f) < 1e-5f)
        assertTrue(abs(adapted.high - 0.53f) < 1e-5f)
        // Arms at a rest 0.06 brighter, but not at the phone (0.2 brighter, beyond `restShiftTolerance`).
        val detector = RepDetector(thresholds)
        for (i in 0 until 20) detector.process(0.69f, 1f, i.toDouble() / 30)
        assertFalse(detector.isArmed)
        for (i in 20 until 40) detector.process(0.55f, 1f, i.toDouble() / 30)
        assertTrue(detector.isArmed)
    }
}
