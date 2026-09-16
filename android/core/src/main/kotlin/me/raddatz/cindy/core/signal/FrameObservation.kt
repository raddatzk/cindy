package me.raddatz.cindy.core.signal

import me.raddatz.cindy.core.Rect
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Face bounding box in normalised coordinates (origin bottom-left, 0…1, as Vision reports it).
 * The Android detector has to flip its top-left y before building one.
 */
data class FaceObservation(val boundingBox: Rect, val confidence: Float) {
    /** Uses the standardised (absolute) size, like `CGRect.width`/`height`. */
    val area: Float get() = (abs(boundingBox.width) * abs(boundingBox.height)).toFloat()
    val centerY: Float get() = boundingBox.midY.toFloat()
    val centerX: Float get() = boundingBox.midX.toFloat()
}

enum class PoseJoint(val rawValue: String) {
    NOSE("nose"),
    LEFT_SHOULDER("leftShoulder"),
    RIGHT_SHOULDER("rightShoulder"),
    LEFT_HIP("leftHip"),
    RIGHT_HIP("rightHip"),
}

data class PosePoint(val x: Float, val y: Float, val confidence: Float)

/** A measured scalar with the confidence it carries (Swift `(value:, confidence:)` tuples). */
data class MeasuredValue(val value: Float, val confidence: Float)

/** Selected body-pose landmarks in normalised coordinates (origin bottom-left). */
data class BodyPoseObservation(
    val joints: Map<PoseJoint, PosePoint>,
    /**
     * Width and height of the oriented image relative to its longer side, so distances between
     * joints have the same unit on both axes and in every orientation.
     */
    val xScale: Float = 1f,
    val yScale: Float = 1f,
) {
    operator fun get(joint: PoseJoint): PosePoint? = joints[joint]

    /** Mean y of the given joints, weighted only by presence; confidence = min of the used joints. */
    fun meanY(jointsToUse: List<PoseJoint>): MeasuredValue? {
        val points = jointsToUse.mapNotNull { joints[it] }
        if (points.isEmpty()) return null
        var sum = 0f
        for (point in points) sum += point.y
        val confidence = points.minOf { it.confidence }
        return MeasuredValue(sum / points.size.toFloat(), confidence)
    }

    val noseY: Float? get() = joints[PoseJoint.NOSE]?.y

    /** Distance between the shoulders in normalised image units; grows as the athlete gets closer. */
    val shoulderWidth: Float? get() = shoulderWidthSample?.value

    /** Shoulder width with the confidence of the weaker shoulder. */
    val shoulderWidthSample: MeasuredValue?
        get() {
            val left = joints[PoseJoint.LEFT_SHOULDER] ?: return null
            val right = joints[PoseJoint.RIGHT_SHOULDER] ?: return null
            return MeasuredValue(
                hypot((left.x - right.x) * xScale, (left.y - right.y) * yScale),
                minOf(left.confidence, right.confidence),
            )
        }

    val shoulderY: Float? get() = meanY(listOf(PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER))?.value
    val hipY: Float? get() = meanY(listOf(PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP))?.value
    val overallConfidence: Float get() = joints.values.maxOfOrNull { it.confidence } ?: 0f
}

/**
 * Image brightness: the squat signal (a closer body covers more of the bright ceiling),
 * measured when the brightness source runs and always in the debug recorder.
 */
data class FrameMetrics(
    /** Mean luma of the whole frame (0…1). */
    val lumaMean: Float? = null,
    /** Mean luma of the central half (width and height) of the frame. */
    val lumaCenter: Float? = null,
)

/** Everything the vision stage extracted from one camera frame. */
data class FrameObservation(
    /** Presentation timestamp of the frame in seconds (monotonic). */
    val timestamp: Double,
    val face: FaceObservation? = null,
    val pose: BodyPoseObservation? = null,
    /**
     * EXIF orientation (1…8, `CGImagePropertyOrientation` raw value on iOS) that produced the
     * face hit; null when no face was found. Only logged, never used by the detection logic.
     */
    val orientation: Int? = null,
    /** Same for the body-pose hit. */
    val poseOrientation: Int? = null,
    val metrics: FrameMetrics? = null,
)
