package me.raddatz.cindy.camera

import me.raddatz.cindy.core.signal.BodyPoseObservation
import me.raddatz.cindy.core.signal.FaceObservation
import me.raddatz.cindy.core.signal.PoseJoint
import me.raddatz.cindy.core.signal.PosePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the MediaPipe → Vision mapping against the EXIF orientation definitions.
 *
 * Normalised points use a top-left origin with u to the right and v down unless noted.
 * The simulation models both platforms from the same physical scene:
 * - Android: the raw buffer rotated clockwise by `rotationDegrees` is the portrait picture P
 *   (unmirrored). MediaPipe sees the raw buffer rotated by the candidate's rotation.
 * - iOS: `.leftMirrored` shows the mirrored portrait picture, which fixes the raw iOS buffer;
 *   every other candidate applies its EXIF transform to that buffer.
 * The mapped detector result must equal what Vision would report for the same candidate.
 */
class OrientationMappingTests {
    private data class P(val u: Double, val v: Double)

    private fun rotateClockwise(p: P, degrees: Int): P = when ((degrees % 360 + 360) % 360) {
        0 -> p
        90 -> P(1 - p.v, p.u)
        180 -> P(1 - p.u, 1 - p.v)
        270 -> P(p.v, 1 - p.u)
        else -> error("unsupported rotation $degrees")
    }

    /** EXIF orientation: stored → displayed. */
    private fun exif(p: P, orientation: Int): P = when (orientation) {
        1 -> p
        2 -> P(1 - p.u, p.v)
        3 -> P(1 - p.u, 1 - p.v)
        4 -> P(p.u, 1 - p.v)
        5 -> P(p.v, p.u)
        6 -> P(1 - p.v, p.u)
        7 -> P(1 - p.v, 1 - p.u)
        8 -> P(p.v, 1 - p.u)
        else -> error("unsupported orientation $orientation")
    }

    private val sensorRotations = listOf(0, 90, 180, 270)
    private val rawWidth = 1280
    private val rawHeight = 720

    /** What Vision reports (bottom-left origin) for a raw Android point under a candidate. */
    private fun visionPoint(raw: P, cameraRotation: Int, candidate: OrientationCandidate): Pair<Double, Double> {
        val portrait = rotateClockwise(raw, cameraRotation)
        // Raw iOS buffer such that leftMirrored (transpose) shows mirror(portrait).
        val mirrored = P(1 - portrait.u, portrait.v)
        val rawIos = exif(mirrored, 5) // transpose is its own inverse
        val displayed = exif(rawIos, candidate.exifOrientation)
        return displayed.u to 1 - displayed.v
    }

    /** What the Android mapping reports for the same raw point. */
    private fun mappedPoint(raw: P, cameraRotation: Int, candidate: OrientationCandidate): Pair<Double, Double> {
        val rotation = candidate.rotationDegrees(cameraRotation)
        val (width, height) = OrientationMapping.orientedSize(rawWidth, rawHeight, rotation)
        val upright = rotateClockwise(raw, rotation)
        val (x, y) = OrientationMapping.point((upright.u * width).toFloat(), (upright.v * height).toFloat(), width, height)
        return x.toDouble() to y.toDouble()
    }

    private val samplePoints = listOf(P(0.1, 0.2), P(0.7, 0.3), P(0.5, 0.5), P(0.25, 0.9), P(0.0, 1.0))

    @Test
    fun candidateRotationsReproduceVisionOrientations() {
        for (rotation in sensorRotations) {
            for (candidate in OrientationCandidate.entries) {
                for (point in samplePoints) {
                    val expected = visionPoint(point, rotation, candidate)
                    val actual = mappedPoint(point, rotation, candidate)
                    assertEquals(expected.first, actual.first, 1e-6, "x for $candidate at $rotation° $point")
                    assertEquals(expected.second, actual.second, 1e-6, "y for $candidate at $rotation° $point")
                }
            }
        }
    }

    @Test
    fun candidateOrderAndRawValuesMatchIOS() {
        assertEquals(
            listOf(5, 7, 2, 4), // leftMirrored, rightMirrored, upMirrored, downMirrored
            OrientationSearch.CANDIDATES.map { it.exifOrientation },
        )
        assertEquals(270, OrientationCandidate.LEFT_MIRRORED.rotationDegrees(270))
        assertEquals(90, OrientationCandidate.RIGHT_MIRRORED.rotationDegrees(270))
        assertEquals(180, OrientationCandidate.UP_MIRRORED.rotationDegrees(270))
        assertEquals(0, OrientationCandidate.DOWN_MIRRORED.rotationDegrees(270))
    }

