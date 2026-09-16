package me.raddatz.cindy

import me.raddatz.cindy.audio.AudioCues
import me.raddatz.cindy.camera.CameraAvailability
import me.raddatz.cindy.camera.CameraException
import me.raddatz.cindy.camera.FrameSource
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.debug.FrameLogger
import me.raddatz.cindy.core.signal.FrameObservation
import me.raddatz.cindy.core.signal.PipelineOutput
import me.raddatz.cindy.core.signal.SignalPipeline

/** Records every tone an engine plays. */
class RecordingAudio : AudioCues {
    val events = mutableListOf<String>()
    var prepared = 0
    var released = 0

    override fun prepare() {
        prepared += 1
    }

    override fun beep() {
        events += "beep"
    }

    override fun goSignal() {
        events += "go"
    }

    override fun endSignal() {
        events += "end"
    }

    override fun release() {
        released += 1
    }

    fun count(event: String): Int = events.count { it == event }
}

/**
 * Frame source driven by the test: [emit] runs the installed pipeline synchronously, like the
 * camera thread does, and reports the frame.
 */
class FakeFrameSource : FrameSource {
    override var onFrame: ((FrameObservation, PipelineOutput?) -> Unit)? = null
    override var onAvailabilityChange: ((CameraAvailability) -> Unit)? = null

    var startFailure: CameraException? = null
    var isRunning = false
    var started = 0
    var released = false
    var relocks = 0
    private var installed: SignalPipeline? = null

    /** The pipeline the engine installed last. */
    val pipeline: SignalPipeline? get() = installed
    var detectsFace = true
    var detectsPose = false
    var measuresMetrics = false
    var faceInterval = 0.0
    var poseInterval = 0.0
    var logger: FrameLogger? = null
    var stateProvider: (() -> String)? = null

    override suspend fun start() {
        startFailure?.let { throw it }
        started += 1
        isRunning = true
    }

    override fun stop() {
        isRunning = false
    }

    override fun release() {
        released = true
        isRunning = false
    }

    override fun relockExposure() {
        relocks += 1
    }

    override fun setPipeline(pipeline: SignalPipeline?) {
        installed = pipeline
    }

    override fun setDetection(face: Boolean, bodyPose: Boolean) {
        detectsFace = face
        detectsPose = bodyPose
    }

    override fun setDetectionIntervals(face: Double, bodyPose: Double) {
        faceInterval = face
        poseInterval = bodyPose
    }

    override fun setMetricsEnabled(enabled: Boolean) {
        measuresMetrics = enabled
    }

    override fun setLogger(logger: FrameLogger?, stateProvider: (() -> String)?) {
        this.logger = logger
        this.stateProvider = stateProvider
    }

    /** Pushes one frame through the installed pipeline. */
    fun emit(observation: FrameObservation): PipelineOutput? {
        val output = pipeline?.process(observation)
        logger?.log(observation, output, stateProvider?.invoke() ?: "")
        onFrame?.invoke(observation, output)
        return output
    }

    /** Reports a hand-made pipeline output, e.g. a rep event. */
    fun emit(observation: FrameObservation, output: PipelineOutput?) {
        onFrame?.invoke(observation, output)
    }

    fun report(availability: CameraAvailability) {
        onAvailabilityChange?.invoke(availability)
    }

    val trackedSource: SignalSource? get() = pipeline?.source
}
