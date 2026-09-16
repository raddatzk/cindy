package me.raddatz.cindy.camera

import android.content.Context
import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.debug.FrameLogger
import me.raddatz.cindy.core.signal.FrameObservation
import me.raddatz.cindy.core.signal.PipelineOutput
import me.raddatz.cindy.core.signal.SignalPipeline
import java.util.concurrent.RejectedExecutionException

/**
 * Glue between the camera thread and the signal chain (iOS: `FrameProcessor`).
 *
 * Owns the [VisionProcessor], the current [SignalPipeline] and an optional [FrameLogger].
 * Everything here runs on the camera's frame thread; the pipeline is swapped by posting onto that
 * thread, so the swap never races a frame that is being processed.
 */
class CameraFrameSource(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    config: SignalConfig = SignalConfig.default,
) : FrameSource {
    /** The capture session, e.g. for [CameraPreview]. */
    val camera = CameraSession(context, lifecycleOwner, config)

    private val vision = VisionProcessor(context)

    // Confined to the frame thread.
    private var pipeline: SignalPipeline? = null
    private var logger: FrameLogger? = null
    private var stateProvider: (() -> String)? = null
    private var measuresMetrics = false

    @Volatile
    override var onFrame: ((FrameObservation, PipelineOutput?) -> Unit)? = null

    override var onAvailabilityChange: ((CameraAvailability) -> Unit)?
        get() = camera.onAvailabilityChange
        set(value) {
            camera.onAvailabilityChange = value
        }

    init {
        camera.frameHandler = { image -> handle(image) }
    }

    override suspend fun start() {
        camera.start()
    }

    override fun stop() {
        camera.stop()
        // The detectors hold native memory; the next frame after a restart creates them again.
        onFrameThread { vision.close() }
    }

    override fun release() {
        onFrameThread {
            vision.release()
            logger = null
            pipeline = null
        }
        camera.release()
    }

    override fun relockExposure() {
        camera.relockExposure()
    }

    override fun setPipeline(pipeline: SignalPipeline?) {
        onFrameThread { this.pipeline = pipeline }
    }

    override fun setDetection(face: Boolean, bodyPose: Boolean) {
        onFrameThread {
            vision.detectFace = face
            vision.detectBodyPose = bodyPose
        }
    }

    override fun setDetectionIntervals(face: Double, bodyPose: Double) {
        onFrameThread {
            vision.faceInterval = face
            vision.poseInterval = bodyPose
        }
    }

    override val stats: VisionStats? get() = vision.stats

    override fun setMetricsEnabled(enabled: Boolean) {
        onFrameThread { measuresMetrics = enabled }
    }

    override fun setLogger(logger: FrameLogger?, stateProvider: (() -> String)?) {
        onFrameThread {
            this.logger = logger
            this.stateProvider = stateProvider
        }
    }

    private fun onFrameThread(block: () -> Unit) {
        try {
            camera.videoExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Released: nothing left to configure.
        }
    }

    private fun handle(image: ImageProxy) {
        val planes = image.planes
        if (planes.size < 3) return
        val observation = vision.process(
            width = image.width,
            height = image.height,
            y = YuvPlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
            u = YuvPlane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
            v = YuvPlane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride),
            cameraRotationDegrees = image.imageInfo.rotationDegrees,
            timestampNanos = image.imageInfo.timestamp,
            measureMetrics = measuresMetrics,
        )
        val output = pipeline?.process(observation)
        logger?.log(observation, output, stateProvider?.invoke() ?: "")
        onFrame?.invoke(observation, output)
    }
}