    @Test
    fun faceBoxMatchesVisionForEveryRotation() {
        val rawCorners = listOf(P(0.30, 0.20), P(0.55, 0.20), P(0.30, 0.65), P(0.55, 0.65))
        for (rotation in sensorRotations) {
            for (candidate in OrientationCandidate.entries) {
                val mlRotation = candidate.rotationDegrees(rotation)
                val (width, height) = OrientationMapping.orientedSize(rawWidth, rawHeight, mlRotation)
                val upright = rawCorners.map { rotateClockwise(it, mlRotation) }
                val box = OrientationMapping.faceBox(
                    left = (upright.minOf { it.u } * width).toFloat(),
                    top = (upright.minOf { it.v } * height).toFloat(),
                    right = (upright.maxOf { it.u } * width).toFloat(),
                    bottom = (upright.maxOf { it.v } * height).toFloat(),
                    orientedWidth = width,
                    orientedHeight = height,
                )
                val vision = rawCorners.map { visionPoint(it, rotation, candidate) }
                val message = "$candidate at $rotation°"
                assertEquals(vision.minOf { it.first }, box.minX, 1e-5, message)
                assertEquals(vision.maxOf { it.first }, box.maxX, 1e-5, message)
                assertEquals(vision.minOf { it.second }, box.minY, 1e-5, message)
                assertEquals(vision.maxOf { it.second }, box.maxY, 1e-5, message)
            }
        }
    }

    @Test
    fun faceBoxIsMirroredAndFlipped() {
        // Portrait 720×1280: a face in the upper left of the unmirrored upright picture.
        val box = OrientationMapping.faceBox(left = 100f, top = 200f, right = 300f, bottom = 500f, orientedWidth = 720, orientedHeight = 1280)
        assertEquals(1 - 300.0 / 720, box.x, 1e-9)
        assertEquals(1 - 500.0 / 1280, box.y, 1e-9)
        assertEquals(200.0 / 720, box.width, 1e-9)
        assertEquals(300.0 / 1280, box.height, 1e-9)
        // Upper left unmirrored → upper right in Vision's mirrored, bottom-left frame.
        val face = FaceObservation(box, 1f)
        assertTrue(face.centerX > 0.5f)
        assertTrue(face.centerY > 0.5f)
    }

    @Test
    fun movingTowardsTheHeadRaisesYAndAGrowingFaceGrowsTheArea() {
        for (rotation in sensorRotations) {
            for (candidate in OrientationCandidate.entries) {
                val mlRotation = candidate.rotationDegrees(rotation)
                val (width, height) = OrientationMapping.orientedSize(rawWidth, rawHeight, mlRotation)
                val low = OrientationMapping.faceBox(300f, 600f, 400f, 700f, width, height)
                val high = OrientationMapping.faceBox(300f, 300f, 400f, 400f, width, height)
                val big = OrientationMapping.faceBox(250f, 550f, 450f, 750f, width, height)
                assertTrue(FaceObservation(high, 1f).centerY > FaceObservation(low, 1f).centerY)
                assertTrue(FaceObservation(big, 1f).area > FaceObservation(low, 1f).area)
            }
        }
    }

