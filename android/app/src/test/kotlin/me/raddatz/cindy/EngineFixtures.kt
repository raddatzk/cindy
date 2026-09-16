package me.raddatz.cindy

import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.Rect
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.calibration.CalibrationProfile
import me.raddatz.cindy.core.calibration.ExerciseCalibration
import me.raddatz.cindy.core.signal.FaceObservation
import me.raddatz.cindy.core.signal.FrameMetrics
import me.raddatz.cindy.core.signal.FrameObservation
import me.raddatz.cindy.core.signal.PipelineOutput
import me.raddatz.cindy.core.signal.RepDetectorEvent
import me.raddatz.cindy.core.signal.RepDirection
import me.raddatz.cindy.core.signal.RepPhase
import java.time.Instant
import kotlin.math.sqrt

object EngineFixtures {
    val epoch: Instant = Instant.parse("2026-09-16T17:00:00Z")

    /** A face-area calibration: rest 0.04, rep peak 0.10. */
    fun faceCalibration(direction: RepDirection = RepDirection.PEAK) = ExerciseCalibration(
        source = SignalSource.FACE,
        minValue = 0.04f,
        maxValue = 0.10f,
        baseline = 0.04f,
        low = 0.055f,
        high = 0.085f,
        direction = direction,
        repDuration = 1.0,
        calibratedAt = epoch,
    )

    fun brightnessCalibration() = ExerciseCalibration(
        source = SignalSource.BRIGHTNESS,
        minValue = 0.44f,
        maxValue = 0.5f,
        baseline = 0.5f,
        low = 0.458f,
        high = 0.482f,
        direction = RepDirection.TROUGH,
        repDuration = 1.2,
        calibratedAt = epoch,
    )

    val completeProfile: CalibrationProfile = CalibrationProfile(createdAt = epoch)
        .withCalibration(faceCalibration(RepDirection.TROUGH), Exercise.PULL_UP)
        .withCalibration(faceCalibration(), Exercise.PUSH_UP)
        .withCalibration(brightnessCalibration(), Exercise.SQUAT)
        .withCalibration(faceCalibration(), Exercise.PLANK)

    fun output(
        exercise: Exercise,
        event: RepDetectorEvent? = null,
        armed: Boolean = true,
        heldSeconds: Double? = null,
        timestamp: Double = 0.0,
    ) = PipelineOutput(
        timestamp = timestamp,
        exercise = exercise,
        source = SignalSource.FACE,
        raw = 0.05f,
        smoothed = 0.05f,
        confidence = 1f,
        event = event,
        phase = RepPhase.REST,
        isArmed = armed,
        repCount = 0,
        heldSeconds = heldSeconds,
    )

    fun face(area: Float, timestamp: Double): FrameObservation {
        val side = sqrt(area.toDouble())
        return FrameObservation(timestamp = timestamp, face = FaceObservation(Rect(0.4, 0.4, side, side), 1f))
    }

    /**
     * Face-area frames at 30 fps: [restFrames] at rest, then per rep a rise to 0.10, a hold, a fall
     * and a rest. Starts at [start] seconds.
     */
    fun pushUpFrames(reps: Int, start: Double = 0.0, restFrames: Int = 15): List<FrameObservation> {
        val values = ArrayList<Float>()
        repeat(restFrames) { values += 0.04f }
        repeat(reps) {
            for (i in 1..10) values += 0.04f + 0.006f * i
            repeat(10) { values += 0.10f }
            for (i in 1..10) values += 0.10f - 0.006f * i
            repeat(20) { values += 0.04f }
        }
        return values.mapIndexed { index, value -> face(value, start + index / 30.0) }
    }

    /** Brightness frames at 30 fps with a dip from 0.5 to 0.44 and no face or pose. */
    fun squatBrightnessFrames(start: Double = 0.0): List<FrameObservation> {
        val values = ArrayList<Float>()
        repeat(20) { values += 0.5f }
        for (i in 1..12) values += 0.5f - 0.005f * i
        repeat(8) { values += 0.44f }
        for (i in 1..12) values += 0.44f + 0.005f * i
        repeat(20) { values += 0.5f }
        return values.mapIndexed { index, value ->
            FrameObservation(timestamp = start + index / 30.0, metrics = FrameMetrics(lumaMean = value, lumaCenter = value))
        }
    }
}
