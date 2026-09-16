package me.raddatz.cindy.core.signal

import me.raddatz.cindy.core.CSVSignalReplay
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.FixtureLocator
import me.raddatz.cindy.core.Rect
import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.SyntheticSignal
import me.raddatz.cindy.core.scaled
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs recorded/synthetic CSV traces (FrameLogger format) through the full extractor → EMA →
 * detector chain. Drop real recordings from the debug mode into `shared/fixtures/` and add a case
 * here (and in the iOS suite).
 */
class PipelineReplayTests {
    companion object {
        // Real recordings (iPhone flat on the floor, 2026-09-14). The thresholds are the
        // calibration the app was running with, recovered from the phase changes in the logs.
        val pushUpCalibration = RepThresholds(0.1616f, 0.374f, RepDirection.PEAK, baseline = 0.0554f)
        val squatCalibration = RepThresholds(0.00752f, 0.01178f, RepDirection.PEAK, baseline = 0.00539f)

        // Shoulder width from body pose, recorded with the face request still steering the pose
        // orientation: squats 1–4 looking down, 5–8 looking ahead. From 12 s the face is only found
        // rotated and the pose breaks with it, so only the first four squats carry a usable signal.
        val squatPoseCalibration = RepThresholds(0.3375f, 0.5125f, RepDirection.PEAK, baseline = 0.25f)
    }

    @Test
    fun syntheticPushUpsCountTen() {
        val samples = CSVSignalReplay.load(FixtureLocator.require("synthetic_pushups_10"))
        assertTrue(samples.size > 500)
        val thresholds = RepThresholds.from(min = 0.05f, max = 0.20f, direction = RepDirection.PEAK)
        assertEquals(10, CSVSignalReplay.countReps(samples, Exercise.PUSH_UP, thresholds))
    }

    @Test
    fun recordedPushUpsCountThree() {
        // Face lost for up to 8 frames at the bottom of each rep; one rep was counted before the recording started.
        val samples = CSVSignalReplay.load(FixtureLocator.require("recorded_pushups_3_face"))
        assertEquals(3, CSVSignalReplay.countReps(samples, Exercise.PUSH_UP, pushUpCalibration))
    }

    @Test
    fun recordedSquatsCountFour() {
        // Walks away from the phone, 4 squats, 3.5 s without a face (disarms), walks back to stop the recording.
        val samples = CSVSignalReplay.load(FixtureLocator.require("recorded_squats_4_face"))
        assertEquals(4, CSVSignalReplay.countReps(samples, Exercise.SQUAT, squatCalibration))
    }

    @Test
    fun recordedSquatShoulderWidthCountsTheUsableSquats() {
        for (scale in listOf(0.6f, 1.0f, 1.5f)) {
            val url = FixtureLocator.require("recorded_squats_8_mixed_gaze")
            val samples = CSVSignalReplay.load(url, column = "pose_shoulder_w", confidenceColumn = "pose_conf").scaled(scale)
            // Arms while still walking away from the phone (rest ≈ 0.40); tracking the rest down recovers the reps.
            assertEquals(
                4,
                CSVSignalReplay.countReps(samples, Exercise.SQUAT, squatPoseCalibration, source = SignalSource.POSE),
                "scale $scale",
            )
        }
    }

    @Test
    fun relativeThresholdsSurviveADifferentDistance() {
        for (areaScale in listOf(0.5f, 0.7f, 1.3f, 1.5f, 2.0f)) {
            // Face area scales with the inverse square of the distance to the phone.
            val pushUps = CSVSignalReplay.load(FixtureLocator.require("recorded_pushups_3_face")).scaled(areaScale)
            val squats = CSVSignalReplay.load(FixtureLocator.require("recorded_squats_4_face")).scaled(areaScale)
            assertEquals(3, CSVSignalReplay.countReps(pushUps, Exercise.PUSH_UP, pushUpCalibration), "push-ups at $areaScale")
            assertEquals(4, CSVSignalReplay.countReps(squats, Exercise.SQUAT, squatCalibration), "squats at $areaScale")
        }
    }

    @Test
    fun absoluteThresholdsMissSquatsWhenStandingCloser() {
        val absolute = squatCalibration.copy(baseline = null)
        val squats = CSVSignalReplay.load(FixtureLocator.require("recorded_squats_4_face")).scaled(1.5f)
        assertEquals(0, CSVSignalReplay.countReps(squats, Exercise.SQUAT, absolute))
    }

