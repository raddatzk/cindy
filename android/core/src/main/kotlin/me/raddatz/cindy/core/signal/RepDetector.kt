package me.raddatz.cindy.core.signal

import me.raddatz.cindy.core.SignalConfig
import kotlin.math.abs

sealed interface RepDetectorEvent {
    /** The signal has been stable in the rest band for `stableFrames` reference frames; reps are counted from now on. */
    data object Armed : RepDetectorEvent

    /** The person was lost for longer than `lostTimeout`; the detector needs to re-arm. */
    data object Disarmed : RepDetectorEvent

    /** A full, plausible cycle was completed. [duration] in seconds. */
    data class RepCompleted(val duration: Double) : RepDetectorEvent

    /** A full cycle was completed but its duration was outside the plausible range (or it was vetoed). */
    data class RepRejected(val duration: Double) : RepDetectorEvent
}

/** Where the signal is within the current cycle (normalised to "peak" polarity). */
enum class RepPhase(val rawValue: String) {
    /** In the rest band (below `low`). */
    REST("rest"),

    /** Left the rest band but has not reached `high` yet. */
    LEAVING("leaving"),

    /** Crossed `high`; the rep completes once the signal returns below `low`. */
    PEAKED("peaked"),
}

/**
 * Schmitt-trigger rep counter for one exercise.
 *
 * A rep is a full cycle: leave the rest band, cross the far threshold, return into the rest
 * band. Frames with low confidence are ignored and the state is held. The counter is armed only
 * after `stableFrames` consecutive confident frames inside the rest band, which also prevents
 * the first "settling" movement after an exercise switch from being counted. Frames are counted
 * at the reference frame rate ([FrameTiming.covers]): at 5 fps three samples spanning at least
 * 0.3 s arm, where ten samples (2 s) would outlast the pause between two squats.
 *
 * With relative thresholds the rest level is the mean of those frames; the thresholds are
 * adapted to it (scaled or shifted), and the frames must lie inside the rest band of the adapted
 * thresholds. Scaled thresholds let the level follow lower values while armed at rest
 * (`restTrackingAlpha` per reference frame), and a relative cycle open longer than
 * `maxRepDuration` disarms, so walking away after arming and stepping closer are both corrected.
 * [cycleValidator] can veto a completed cycle.
 */
