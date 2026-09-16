package me.raddatz.cindy.core.signal

import me.raddatz.cindy.core.SignalConfig

/**
 * Time-based counterpart of [RepDetector] for planks.
 *
 * The signal has to stay inside the calibrated band [low, high]. While it does (and the face is
 * seen), held time accumulates from the frame timestamps. Leaving the band or losing the face
 * pauses the clock without resetting it. Emits [RepDetectorEvent.RepCompleted] once the
 * accumulated time reaches [targetSeconds].
 */
class HoldDetector(
    val thresholds: RepThresholds,
    val targetSeconds: Double,
    val config: SignalConfig = SignalConfig.default,
) {
    var isArmed: Boolean = false
        private set
    var isHolding: Boolean = false
        private set
    var heldSeconds: Double = 0.0
        private set
    var completed: Boolean = false
        private set

    private var stableCount = 0
    private var lastTimestamp: Double? = null
    private var lastConfidentTimestamp: Double? = null

    val phase: RepPhase get() = if (isHolding) RepPhase.PEAKED else RepPhase.REST
    val repCount: Int get() = if (completed) 1 else 0

    fun process(value: Float?, confidence: Float, timestamp: Double): RepDetectorEvent? {
        val event = step(value, confidence, timestamp)
        lastTimestamp = timestamp
        return event
    }

    private fun step(value: Float?, confidence: Float, timestamp: Double): RepDetectorEvent? {
        if (value == null || confidence < config.minConfidence) {
            isHolding = false
            if (!isArmed) stableCount = 0
            val last = lastConfidentTimestamp
            if (last != null && timestamp - last > config.lostTimeout && isArmed) {
                isArmed = false
                stableCount = 0
                lastConfidentTimestamp = null
                return RepDetectorEvent.Disarmed
            }
            return null
        }
        lastConfidentTimestamp = timestamp
        val inBand = value >= thresholds.low && value <= thresholds.high

        if (!isArmed) {
            stableCount = if (inBand) stableCount + 1 else 0
            if (stableCount >= config.stableFrames) {
                isArmed = true
                isHolding = true
                return RepDetectorEvent.Armed
            }
            return null
        }
        if (completed) return null
        if (inBand) {
            val last = lastTimestamp
            if (isHolding && last != null) {
                heldSeconds += maxOf(0.0, minOf(timestamp - last, 0.5))
            }
            isHolding = true
            if (heldSeconds >= targetSeconds) {
                completed = true
                return RepDetectorEvent.RepCompleted(heldSeconds)
            }
        } else {
            isHolding = false
        }
        return null
    }
}
