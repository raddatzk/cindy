package me.raddatz.cindy.camera

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import me.raddatz.cindy.core.camera.FrameMetricsCalculator
import me.raddatz.cindy.core.signal.BodyPoseObservation
import me.raddatz.cindy.core.signal.FaceObservation
import me.raddatz.cindy.core.signal.FrameObservation
import me.raddatz.cindy.core.signal.PoseJoint
import me.raddatz.cindy.core.signal.PosePoint
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/**
 * Runs MediaPipe face detection and pose landmarking on a frame and returns a [FrameObservation]
 * (iOS: `VisionProcessor` with Vision's face-rectangle and body-pose requests). Both models are
 * bundled assets on the CPU delegate, so detection needs neither the network nor Google Play
 * services.
 *
 * The phone lies flat, so each detector keeps its own [OrientationSearch]: the candidate that last
 * worked first, one alternative per frame after a miss. The pixels are rotated for the candidate
 * by [RgbFrames] (half resolution, RGB). Pose runs on its own thread in parallel with the face —
 * the pose model is the expensive one, and the camera drops frames (keep-only-latest) for as long
 * as a frame is being processed.
 *
 * Per-frame cost on the CPU: one 640×360 RGB conversion per orientation in use (usually one,
 * two while a search probes), BlazeFace full range (192×192 input) on the frame thread, and
 * while pose is on, the lite pose landmarker on the pose thread.
 *
 * Video mode wants strictly increasing timestamps per detector instance, and the pose landmarker
 * tracks the body from one frame to the next. So:
 * - the face detector (no tracking) is one instance; a probe in the same frame gets the next
 *   millisecond;
 * - the pose landmarker is one instance per orientation candidate, created when the search first
 *   needs it, so its tracking only ever sees frames in its own orientation.
 *
 * Not thread-safe: call [process] from the frame thread only. Detectors are created lazily and
 * released by [close]; a later frame creates them again.
 */
