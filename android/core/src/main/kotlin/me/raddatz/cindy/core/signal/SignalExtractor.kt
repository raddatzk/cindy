package me.raddatz.cindy.core.signal

import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.SignalSource

/** One scalar per frame for the current exercise, plus how much to trust it. */
data class SignalSample(
    /** null when there is no usable measurement in this frame. */
    val value: Float?,
    val confidence: Float,
    val source: SignalSource,
) {
    companion object {
        fun missing(source: SignalSource): SignalSample = SignalSample(null, 0f, source)
    }
}

/** Turns a [FrameObservation] into the 1-D signal of the expected exercise. */
class SignalExtractor(val config: SignalConfig = SignalConfig.default) {

    fun extract(observation: FrameObservation, exercise: Exercise, source: SignalSource): SignalSample =
        when (source) {
            SignalSource.FACE -> faceSignal(observation, exercise)
            SignalSource.POSE -> poseSignal(observation, exercise)
            SignalSource.BRIGHTNESS -> brightnessSignal(observation)
        }

    /** Mean image brightness; always confident when measured. */
    fun brightnessSignal(observation: FrameObservation): SignalSample {
        val luma = observation.metrics?.lumaMean ?: return SignalSample.missing(SignalSource.BRIGHTNESS)
        return SignalSample(luma, 1f, SignalSource.BRIGHTNESS)
    }

    /** Face bounding-box area (plus an optional weighted centre-y term). */
    fun faceSignal(observation: FrameObservation, exercise: Exercise): SignalSample {
        val face = observation.face ?: return SignalSample.missing(SignalSource.FACE)
        val weight = config.faceYWeight(exercise)
        val value = face.area + weight * (face.centerY - 0.5f)
        return SignalSample(value, face.confidence, SignalSource.FACE)
    }

    /**
     * Body-pose signal: shoulder width for squats (the athlete comes closer to the floor camera),
     * landmark height otherwise (nose for push-ups, shoulders for pull-ups).
     */
    fun poseSignal(observation: FrameObservation, exercise: Exercise): SignalSample {
        val pose = observation.pose ?: return SignalSample.missing(SignalSource.POSE)
        val result = when (exercise) {
            Exercise.PUSH_UP, Exercise.PLANK -> pose.meanY(listOf(PoseJoint.NOSE))
            Exercise.SQUAT -> pose.shoulderWidthSample
            Exercise.PULL_UP -> pose.meanY(listOf(PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER))
        } ?: return SignalSample.missing(SignalSource.POSE)
        return SignalSample(result.value, result.confidence, SignalSource.POSE)
    }

    /**
     * How calibrated thresholds follow the rest level: sizes in the image scale with the distance
     * to the phone, brightness shifts. Image heights and a face-y mix stay absolute (null).
     */
    fun restAdaptation(exercise: Exercise, source: SignalSource): RestAdaptation? = when (source) {
        SignalSource.FACE -> if (config.faceYWeight(exercise) == 0f) RestAdaptation.SCALE else null
        SignalSource.POSE -> if (exercise == Exercise.SQUAT) RestAdaptation.SCALE else null
        SignalSource.BRIGHTNESS -> RestAdaptation.SHIFT
    }
}
