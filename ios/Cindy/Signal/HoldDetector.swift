import Foundation

/// Time-based counterpart of `RepDetector` for planks.
///
/// The signal has to stay inside the calibrated band [low, high]. While it does
/// (and the face is seen), held time accumulates from the frame timestamps.
/// Leaving the band or losing the face pauses the clock without resetting it.
/// Emits `.repCompleted` once the accumulated time reaches `targetSeconds`.
final class HoldDetector {
    let thresholds: RepThresholds
    let targetSeconds: TimeInterval
    let config: SignalConfig

    private(set) var isArmed = false
    private(set) var isHolding = false
    private(set) var heldSeconds: TimeInterval = 0
    private(set) var completed = false

    private var stableCount = 0
    private var lastTimestamp: TimeInterval?
    private var lastConfidentTimestamp: TimeInterval?

    init(thresholds: RepThresholds, targetSeconds: TimeInterval, config: SignalConfig = .default) {
        self.thresholds = thresholds
        self.targetSeconds = targetSeconds
        self.config = config
    }

    var phase: RepPhase { isHolding ? .peaked : .rest }
    var repCount: Int { completed ? 1 : 0 }

    func process(value: Float?, confidence: Float, timestamp: TimeInterval) -> RepDetectorEvent? {
        defer { lastTimestamp = timestamp }
        guard let value, confidence >= config.minConfidence else {
            isHolding = false
            if !isArmed { stableCount = 0 }
            if let last = lastConfidentTimestamp, timestamp - last > config.lostTimeout, isArmed {
                isArmed = false
                stableCount = 0
                lastConfidentTimestamp = nil
                return .disarmed
            }
            return nil
        }
        lastConfidentTimestamp = timestamp
        let inBand = value >= thresholds.low && value <= thresholds.high

        if !isArmed {
            stableCount = inBand ? stableCount + 1 : 0
            if stableCount >= config.stableFrames {
                isArmed = true
                isHolding = true
                return .armed
            }
            return nil
        }
        guard !completed else { return nil }
        if inBand {
            if isHolding, let last = lastTimestamp {
                heldSeconds += max(0, min(timestamp - last, 0.5))
            }
            isHolding = true
            if heldSeconds >= targetSeconds {
                completed = true
                return .repCompleted(duration: heldSeconds)
            }
        } else {
            isHolding = false
        }
        return nil
    }
}
