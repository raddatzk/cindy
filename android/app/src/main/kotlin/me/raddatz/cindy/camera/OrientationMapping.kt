package me.raddatz.cindy.camera

import me.raddatz.cindy.core.Rect
import me.raddatz.cindy.core.signal.PoseJoint

/**
 * One of the four upright candidates the orientation search tries, named after the Vision
 * orientation it reproduces.
 *
 * The phone lies flat, so the athlete's "up" is unknown relative to the sensor, and both detectors
 * prefer upright people. iOS therefore hands Vision the front-camera buffer with
 * `.leftMirrored/.rightMirrored/.upMirrored/.downMirrored` in turn. Android has no such buffer
 * orientation, so each candidate becomes a clockwise rotation of the pixels handed to MediaPipe
 * ([RgbFrames]) plus a horizontal flip of the results (see [OrientationMapping]).
 *
 * Why these offsets: with the activity locked to portrait, CameraX's `rotationDegrees` is the
 * rotation that makes the raw buffer upright for a phone held in portrait, which is what
 * `.leftMirrored` achieves on iOS (Apple's own samples use it for the portrait front camera).
 * Every EXIF `…Mirrored` orientation is "rotate clockwise, then flip horizontally":
 * upMirrored (2) = 0°, leftMirrored (5) = 90°, downMirrored (4) = 180°, rightMirrored (7) = 270°.
 * Relative to leftMirrored that is +0° (5), +90° (4), +180° (7) and +270° (2) on top of
 * `rotationDegrees`. `OrientationMappingTests` checks the table against the EXIF definitions.
 *
 * The order is iOS's `OrientationSearch.orientations`.
 */
enum class OrientationCandidate(
    /** Clockwise degrees added to the camera's upright rotation. */
    val rotationOffset: Int,
    /** `CGImagePropertyOrientation` raw value iOS logs for the same candidate. */
    val exifOrientation: Int,
) {
    LEFT_MIRRORED(0, 5),
    RIGHT_MIRRORED(180, 7),
    UP_MIRRORED(270, 2),
    DOWN_MIRRORED(90, 4);

    /** Clockwise rotation of the pixels for a buffer whose upright rotation is [cameraRotationDegrees]. */
    fun rotationDegrees(cameraRotationDegrees: Int): Int =
        ((cameraRotationDegrees + rotationOffset) % 360 + 360) % 360
}

/**
 * Remembers which candidate last produced a detection and probes one alternative per frame after a
 * miss (round robin over the remaining candidates). Port of iOS `OrientationSearch`; face and pose
 * keep separate instances so a face found sideways does not tilt the pose search.
 */
class OrientationSearch {
    data class Hit<T>(val result: T, val orientation: OrientationCandidate)

    @PublishedApi
    internal var preferred: Int = 0

    /** Index into [CANDIDATES] of the candidate tried first. */
    val preferredIndex: Int get() = preferred

    private var fallbackCursor = 1

    inline fun <T> find(detect: (OrientationCandidate) -> T?): Hit<T>? {
        val candidates = CANDIDATES
        val first = candidates[preferred]
        val result = detect(first)
        if (result != null) return Hit(result, first)
        val alternativeIndex = nextFallbackIndex()
        val alternative = candidates[alternativeIndex]
        val alternativeResult = detect(alternative) ?: return null
        preferred = alternativeIndex
        return Hit(alternativeResult, alternative)
    }

    @PublishedApi
    internal fun nextFallbackIndex(): Int {
        val count = CANDIDATES.size
        var index = fallbackCursor % count
        if (index == preferred) index = (index + 1) % count
        fallbackCursor = index + 1
        return index
    }

    companion object {
        val CANDIDATES: List<OrientationCandidate> = OrientationCandidate.entries.toList()
    }
}

/**
 * Turns MediaPipe results into the coordinates core expects, which are Vision's.
 *
 * MediaPipe reports on the rotated (upright) image it was given, origin top-left, unmirrored — the
 * scene as someone standing behind the phone would see it. Vision reports on the mirrored upright image,
 * normalised to 0…1, origin bottom-left. So:
 * - x: normalise, then mirror (`x' = 1 − x`); a box's left edge becomes `1 − right`.
 * - y: normalise, then flip to a bottom-left origin (`y' = 1 − y`); a box's bottom edge becomes
 *   `1 − bottom`.
 * - joints: in the mirrored picture the athlete's right shoulder sits where a left shoulder would,
 *   and Vision names it accordingly, so MediaPipe's left landmarks become core's RIGHT_* and vice
 *   versa. Shoulder width and mean heights do not care, but logs and any future side-specific
 *   signal stay comparable with iOS.
 *
 * Face area, shoulder width and every y-based signal therefore move in the same direction as on
 * iOS for the same physical movement.
 */
object OrientationMapping {
    /** Width and height of the image after rotating it clockwise by [rotationDegrees]. */
    fun orientedSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (rotationDegrees % 180 != 0) height to width else width to height

    /** A face box in upright detector pixels → Vision's normalised, mirrored, bottom-left box. */
    fun faceBox(left: Float, top: Float, right: Float, bottom: Float, orientedWidth: Int, orientedHeight: Int): Rect {
        val w = orientedWidth.coerceAtLeast(1).toDouble()
        val h = orientedHeight.coerceAtLeast(1).toDouble()
        val minX = 1.0 - maxOf(left, right) / w
        val minY = 1.0 - maxOf(top, bottom) / h
        return Rect(
            x = minX,
            y = minY,
            width = kotlin.math.abs(right - left) / w,
            height = kotlin.math.abs(bottom - top) / h,
        )
    }

    /** A landmark in upright detector pixels → Vision's normalised, mirrored, bottom-left point. */
    fun point(x: Float, y: Float, orientedWidth: Int, orientedHeight: Int): Pair<Float, Float> =
        (1f - x / orientedWidth.coerceAtLeast(1)) to (1f - y / orientedHeight.coerceAtLeast(1))

    /**
     * `BodyPoseObservation.xScale/yScale`: oriented width and height relative to the longer side,
     * so joint distances have the same unit on both axes.
     */
    fun poseScales(orientedWidth: Int, orientedHeight: Int): Pair<Float, Float> {
        val longer = maxOf(orientedWidth, orientedHeight, 1).toFloat()
        return orientedWidth / longer to orientedHeight / longer
    }

    /** A landmark normalised to the upright detector image (0…1, top-left) → Vision's point. */
    fun normalizedPoint(x: Float, y: Float): Pair<Float, Float> = (1f - x) to (1f - y)

    /** Which detector side a core joint is read from (mirrored, see the class comment). */
    enum class Side { NONE, LEFT, RIGHT }

    /** The detector's landmark side that feeds [joint]. */
    fun detectorSide(joint: PoseJoint): Side = when (joint) {
        PoseJoint.NOSE -> Side.NONE
        PoseJoint.LEFT_SHOULDER, PoseJoint.LEFT_HIP -> Side.RIGHT
        PoseJoint.RIGHT_SHOULDER, PoseJoint.RIGHT_HIP -> Side.LEFT
    }

    /**
     * Index into MediaPipe's 33 BlazePose landmarks for a core joint: 0 nose, 11/12 left/right
     * shoulder, 23/24 left/right hip (the athlete's own sides), mirrored like Vision.
     */
    fun poseLandmarkIndex(joint: PoseJoint): Int {
        val left = detectorSide(joint) == Side.LEFT
        return when (joint) {
            PoseJoint.NOSE -> 0
            PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER -> if (left) 11 else 12
            PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP -> if (left) 23 else 24
        }
    }
}
