package me.raddatz.cindy.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which measurement feeds the rep detector. */
@Serializable
enum class SignalSource(val rawValue: String) {
    /** Face bounding-box based signal (push-ups, pull-ups, plank). Display name "Face". */
    @SerialName("face")
    FACE("face"),

    /** Body-pose landmark based signal. Display name "Body Pose". */
    @SerialName("pose")
    POSE("pose"),

    /**
     * Mean image brightness (squats): the body covers more of the bright ceiling the lower
     * it gets, whichever way the athlete looks. Face and pose run alongside as a plausibility
     * check. Display name "Brightness".
     */
    @SerialName("brightness")
    BRIGHTNESS("brightness");

    val id: String get() = rawValue
}

/**
 * All tunables of the detection chain in one place. Change the defaults here; the values are
 * deliberately plain constants so they can be tweaked after analysing debug CSV recordings.
 *
 * Durations are seconds (`TimeInterval` on iOS).
 */
@Serializable
data class SignalConfig(
    // Smoothing & thresholds

    /** EMA smoothing factor (1 = no smoothing). */
    val emaAlpha: Float = 0.3f,
    /**
     * Margin used to derive the Schmitt-trigger thresholds from the calibrated extremes:
     * low = min + margin·range, high = max − margin·range.
     */
    val thresholdMargin: Float = 0.25f,
    /**
     * Relative thresholds only: the rest level measured when arming may differ from the
     * calibrated baseline by at most this factor (either way). Keeps the counter from arming
     * while the athlete stands right at the phone or holds the far position.
     */
    val restBaselineTolerance: Float = 2.5f,
    /**
     * Shifted thresholds (brightness) only: the rest level measured when arming may differ
     * from the calibrated baseline by at most this much (brightness 0…1).
     */
    val restShiftTolerance: Float = 0.1f,
    /**
     * Scaled thresholds only: while armed and at rest, the rest level follows lower values with
     * this EMA factor (never higher ones, those may be a rep starting). Corrects a rest measured
     * while the athlete was still walking away from the phone. Brightness gains nothing from it
     * in the recordings, so shifted thresholds do not track.
     */
    val restTrackingAlpha: Float = 0.03f,
    /**
     * Rest-anchored thresholds (brightness): a rep leaves rest after this fraction of the
     * calibrated swing and peaks after [restAnchoredPeak].
     */
    val restAnchoredLeave: Float = 0.3f,
    val restAnchoredPeak: Float = 0.7f,

    /**
     * Brightness reps only count when a body signal moved in the same cycle: the face area
     * grew by this factor, the shoulder width by [evidenceShoulderRatio], or face or pose
     * vanished for [evidenceLostFrames] frames (looking ahead at the bottom of a squat).
     */
    val evidenceFaceAreaRatio: Float = 1.5f,
    val evidenceShoulderRatio: Float = 1.3f,
    val evidenceLostFrames: Int = 5,
    /** Pose frames below this shoulder confidence do not count as a seen body. */
    val evidencePoseMinConfidence: Float = 0.2f,

    /** Median window applied to body-pose signals before the EMA (single-frame outliers). */
    val poseMedianWindow: Int = 5,

    // Rep plausibility & debounce

    val minRepDuration: Double = 0.5,
    /**
     * Longer cycles are rejected. With relative thresholds a cycle still open after this long
     * disarms the detector, so a changed standing position gets a fresh rest level.
     */
    val maxRepDuration: Double = 5.0,
    /** Consecutive confident frames in the rest band before the counter is armed. */
    val stableFrames: Int = 10,
    /** Frames with lower confidence are ignored (state is held, not reset). */
    val minConfidence: Float = 0.5f,
    /** After this long without a confident frame the detector drops its cycle state and has to be re-armed. */
    val lostTimeout: Double = 2.0,

    // Signal composition

    /**
     * Weight of the normalised face centre-y added to the face area for squats. 0 = area only.
     * The sign depends on where the athlete stands relative to the phone, so it is left at 0
     * until CSV recordings say otherwise.
     */
    val squatFaceYWeight: Float = 0f,
    /** Same for pull-ups. */
    val pullUpFaceYWeight: Float = 0f,

    /**
     * Signal source per exercise. Squats use the brightness: from the floor the face is only
     * found while the athlete looks down, and body pose drops out at the bottom of the squat
     * when looking ahead (CSV recordings 2026-09-14).
     */
    val pullUpSource: SignalSource = SignalSource.FACE,
    val pushUpSource: SignalSource = SignalSource.FACE,
    val squatSource: SignalSource = SignalSource.BRIGHTNESS,

    // Calibration

    val calibrationCountdownSeconds: Int = 3,
    val calibrationTimeout: Double = 15.0,
    /** Length of the initial window used to measure the rest baseline. */
    val calibrationBaselineDuration: Double = 0.5,
    /** Minimum number of confident samples the baseline window must contain. */
    val calibrationBaselineMinSamples: Int = 5,
    /** Face-area signals must swing by at least this fraction of the baseline. */
    val calibrationMinRelativeExcursion: Float = 0.3f,
    /** Pose signals (normalised image coordinates) must swing by at least this much. */
    val calibrationMinAbsoluteExcursion: Float = 0.05f,
    /** Brightness (0…1) must dip by at least this much; the recorded squats dipped 0.03–0.09. */
    val calibrationMinBrightnessExcursion: Float = 0.015f,
    /** Seconds the athlete holds the plank during calibration. */
    val calibrationHoldDuration: Double = 3.0,
    /** Half-width of the plank band relative to the mean signal (± 25 %). */
    val calibrationHoldBandMargin: Float = 0.25f,

    // Workout

    val workoutCountdownSeconds: Int = 5,

    // Camera

    /** Seconds of auto exposure after the session starts before exposure is locked. */
    val exposureSettleDuration: Double = 2.0,
    val targetFrameRate: Double = 30.0,
) {
    fun source(exercise: Exercise): SignalSource = when (exercise) {
        Exercise.PULL_UP -> pullUpSource
        Exercise.PUSH_UP -> pushUpSource
        Exercise.SQUAT -> squatSource
        Exercise.PLANK -> pushUpSource // same geometry as the push-up top position
    }

    fun faceYWeight(exercise: Exercise): Float = when (exercise) {
        Exercise.PULL_UP -> pullUpFaceYWeight
        Exercise.PUSH_UP -> 0f
        Exercise.SQUAT -> squatFaceYWeight
        Exercise.PLANK -> 0f
    }

    companion object {
        val default: SignalConfig = SignalConfig()
    }
}
