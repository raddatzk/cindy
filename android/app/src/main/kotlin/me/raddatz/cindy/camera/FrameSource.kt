package me.raddatz.cindy.camera

import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.debug.FrameLogger
import me.raddatz.cindy.core.signal.FrameObservation
import me.raddatz.cindy.core.signal.PipelineOutput
import me.raddatz.cindy.core.signal.SignalPipeline

/**
 * Camera → vision → signal chain, as the engines see it (iOS: `CameraSession` + `FrameProcessor`).
 *
 * The production implementation is [CameraFrameSource]; tests drive the engines with a fake.
 * Pipeline, detection and logger changes are applied on the frame thread in order, so a swap never
 * races a frame that is being processed.
 */
interface FrameSource {
    /** Called on the frame thread for every processed frame. Set it before [start]. */
    var onFrame: ((FrameObservation, PipelineOutput?) -> Unit)?

    /**
     * Called on the main thread when frame delivery stops or comes back. Never called for a
     * [stop] this app asked for. Set it before [start].
     */
    var onAvailabilityChange: ((CameraAvailability) -> Unit)?

    /**
     * Opens the camera and starts delivering frames. Exposure and white balance run on auto for
     * `SignalConfig.exposureSettleDuration` and are locked afterwards.
     *
     * @throws CameraException when the camera cannot be started.
     */
    suspend fun start()

    /** Stops frame delivery. Safe to call repeatedly. */
    fun stop()

    /** Stops for good and releases threads and detectors. */
    fun release()

    /** Re-runs auto exposure and locks it again after the settle duration. */
    fun relockExposure()

    /** Replaces the pipeline (null = vision only, no rep detection). */
    fun setPipeline(pipeline: SignalPipeline?)

    /** Chooses the detectors; each costs processing time per frame. */
    fun setDetection(face: Boolean, bodyPose: Boolean)

    /** Measures the brightness (`FrameMetrics`) per frame. */
    fun setMetricsEnabled(enabled: Boolean)

    fun setLogger(logger: FrameLogger?, stateProvider: (() -> String)? = null)

    /** Runs only what the signal source needs; brightness keeps face and pose for `BodyEvidence`. */
    fun setDetection(source: SignalSource) {
        setDetection(face = source != SignalSource.POSE, bodyPose = source != SignalSource.FACE)
        setMetricsEnabled(source == SignalSource.BRIGHTNESS)
    }
}