    @Test
    fun restAdaptationFollowsTheSignal() {
        // Face area and shoulder width scale with the distance, brightness shifts; landmark heights stay absolute.
        assertEquals(
            RestAdaptation.SCALE,
            SignalPipeline(Exercise.SQUAT, squatCalibration, SignalSource.FACE).thresholds.adaptation,
        )
        assertTrue(SignalPipeline(Exercise.SQUAT, squatCalibration, SignalSource.POSE).thresholds.isRelative)
        assertEquals(
            RestAdaptation.SHIFT,
            SignalPipeline(Exercise.SQUAT, squatCalibration, SignalSource.BRIGHTNESS).thresholds.adaptation,
        )
        assertFalse(SignalPipeline(Exercise.PUSH_UP, pushUpCalibration, SignalSource.POSE).thresholds.isRelative)
        val faceY = SignalConfig.default.copy(squatFaceYWeight = 0.1f)
        assertFalse(SignalPipeline(Exercise.SQUAT, squatCalibration, SignalSource.FACE, faceY).thresholds.isRelative)
    }

    @Test
    fun squatsUseBrightnessByDefault() {
        assertEquals(SignalSource.BRIGHTNESS, SignalConfig.default.source(Exercise.SQUAT))
        assertEquals(SignalSource.FACE, SignalConfig.default.source(Exercise.PUSH_UP))
    }

    @Test
    fun medianRemovesSingleFrameOutliers() {
        val median = MedianFilter(window = 5)
        val output = listOf(0.25f, 0.26f, 0.04f, 0.25f, 0.27f, 0.26f).map { median.update(it) }
        assertTrue((output.minOrNull() ?: 0f) >= 0.25f) // the 0.04 frame never gets through
        assertEquals(0.26f, output.last())
    }

    @Test
    fun smoothingReducesNoise() {
        val noisy = SyntheticSignal(noise = 0.01f).reps(5)
        val thresholds = RepThresholds.from(min = 0.05f, max = 0.20f, direction = RepDirection.PEAK)
        assertEquals(5, CSVSignalReplay.countReps(noisy, Exercise.PUSH_UP, thresholds))
    }

    @Test
    fun faceSignalUsesBoundingBoxArea() {
        val extractor = SignalExtractor()
        val face = FaceObservation(Rect(0.1, 0.2, 0.4, 0.5), 0.9f)
        val observation = FrameObservation(0.0, face)
        val sample = extractor.extract(observation, Exercise.PUSH_UP, SignalSource.FACE)
        val value = assertNotNull(sample.value)
        assertTrue(abs(value - 0.2f) < 1e-6f)
        assertEquals(0.9f, sample.confidence)
        assertNull(extractor.extract(FrameObservation(0.0), Exercise.PUSH_UP, SignalSource.FACE).value)
    }

    @Test
    fun poseSignalPicksJointPerExercise() {
        val extractor = SignalExtractor()
        val pose = BodyPoseObservation(
            mapOf(
                PoseJoint.NOSE to PosePoint(0.5f, 0.9f, 0.8f),
                PoseJoint.LEFT_SHOULDER to PosePoint(0.4f, 0.7f, 0.7f),
                PoseJoint.RIGHT_SHOULDER to PosePoint(0.6f, 0.5f, 0.6f),
                PoseJoint.LEFT_HIP to PosePoint(0.4f, 0.3f, 0.5f),
            ),
        )
        val observation = FrameObservation(0.0, pose = pose)
        assertEquals(0.9f, extractor.extract(observation, Exercise.PUSH_UP, SignalSource.POSE).value)
        assertEquals(0.6f, extractor.extract(observation, Exercise.PULL_UP, SignalSource.POSE).value)
        assertEquals(0.6f, extractor.extract(observation, Exercise.PULL_UP, SignalSource.POSE).confidence)
        // Squats: shoulder width, with the weaker shoulder's confidence.
        val squat = extractor.extract(observation, Exercise.SQUAT, SignalSource.POSE)
        assertTrue(abs((squat.value ?: 0f) - 0.2828427f) < 1e-5f)
        assertEquals(0.6f, squat.confidence)
    }
}