class RepDetector(
    val thresholds: RepThresholds,
    val config: SignalConfig = SignalConfig.default,
) {
    var isArmed: Boolean = false
        private set
    var phase: RepPhase = RepPhase.REST
        private set
    var repCount: Int = 0
        private set

    /**
     * The thresholds the state machine compares against: [thresholds], adapted to the measured
     * rest level once armed if they are relative.
     */
    var activeThresholds: RepThresholds = thresholds
        private set

    /** Asked when a cycle of plausible duration completes; `false` turns it into [RepDetectorEvent.RepRejected]. */
    var cycleValidator: (() -> Boolean)? = null

    private data class RestSample(val value: Float, val timestamp: Double)

    /**
     * Consecutive confident samples while unarmed, newest last: the shortest run of newest samples
     * that covers `stableFrames` (at most `stableFrames` samples).
     */
    private val restWindow = ArrayList<RestSample>()

    /** Rest level the active thresholds are scaled to (relative thresholds only). */
    private var restLevel: Float? = null
    private var cycleStart: Double? = null
    private var lastConfidentTimestamp: Double? = null

    /** Timestamp of the previous frame of any confidence, for the frame interval. */
    private var lastTimestamp: Double? = null

    /** Feeds one (already smoothed) sample. [timestamp] in seconds. */
    fun process(value: Float?, confidence: Float, timestamp: Double): RepDetectorEvent? {
        val frameInterval = lastTimestamp?.let { timestamp - it }
        lastTimestamp = timestamp
        if (value == null || confidence < config.minConfidence) {
            return handleLowConfidence(timestamp)
        }
        lastConfidentTimestamp = timestamp

        if (!isArmed) {
            val armed = armedThresholds(value, timestamp) ?: return null
            activeThresholds = armed
            restLevel = if (armed.isRelative) armed.baseline else null
            restWindow.clear()
            isArmed = true
            phase = RepPhase.REST
            cycleStart = null
            return RepDetectorEvent.Armed
        }

        val (v, low, high) = normalised(value, activeThresholds)

        when (phase) {
            RepPhase.REST -> if (v >= low) {
                phase = RepPhase.LEAVING
                cycleStart = timestamp
            } else {
                trackRest(value, frameInterval)
            }
            RepPhase.LEAVING -> if (v >= high) {
                phase = RepPhase.PEAKED
            } else if (v < low) {
                phase = RepPhase.REST // bounced back without a full rep
                cycleStart = null
            }
            RepPhase.PEAKED -> if (v < low) {
                phase = RepPhase.REST
                val duration = timestamp - (cycleStart ?: timestamp)
                cycleStart = null
                if (duration >= config.minRepDuration && duration <= config.maxRepDuration &&
                    (cycleValidator?.invoke() ?: true)
                ) {
                    repCount += 1
                    return RepDetectorEvent.RepCompleted(duration)
                }
                return RepDetectorEvent.RepRejected(duration)
            }
        }
        val start = cycleStart
        if (thresholds.isRelative && phase != RepPhase.REST && start != null &&
            timestamp - start > config.maxRepDuration
        ) {
            reset()
            return RepDetectorEvent.Disarmed
        }
        return null
    }

    fun reset() {
        isArmed = false
        phase = RepPhase.REST
        activeThresholds = thresholds
        restWindow.clear()
        restLevel = null
        cycleStart = null
        lastConfidentTimestamp = null
    }

    /** Lets the rest level follow values on the rest side of it (relative thresholds only). */
    private fun trackRest(value: Float, frameInterval: Double?) {
        val rest = restLevel ?: return
        if (thresholds.adaptation != RestAdaptation.SCALE || config.restTrackingAlpha <= 0) return
        val restSide = if (thresholds.direction == RepDirection.PEAK) value < rest else value > rest
        if (!restSide) return
        val next = rest + FrameTiming.alpha(config.restTrackingAlpha, frameInterval) * (value - rest)
        restLevel = next
        activeThresholds = thresholds.adapted(toRest = next)
    }

    /**
     * Adds an unarmed sample; returns the thresholds to arm with once the newest samples cover
     * `stableFrames` and form a plausible rest.
     */
    private fun armedThresholds(value: Float, timestamp: Double): RepThresholds? {
        restWindow.add(RestSample(value, timestamp))
        // Keep the shortest run of newest samples that still covers the debounce; at 30 fps that is
        // exactly the last `stableFrames` samples.
        while (restWindow.size > 1 && coversStableFrames(restWindow.size - 1, restWindow[1].timestamp, timestamp)) {
            restWindow.removeAt(0)
        }
        if (!coversStableFrames(restWindow.size, restWindow[0].timestamp, timestamp)) return null

        var candidate = thresholds
        val baseline = thresholds.baseline
        if (baseline != null && thresholds.isRelative) {
            var sum = 0f
            for (sample in restWindow) sum += sample.value
            val rest = sum / restWindow.size.toFloat()
            when (thresholds.adaptation) {
                RestAdaptation.SCALE -> {
                    val scale = rest / baseline
                    val tolerance = maxOf(config.restBaselineTolerance, 1f)
                    if (!(scale <= tolerance && scale >= 1 / tolerance)) return null
                }
                RestAdaptation.SHIFT -> if (!(abs(rest - baseline) <= config.restShiftTolerance)) return null
            }
            candidate = thresholds.adapted(toRest = rest)
        }
        val allInRest = restWindow.all { sample ->
            val (v, low, _) = normalised(sample.value, candidate)
            v < low
        }
        return if (allInRest) candidate else null
    }

    private fun coversStableFrames(count: Int, first: Double, last: Double): Boolean =
        FrameTiming.covers(maxOf(config.stableFrames, 1), count, last - first, FrameTiming.minStableSamples)

    private data class Normalised(val value: Float, val low: Float, val high: Float)

    /** Maps the sample and thresholds into "peak" polarity so one state machine handles both directions. */
    private fun normalised(value: Float, thresholds: RepThresholds): Normalised = when (thresholds.direction) {
        RepDirection.PEAK -> Normalised(value, thresholds.low, thresholds.high)
        RepDirection.TROUGH -> Normalised(-value, -thresholds.high, -thresholds.low)
    }

    private fun handleLowConfidence(timestamp: Double): RepDetectorEvent? {
        if (!isArmed) {
            restWindow.clear()
        }
        val last = lastConfidentTimestamp ?: return null
        if (timestamp - last > config.lostTimeout) {
            val wasArmed = isArmed
            reset()
            return if (wasArmed) RepDetectorEvent.Disarmed else null
        }
        return null // hold state
    }
}
