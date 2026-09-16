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

    /**
     * Minimum time in seconds between two runs of each detector; 0 runs it on every frame. In
     * between, frames carry the detector's latest result. A detector that only supplies
     * `BodyEvidence` does not need every frame, and on a slow phone running it on every frame
     * drags the whole chain — the brightness signal included — down to its speed.
     */
    fun setDetectionIntervals(face: Double, bodyPose: Double) {}

    /** Frame rate and per-stage processing time over the last seconds; null before any frame. */
    val stats: VisionStats? get() = null

    /** Measures the brightness (`FrameMetrics`) per frame. */
    fun setMetricsEnabled(enabled: Boolean)

    fun setLogger(logger: FrameLogger?, stateProvider: (() -> String)? = null)

    /**
     * Runs only what the signal source needs; brightness keeps face and pose for `BodyEvidence`.
     * The detector that produces the signal runs on every frame, evidence-only detectors at
     * [EVIDENCE_INTERVAL].
     */
    fun setDetection(source: SignalSource) {
        setDetection(face = source != SignalSource.POSE, bodyPose = source != SignalSource.FACE)
        setDetectionIntervals(
            face = if (source == SignalSource.FACE) 0.0 else EVIDENCE_INTERVAL,
            bodyPose = if (source == SignalSource.POSE) 0.0 else EVIDENCE_INTERVAL,
        )
        setMetricsEnabled(source == SignalSource.BRIGHTNESS)
    }

    companion object {
        /**
         * Seconds between evidence-only detector runs. A squat takes about two seconds and the
         * evidence asks whether the face or the shoulders grew during it, so four to five looks
         * per second are plenty.
         */
        const val EVIDENCE_INTERVAL: Double = 0.2
    }
}
