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
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs MediaPipe face detection and pose landmarking on a frame and returns a [FrameObservation]
 * (iOS: `VisionProcessor` with Vision's face-rectangle and body-pose requests). Both models are
 * bundled assets on the CPU delegate, so detection needs neither the network nor Google Play
 * services.
 *
 * The phone lies flat, so each detector keeps its own [OrientationSearch]: the candidate that last
 * worked first, one alternative per frame after a miss. The pixels are rotated for the candidate
 * by [RgbFrames] (half resolution, RGB).
 *
 * Two kinds of detector run:
 * - The **signal** detector (interval 0: the face for push-ups, pull-ups and plank) runs on the
 *   frame thread for every frame, because every frame is a sample of the signal.
 * - **Evidence** detectors (interval > 0: face and pose next to the brightness squats) run on a
 *   separate thread on a copy of the frame, at most once per interval, and never make the frame
 *   thread wait. Frames carry their latest result, hit or miss. On a Galaxy A20e face and pose on
 *   every frame held the whole chain — the brightness signal included — at 5 fps; the brightness
 *   itself costs about a millisecond.
 *
 * Video mode wants strictly increasing timestamps per detector instance, and the pose landmarker
 * tracks the body from one frame to the next. So:
 * - the face detector is one instance; a probe in the same frame gets the next millisecond;
 * - the pose landmarker is one instance per orientation candidate, created when the search first
 *   needs it, so its tracking only ever sees frames in its own orientation.
 * Each detector and its search are guarded by a lock, because a configuration change can move a
 * detector between the frame thread and the evidence thread while a run is still going.
 *
 * Call [process], [close] and [release] from the frame thread only.
 */
class VisionProcessor(
    context: Context,
    detectFace: Boolean = true,
    detectBodyPose: Boolean = false,
) {
    /** Whether to run face detection (the signal for push-ups, pull-ups and plank). */
    @Volatile
    var detectFace: Boolean = detectFace
        set(value) {
            field = value
            forgetFace()
        }

    /** Whether to run the (expensive) pose landmarker (evidence for squats, pose signals). */
    @Volatile
    var detectBodyPose: Boolean = detectBodyPose
        set(value) {
            field = value
            forgetPose()
        }

    /** Seconds between face runs; 0 runs it on the frame thread for every frame. */
    var faceInterval: Double = 0.0
        set(value) {
            field = value
            forgetFace()
        }

    /** Seconds between pose runs; 0 runs it on the frame thread for every frame. */
    var poseInterval: Double = 0.0
        set(value) {
            field = value
            forgetPose()
        }

    /** Timing of the last few seconds; written on the frame thread. */
    @Volatile
    var stats: VisionStats? = null
        private set

    private val appContext = context.applicationContext
    private val statsAccumulator = VisionStatsAccumulator()

    private val frames = RgbFrames()
    private val faceLock = Any()
    private val faceSearch = OrientationSearch()
    private var faceDetector: FaceDetector? = null
    private var faceFailed = false
    private var lastFaceTimestamp = Long.MIN_VALUE

    private val poseLock = Any()
    private val poseSearch = OrientationSearch()
    private val poseLandmarkers = arrayOfNulls<PoseLandmarker>(OrientationCandidate.entries.size)
    private val lastPoseTimestamps = LongArray(OrientationCandidate.entries.size) { Long.MIN_VALUE }
    private var poseFailed = false

    // Evidence: scheduled on the frame thread, run on the evidence thread.
    private val evidenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "me.raddatz.cindy.evidence").apply { isDaemon = true }
    }
    private val evidenceIdle = AtomicBoolean(true)
    private val evidenceFrames = RgbFrames()
    private val snapshot = YuvSnapshot()
    private var lastFaceRun = Double.NEGATIVE_INFINITY
    private var lastPoseRun = Double.NEGATIVE_INFINITY

    @Volatile
    private var latestFace: OrientationSearch.Hit<FaceObservation>? = null

    @Volatile
    private var latestPose: OrientationSearch.Hit<BodyPoseObservation>? = null

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
        val started = System.nanoTime()
        val millis = timestampNanos / 1_000_000
        val seconds = timestampNanos / 1e9
        scheduleEvidence(width, height, y, u, v, cameraRotationDegrees, millis, seconds)

        val metrics = if (measureMetrics) {
            FrameMetricsCalculator.measure(y.buffer, width, height, y.rowStride)
        } else {
            null
        }

        frames.begin(width, height, y, u, v)
        try {
            val faceHit = when {
                !detectFace -> null
                faceInterval <= 0.0 -> findFace(frames, cameraRotationDegrees, millis)
                else -> latestFace
            }
            val poseHit = when {
                !detectBodyPose -> null
                poseInterval <= 0.0 -> findPose(frames, cameraRotationDegrees, millis)
                else -> latestPose
            }
            return FrameObservation(
                timestamp = seconds,
                face = faceHit?.result,
                pose = poseHit?.result,
                orientation = faceHit?.orientation?.exifOrientation,
                poseOrientation = poseHit?.orientation?.exifOrientation,
                metrics = metrics,
            )
        } finally {
            frames.end()
            statsAccumulator.rgb(frames.takeConversionNanos())
            statsAccumulator.frame(seconds, System.nanoTime() - started)?.let {
                stats = it
                Log.i(PERF_TAG, it.summary())
            }
        }
    }

    /** Releases the detectors; the next frame creates them again. */
    fun close() {
        runOnEvidenceThread { }
        synchronized(faceLock) {
            faceDetector?.close()
            faceDetector = null
            faceFailed = false
            lastFaceTimestamp = Long.MIN_VALUE
        }
        synchronized(poseLock) {
            for (i in poseLandmarkers.indices) {
                poseLandmarkers[i]?.close()
                poseLandmarkers[i] = null
                lastPoseTimestamps[i] = Long.MIN_VALUE
            }
            poseFailed = false
        }
        forgetFace()
        forgetPose()
        statsAccumulator.reset()
        stats = null
    }

    /** Closes the detectors and ends the evidence thread for good. */
    fun release() {
        close()
        evidenceExecutor.shutdown()
    }

    // Evidence

    private fun scheduleEvidence(
        width: Int,
        height: Int,
        y: YuvPlane,
        u: YuvPlane,
        v: YuvPlane,
        cameraRotation: Int,
        millis: Long,
        seconds: Double,
    ) {
        val runFace = detectFace && faceInterval > 0.0 && seconds - lastFaceRun >= faceInterval
        val runPose = detectBodyPose && poseInterval > 0.0 && seconds - lastPoseRun >= poseInterval
        if (!runFace && !runPose) return
        // Still busy with the previous frame: skip, the next frame asks again.
        if (!evidenceIdle.compareAndSet(true, false)) return
        if (runFace) lastFaceRun = seconds
        if (runPose) lastPoseRun = seconds
        // The camera reuses its buffers once the frame is closed, so the thread gets a copy.
        val planes = snapshot.copy(y, u, v)
        try {
            evidenceExecutor.execute {
                try {
                    evidenceFrames.begin(width, height, planes[0], planes[1], planes[2])
                    try {
                        if (runFace) latestFace = findFace(evidenceFrames, cameraRotation, millis)
                        if (runPose) latestPose = findPose(evidenceFrames, cameraRotation, millis)
                    } finally {
                        evidenceFrames.end()
                        statsAccumulator.rgb(evidenceFrames.takeConversionNanos())
                    }
                } finally {
                    evidenceIdle.set(true)
                }
            }
        } catch (_: RejectedExecutionException) {
            evidenceIdle.set(true)
        }
    }

    /** Waits until the evidence thread has finished what it is doing. */
    private fun runOnEvidenceThread(block: () -> Unit) {
        try {
            evidenceExecutor.submit(block).get()
        } catch (_: RejectedExecutionException) {
            block()
        } catch (_: java.util.concurrent.ExecutionException) {
        }
    }

    private fun forgetFace() {
        lastFaceRun = Double.NEGATIVE_INFINITY
        latestFace = null
    }

    private fun forgetPose() {
        lastPoseRun = Double.NEGATIVE_INFINITY
        latestPose = null
    }

    // Face

    private fun findFace(
        source: RgbFrames,
        cameraRotation: Int,
        millis: Long,
    ): OrientationSearch.Hit<FaceObservation>? = synchronized(faceLock) {
        var detectorNanos = 0L
        val hit = faceSearch.find { candidate ->
            val detector = faceDetector() ?: return@find null
            val image = source.rgb(candidate.rotationDegrees(cameraRotation))
            val timestamp = maxOf(millis, lastFaceTimestamp + 1).also { lastFaceTimestamp = it }
            val started = System.nanoTime()
            val result = try {
                detector.detectForVideo(image.mpImage(), timestamp)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Face detection failed", e)
                null
            } finally {
                detectorNanos += System.nanoTime() - started
            }
            result?.let { faceObservation(it, image) }
        }
        statsAccumulator.face(detectorNanos)
        hit
    }

    private fun faceObservation(
        result: com.google.mediapipe.tasks.vision.facedetector.FaceDetectorResult,
        image: RgbImage,
    ): FaceObservation? {
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

    private fun findPose(
        source: RgbFrames,
        cameraRotation: Int,
        millis: Long,
    ): OrientationSearch.Hit<BodyPoseObservation>? = synchronized(poseLock) {
        var detectorNanos = 0L
        val hit = poseSearch.find { candidate ->
            val landmarker = poseLandmarker(candidate) ?: return@find null
            val image = source.rgb(candidate.rotationDegrees(cameraRotation))
            val slot = candidate.ordinal
            val timestamp = maxOf(millis, lastPoseTimestamps[slot] + 1).also { lastPoseTimestamps[slot] = it }
            val started = System.nanoTime()
            val result = try {
                landmarker.detectForVideo(image.mpImage(), timestamp)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Pose detection failed", e)
                null
            } finally {
                detectorNanos += System.nanoTime() - started
            }
            result?.let { poseObservation(it, image) }
        }
        statsAccumulator.pose(detectorNanos)
        hit
    }

    private fun poseObservation(
        result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult,
        image: RgbImage,
    ): BodyPoseObservation? {
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

    private fun RgbImage.mpImage(): MPImage =
        ByteBufferImageBuilder(buffer, width, height, MPImage.IMAGE_FORMAT_RGB).build()

    private fun baseOptions(model: String): BaseOptions =
        BaseOptions.builder().setModelAssetPath(model).setDelegate(Delegate.CPU).build()

    companion object {
        private const val TAG = "VisionProcessor"
        private const val PERF_TAG = "CindyPerf"

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

/**
 * A copy of a frame's three planes for the evidence thread. The buffers are reused and only
 * reallocated when the frame size changes; copying about 1.4 MB a few times a second is far
 * cheaper than any detector run.
 */
private class YuvSnapshot {
    private val copies = arrayOfNulls<ByteBuffer>(3)

    fun copy(y: YuvPlane, u: YuvPlane, v: YuvPlane): Array<YuvPlane> =
        arrayOf(copyPlane(0, y), copyPlane(1, u), copyPlane(2, v))

    private fun copyPlane(index: Int, plane: YuvPlane): YuvPlane {
        val source = plane.buffer.duplicate()
        val size = source.remaining()
        val target = copies[index]?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocate(size).also { copies[index] = it }
        target.clear()
        target.put(source)
        target.flip()
        return YuvPlane(target, plane.rowStride, plane.pixelStride)
    }
}
