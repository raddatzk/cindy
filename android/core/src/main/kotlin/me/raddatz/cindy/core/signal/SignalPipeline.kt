package me.raddatz.cindy.core.signal

import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.SignalSource

/** Result of pushing one frame through extractor → EMA → rep detector. */
data class PipelineOutput(
    val timestamp: Double,
    val exercise: Exercise,
    val source: SignalSource,
    val raw: Float?,
    val smoothed: Float?,
    val confidence: Float,
    val event: RepDetectorEvent?,
    val phase: RepPhase,
    val isArmed: Boolean,
    val repCount: Int,
    /** Accumulated hold time in seconds (plank only). */
    val heldSeconds: Double? = null,
    /** Thresholds the detector currently compares against (rescaled once armed if relative). */
    val thresholds: RepThresholds? = null,
)

/**
 * Extractor → (median for pose) → EMA smoothing → Schmitt-trigger rep detector for one exercise;
 * brightness reps additionally need [BodyEvidence].
 * Not thread-safe; call [process] from a single thread.
 *
 * [holdSeconds] switches the pipeline to the time-based hold detector (plank). [source] defaults
 * to the config's source for the exercise.
 */
class SignalPipeline(
    val exercise: Exercise,
    thresholds: RepThresholds,
    source: SignalSource? = null,
    val config: SignalConfig = SignalConfig.default,
    holdSeconds: Double? = null,
) {
    val source: SignalSource = source ?: config.source(exercise)
    val thresholds: RepThresholds

    private val extractor = SignalExtractor(config)
    private val ema = EMAFilter(config.emaAlpha)
    private val median: MedianFilter? =
        if (this.source == SignalSource.POSE) MedianFilter(config.poseMedianWindow) else null
    private val detector: RepDetector?
    private val holdDetector: HoldDetector?
    private val evidence: BodyEvidence?

    /** Timestamp of the previous frame of any confidence; the EMA scales to this interval. */
    private var lastTimestamp: Double? = null

    init {
        val adaptation = extractor.restAdaptation(exercise, this.source)
        this.thresholds = if (adaptation != null) {
            thresholds.copy(adaptation = adaptation)
        } else {
            thresholds.copy(baseline = null)
        }
        if (holdSeconds != null) {
            holdDetector = HoldDetector(this.thresholds, holdSeconds, config)
            detector = null
        } else {
            detector = RepDetector(this.thresholds, config)
            holdDetector = null
        }
        if (this.source == SignalSource.BRIGHTNESS && detector != null) {
            val bodyEvidence = BodyEvidence(config)
            evidence = bodyEvidence
            detector.cycleValidator = {
                if (!bodyEvidence.hasObservations) true else bodyEvidence.supportsCycle
            }
        } else {
            evidence = null
        }
    }

    val isArmed: Boolean get() = detector?.isArmed ?: holdDetector?.isArmed ?: false
    val repCount: Int get() = detector?.repCount ?: holdDetector?.repCount ?: 0
    private val phase: RepPhase get() = detector?.phase ?: holdDetector?.phase ?: RepPhase.REST
    private val heldSeconds: Double? get() = holdDetector?.heldSeconds
    private val activeThresholds: RepThresholds? get() = detector?.activeThresholds ?: holdDetector?.thresholds

    private fun detect(value: Float?, confidence: Float, timestamp: Double): RepDetectorEvent? {
        if (detector != null) return detector.process(value, confidence, timestamp)
        return holdDetector?.process(value, confidence, timestamp)
    }

    fun process(observation: FrameObservation): PipelineOutput {
        evidence?.update(observation, cycleActive = phase != RepPhase.REST)
        val sample = extractor.extract(observation, exercise, source)
        return process(sample.value, sample.confidence, observation.timestamp)
    }

    /** Feeds an already extracted scalar (also used by tests and CSV replay). */
    fun process(value: Float?, confidence: Float, timestamp: Double): PipelineOutput {
        // The interval to the previous camera frame, not to the previous EMA update: a frame without
        // a face holds the EMA like before, only a lower frame rate moves it further per sample.
        val frameInterval = lastTimestamp?.let { timestamp - it }
        lastTimestamp = timestamp
        val smoothed: Float? = if (value != null && confidence >= config.minConfidence) {
            val filtered = median?.update(value) ?: value
            ema.update(filtered, frameInterval)
        } else {
            ema.value // hold the last value; the detector ignores this frame anyway
        }
        val event = detect(
            value = if (confidence >= config.minConfidence) smoothed else null,
            confidence = confidence,
            timestamp = timestamp,
        )
        return PipelineOutput(
            timestamp = timestamp, exercise = exercise, source = source, raw = value, smoothed = smoothed,
            confidence = confidence, event = event, phase = phase, isArmed = isArmed,
            repCount = repCount, heldSeconds = heldSeconds, thresholds = activeThresholds,
        )
    }
}
