import Foundation

enum RepDetectorEvent: Equatable, Sendable {
    /// The signal has been stable in the rest band for `stableFrames` frames; reps are counted from now on.
    case armed
    /// The person was lost for longer than `lostTimeout`; the detector needs to re-arm.
    case disarmed
    /// A full, plausible cycle was completed.
    case repCompleted(duration: TimeInterval)
    /// A full cycle was completed but its duration was outside the plausible range.
    case repRejected(duration: TimeInterval)
}

/// Where the signal is within the current cycle (normalised to "peak" polarity).
enum RepPhase: String, Sendable {
    /// In the rest band (below `low`).
    case rest
    /// Left the rest band but has not reached `high` yet.
    case leaving
    /// Crossed `high`; the rep completes once the signal returns below `low`.
    case peaked
}

/// Schmitt-trigger rep counter for one exercise.
///
/// A rep is a full cycle: leave the rest band, cross the far threshold, return
/// into the rest band. Frames with low confidence are ignored and the state is
/// held. The counter is armed only after `stableFrames` consecutive confident
/// frames inside the rest band, which also prevents the first "settling"
/// movement after an exercise switch from being counted.
final class RepDetector {
    let thresholds: RepThresholds
    let config: SignalConfig

    private(set) var isArmed = false
    private(set) var phase: RepPhase = .rest
    private(set) var repCount = 0

    private var stableCount = 0
    private var cycleStart: TimeInterval?
    private var lastConfidentTimestamp: TimeInterval?

    init(thresholds: RepThresholds, config: SignalConfig = .default) {
        self.thresholds = thresholds
        self.config = config
    }

    /// Feeds one (already smoothed) sample.
    func process(value: Float?, confidence: Float, timestamp: TimeInterval) -> RepDetectorEvent? {
        guard let value, confidence >= config.minConfidence else {
            return handleLowConfidence(at: timestamp)
        }
        lastConfidentTimestamp = timestamp

        let (v, low, high) = normalised(value)

        if !isArmed {
            stableCount = v < low ? stableCount + 1 : 0
            if stableCount >= config.stableFrames {
                isArmed = true
                phase = .rest
                cycleStart = nil
                return .armed
            }
            return nil
        }

        switch phase {
        case .rest:
            if v >= low {
                phase = .leaving
                cycleStart = timestamp
            }
        case .leaving:
            if v >= high {
                phase = .peaked
            } else if v < low {
                phase = .rest      // bounced back without a full rep
                cycleStart = nil
            }
        case .peaked:
            if v < low {
                phase = .rest
                let duration = timestamp - (cycleStart ?? timestamp)
                cycleStart = nil
                if duration >= config.minRepDuration && duration <= config.maxRepDuration {
                    repCount += 1
                    return .repCompleted(duration: duration)
                }
                return .repRejected(duration: duration)
            }
        }
        return nil
    }

    func reset() {
        isArmed = false
        phase = .rest
        stableCount = 0
        cycleStart = nil
        lastConfidentTimestamp = nil
    }

    // MARK: - Private

    /// Maps the sample and thresholds into "peak" polarity so one state machine handles both directions.
    private func normalised(_ value: Float) -> (value: Float, low: Float, high: Float) {
        switch thresholds.direction {
        case .peak:
            return (value, thresholds.low, thresholds.high)
        case .trough:
            return (-value, -thresholds.high, -thresholds.low)
        }
    }

    private func handleLowConfidence(at timestamp: TimeInterval) -> RepDetectorEvent? {
        if !isArmed {
            stableCount = 0
        }
        guard let last = lastConfidentTimestamp else { return nil }
        if timestamp - last > config.lostTimeout {
            let wasArmed = isArmed
            reset()
            return wasArmed ? .disarmed : nil
        }
        return nil // hold state
    }
}