    @Test
    fun shouldersAreMirroredLikeVision() {
        // An athlete facing the camera, upright in a 720×1280 portrait frame: the detector's left shoulder
        // (their left) appears on the right of the unmirrored picture.
        val width = 720
        val height = 1280
        val mlLeft = OrientationMapping.point(500f, 400f, width, height)
        val mlRight = OrientationMapping.point(220f, 420f, width, height)
        assertEquals(OrientationMapping.Side.RIGHT, OrientationMapping.detectorSide(PoseJoint.LEFT_SHOULDER))
        assertEquals(OrientationMapping.Side.LEFT, OrientationMapping.detectorSide(PoseJoint.RIGHT_SHOULDER))
        assertEquals(OrientationMapping.Side.RIGHT, OrientationMapping.detectorSide(PoseJoint.LEFT_HIP))
        assertEquals(OrientationMapping.Side.NONE, OrientationMapping.detectorSide(PoseJoint.NOSE))
        // BlazePose indices: 11/12 left/right shoulder, 23/24 left/right hip, read mirrored.
        assertEquals(12, OrientationMapping.poseLandmarkIndex(PoseJoint.LEFT_SHOULDER))
        assertEquals(11, OrientationMapping.poseLandmarkIndex(PoseJoint.RIGHT_SHOULDER))
        assertEquals(24, OrientationMapping.poseLandmarkIndex(PoseJoint.LEFT_HIP))
        assertEquals(23, OrientationMapping.poseLandmarkIndex(PoseJoint.RIGHT_HIP))
        assertEquals(0, OrientationMapping.poseLandmarkIndex(PoseJoint.NOSE))
        assertEquals(OrientationMapping.point(360f, 320f, 720, 1280), OrientationMapping.normalizedPoint(0.5f, 0.25f))

        val (xScale, yScale) = OrientationMapping.poseScales(width, height)
        val pose = BodyPoseObservation(
            joints = mapOf(
                PoseJoint.LEFT_SHOULDER to PosePoint(mlRight.first, mlRight.second, 0.9f),
                PoseJoint.RIGHT_SHOULDER to PosePoint(mlLeft.first, mlLeft.second, 0.8f),
            ),
            xScale = xScale,
            yScale = yScale,
        )
        // Vision sees the mirrored picture as a normal photo: the left shoulder is on the image's right.
        assertTrue(pose[PoseJoint.LEFT_SHOULDER]!!.x > pose[PoseJoint.RIGHT_SHOULDER]!!.x)
        assertEquals(720f / 1280f, xScale)
        assertEquals(1f, yScale)
        val expectedWidth = kotlin.math.hypot(280.0 / 1280, 20.0 / 1280)
        assertEquals(expectedWidth, pose.shoulderWidth!!.toDouble(), 1e-5)
        assertEquals(0.8f, pose.shoulderWidthSample!!.confidence)
        // Shoulder height is measured from the bottom.
        assertEquals(1 - 410.0 / 1280, pose.shoulderY!!.toDouble(), 1e-5)
    }

    @Test
    fun orientedSizeSwapsForQuarterTurns() {
        assertEquals(1280 to 720, OrientationMapping.orientedSize(1280, 720, 0))
        assertEquals(720 to 1280, OrientationMapping.orientedSize(1280, 720, 90))
        assertEquals(1280 to 720, OrientationMapping.orientedSize(1280, 720, 180))
        assertEquals(720 to 1280, OrientationMapping.orientedSize(1280, 720, 270))
    }

    @Test
    fun searchKeepsTheLastHitAndProbesOneAlternativePerFrame() {
        val search = OrientationSearch()
        val tried = mutableListOf<OrientationCandidate>()
        // Only upMirrored finds something.
        fun frame(): OrientationSearch.Hit<Int>? = search.find { candidate ->
            tried += candidate
            if (candidate == OrientationCandidate.UP_MIRRORED) 1 else null
        }

        assertEquals(null, frame())
        assertEquals(listOf(OrientationCandidate.LEFT_MIRRORED, OrientationCandidate.RIGHT_MIRRORED), tried)
        tried.clear()
        val hit = frame()
        assertEquals(OrientationCandidate.UP_MIRRORED, hit?.orientation)
        assertEquals(listOf(OrientationCandidate.LEFT_MIRRORED, OrientationCandidate.UP_MIRRORED), tried)
        assertEquals(2, search.preferredIndex)
        tried.clear()
        frame()
        assertEquals(listOf(OrientationCandidate.UP_MIRRORED), tried)
    }

    @Test
    fun fallbackSkipsThePreferredCandidateAndWrapsAround() {
        val search = OrientationSearch()
        val probes = mutableListOf<OrientationCandidate>()
        repeat(4) {
            search.find<Int> { candidate ->
                if (candidate != OrientationCandidate.LEFT_MIRRORED) probes += candidate
                null
            }
        }
        assertEquals(
            listOf(
                OrientationCandidate.RIGHT_MIRRORED,
                OrientationCandidate.UP_MIRRORED,
                OrientationCandidate.DOWN_MIRRORED,
                OrientationCandidate.RIGHT_MIRRORED,
            ),
            probes,
        )
    }
}