class VisionProcessor(
    context: Context,
    /** Whether to run face detection (the signal for push-ups, pull-ups and plank). */
    var detectFace: Boolean = true,
    /** Whether to run the (expensive) pose landmarker (evidence for squats, pose signals). */
    var detectBodyPose: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val frames = RgbFrames()
    private val faceSearch = OrientationSearch()
    private val poseSearch = OrientationSearch()

    private var faceDetector: FaceDetector? = null
    private var faceFailed = false
    private var lastFaceTimestamp = Long.MIN_VALUE

    // Confined to the pose thread (and to the frame thread while it waits for the pose result).
    private val poseLandmarkers = arrayOfNulls<PoseLandmarker>(OrientationCandidate.entries.size)
    private val lastPoseTimestamps = LongArray(OrientationCandidate.entries.size) { Long.MIN_VALUE }
    private var poseFailed = false

    private val poseExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "me.raddatz.cindy.pose").apply { isDaemon = true }
    }

    /**
     * @param cameraRotationDegrees CameraX's `rotationDegrees`: makes the buffer upright for a
     *   portrait phone.
     * @param timestampNanos the frame's monotonic timestamp.
     * @param measureMetrics also measure the image brightness (from the full-resolution Y plane).
     */
    fun process(
        width: Int,
        height: Int,
        y: YuvPlane,
        u: YuvPlane,
        v: YuvPlane,
        cameraRotationDegrees: Int,
        timestampNanos: Long,
        measureMetrics: Boolean,
    ): FrameObservation {
        frames.begin(width, height, y, u, v)
        try {
            val millis = timestampNanos / 1_000_000
            val pose: Future<OrientationSearch.Hit<BodyPoseObservation>?>? = if (detectBodyPose) {
                try {
                    poseExecutor.submit<OrientationSearch.Hit<BodyPoseObservation>?> {
                        poseSearch.find { detectPose(it, cameraRotationDegrees, millis) }
                    }
                } catch (_: RejectedExecutionException) {
                    null
                }
            } else {
                null
            }
            val metrics = if (measureMetrics) {
                FrameMetricsCalculator.measure(y.buffer, width, height, y.rowStride)
            } else {
                null
            }
            val faceHit = if (detectFace) faceSearch.find { detectFace(it, cameraRotationDegrees, millis) } else null
            val poseHit = try {
                pose?.get()
            } catch (_: ExecutionException) {
                null
            }
            return FrameObservation(
                timestamp = timestampNanos / 1e9,
                face = faceHit?.result,
                pose = poseHit?.result,
                orientation = faceHit?.orientation?.exifOrientation,
                poseOrientation = poseHit?.orientation?.exifOrientation,
                metrics = metrics,
            )
        } finally {
            frames.end()
        }
    }

    /** Releases the detectors; the next frame creates them again. Call on the frame thread. */
    fun close() {
        faceDetector?.close()
        faceDetector = null
        faceFailed = false
        val closed = try {
            poseExecutor.submit { closePoseLandmarkers() }
        } catch (_: RejectedExecutionException) {
            null
        }
        if (closed == null) closePoseLandmarkers() else runCatching { closed.get() }
    }

    /** Closes the detectors and ends the pose thread for good. */
    fun release() {
        close()
        poseExecutor.shutdown()
    }

    // Face

    private fun detectFace(candidate: OrientationCandidate, cameraRotation: Int, millis: Long): FaceObservation? {
        val detector = faceDetector() ?: return null
        val image = frames.rgb(candidate.rotationDegrees(cameraRotation))
        val timestamp = maxOf(millis, lastFaceTimestamp + 1).also { lastFaceTimestamp = it }
        val result = try {
            detector.detectForVideo(image.mpImage(), timestamp)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Face detection failed", e)
            return null
        }
        // Largest face wins: the athlete is the closest person to the camera.
        val best = result.detections().maxByOrNull { it.boundingBox().width() * it.boundingBox().height() } ?: return null
        val box = best.boundingBox()
        // Pixels of the input image; a box in relative units (no side above ~1) is scaled up first.
        val relative = maxOf(box.right, box.bottom) <= 1.5f
        val scaleX = if (relative) image.width.toFloat() else 1f
        val scaleY = if (relative) image.height.toFloat() else 1f
        return FaceObservation(
            boundingBox = OrientationMapping.faceBox(
                box.left * scaleX, box.top * scaleY, box.right * scaleX, box.bottom * scaleY,
                image.width, image.height,
            ),
            confidence = best.categories().maxOfOrNull { it.score() } ?: 0f,
        )
    }

    private fun faceDetector(): FaceDetector? {
        faceDetector?.let { return it }
        if (faceFailed) return null
        return try {
            FaceDetector.createFromOptions(
                appContext,
                FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(baseOptions(FACE_MODEL))
                    .setRunningMode(RunningMode.VIDEO)
                    .setMinDetectionConfidence(MIN_FACE_CONFIDENCE)
                    .build(),
            ).also { faceDetector = it }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Face detector unavailable", e)
            faceFailed = true
            null
        }
    }

    // Pose

    private fun detectPose(candidate: OrientationCandidate, cameraRotation: Int, millis: Long): BodyPoseObservation? {
        val landmarker = poseLandmarker(candidate) ?: return null
        val image = frames.rgb(candidate.rotationDegrees(cameraRotation))
        val slot = candidate.ordinal
        val timestamp = maxOf(millis, lastPoseTimestamps[slot] + 1).also { lastPoseTimestamps[slot] = it }
        val result = try {
            landmarker.detectForVideo(image.mpImage(), timestamp)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Pose detection failed", e)
            return null
        }
        val landmarks = result.landmarks().firstOrNull() ?: return null
        val joints = HashMap<PoseJoint, PosePoint>()
        for (joint in PoseJoint.entries) {
            val landmark = landmarks.getOrNull(OrientationMapping.poseLandmarkIndex(joint)) ?: continue
            // Presence is "inside the picture", the counterpart of Vision's joint confidence;
            // the landmarker has already dropped poses below its presence threshold.
            val likelihood = landmark.presence().orElse(null) ?: landmark.visibility().orElse(null) ?: 1f
            if (likelihood < MIN_LANDMARK_LIKELIHOOD) continue
            val (x, yValue) = OrientationMapping.normalizedPoint(landmark.x(), landmark.y())
            joints[joint] = PosePoint(x, yValue, likelihood)
        }
        // Without both shoulders the orientation is treated as a miss, so the search moves on.
        if (joints[PoseJoint.LEFT_SHOULDER] == null || joints[PoseJoint.RIGHT_SHOULDER] == null) return null
        val (xScale, yScale) = OrientationMapping.poseScales(image.width, image.height)
        return BodyPoseObservation(joints, xScale, yScale)
    }

    private fun poseLandmarker(candidate: OrientationCandidate): PoseLandmarker? {
        poseLandmarkers[candidate.ordinal]?.let { return it }
        if (poseFailed) return null
        return try {
            PoseLandmarker.createFromOptions(
                appContext,
                PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions(POSE_MODEL))
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(MIN_POSE_CONFIDENCE)
                    .setMinPosePresenceConfidence(MIN_POSE_CONFIDENCE)
                    .setMinTrackingConfidence(MIN_POSE_CONFIDENCE)
                    .setOutputSegmentationMasks(false)
                    .build(),
            ).also { poseLandmarkers[candidate.ordinal] = it }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Pose landmarker unavailable", e)
            poseFailed = true
            null
        }
    }

    private fun closePoseLandmarkers() {
        for (i in poseLandmarkers.indices) {
            poseLandmarkers[i]?.close()
            poseLandmarkers[i] = null
            lastPoseTimestamps[i] = Long.MIN_VALUE
        }
        poseFailed = false
    }

    private fun RgbImage.mpImage(): MPImage =
        ByteBufferImageBuilder(buffer, width, height, MPImage.IMAGE_FORMAT_RGB).build()

    private fun baseOptions(model: String): BaseOptions =
        BaseOptions.builder().setModelAssetPath(model).setDelegate(Delegate.CPU).build()

    companion object {
        private const val TAG = "VisionProcessor"
        /**
         * Full range, not short range: the short-range model is built for selfie distance (up to
         * about 2 m), and at the top of a pull-up the face is further than that from a phone on
         * the floor.
         */
        const val FACE_MODEL = "mediapipe/blaze_face_full_range.tflite"
        const val POSE_MODEL = "mediapipe/pose_landmarker_lite.task"

        /** MediaPipe's default; also where core starts trusting a face (`SignalConfig.minConfidence`). */
        const val MIN_FACE_CONFIDENCE: Float = 0.5f
        const val MIN_POSE_CONFIDENCE: Float = 0.5f

        /** Below this presence a landmark counts as not recognised, like a joint Vision leaves out. */
        const val MIN_LANDMARK_LIKELIHOOD: Float = 0.5f
    }
}
