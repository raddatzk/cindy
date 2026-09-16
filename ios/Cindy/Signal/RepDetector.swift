import Foundation

enum RepDetectorEvent: Equatable, Sendable {
    /// The signal has been stable in the rest band for `stableFrames` reference frames; reps are counted from now on.
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
/// movement after an exercise switch from being counted. Frames are counted at the
/// reference frame rate (`FrameTiming.covers`): at 5 fps three samples spanning at least
/// 0.3 s arm, where ten samples (2 s) would outlast the pause between two squats.
///
/// With relative thresholds the rest level is the mean of those frames; the
/// thresholds are adapted to it (scaled or shifted), and the frames must lie inside
/// the rest band of the adapted thresholds. Scaled thresholds let the level follow
/// lower values while armed at rest (`restTrackingAlpha` per reference frame), and a
/// relative cycle open longer than `maxRepDuration` disarms, so walking away after arming
/// and stepping closer are both corrected. `cycleValidator` can veto a completed cycle.
final class RepDetector {
    let thresholds: RepThresholds
    let config: SignalConfig

    private(set) var isArmed = false
    private(set) var phase: RepPhase = .rest
    private(set) var repCount = 0
    /// The thresholds the state machine compares against: `thresholds`, adapted
    /// to the measured rest level once armed if they are relative.
    private(set) var activeThresholds: RepThresholds
    /// Asked when a cycle of plausible duration completes; `false` turns it into `.repRejected`.
    var cycleValidator: (() -> Bool)?

    private struct RestSample {
        var value: Float
        var timestamp: TimeInterval
    }

    /// Consecutive confident samples while unarmed, newest last: the shortest run of newest samples
    /// that covers `stableFrames` (at most `stableFrames` samples).
    private var restWindow: [RestSample] = []
    /// Rest level the active thresholds are scaled to (relative thresholds only).
    private var restLevel: Float?
    private var cycleStart: TimeInterval?
    private var lastConfidentTimestamp: TimeInterval?
    /// Timestamp of the previous frame of any confidence, for the frame interval.
    private var lastTimestamp: TimeInterval?

    init(thresholds: RepThresholds, config: SignalConfig = .default) {
        self.thresholds = thresholds
        self.config = config
        self.activeThresholds = thresholds
    }

    /// Feeds one (already smoothed) sample.
    func process(value: Float?, confidence: Float, timestamp: TimeInterval) -> RepDetectorEvent? {
        let frameInterval = lastTimestamp.map { timestamp - $0 }
        lastTimestamp = timestamp
        guard let value, confidence >= config.minConfidence else {
            return handleLowConfidence(at: timestamp)
        }
        lastConfidentTimestamp = timestamp

        if !isArmed {
            guard let armed = armedThresholds(adding: value, at: timestamp) else { return nil }
            activeThresholds = armed
            restLevel = armed.isRelative ? armed.baseline : nil
            restWindow.removeAll()
            isArmed = true
            phase = .rest
            cycleStart = nil
            return .armed
        }

        let (v, low, high) = normalised(value, activeThresholds)

        switch phase {
        case .rest:
            if v >= low {
                phase = .leaving
                cycleStart = timestamp
            } else {
                trackRest(value, frameInterval: frameInterval)
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
                if duration >= config.minRepDuration && duration <= config.maxRepDuration,
                   cycleValidator?() ?? true {
                    repCount += 1
                    return .repCompleted(duration: duration)
                }
                return .repRejected(duration: duration)
            }
        }
        if thresholds.isRelative, phase != .rest, let cycleStart, timestamp - cycleStart > config.maxRepDuration {
            reset()
            return .disarmed
        }
        return nil
    }

    func reset() {
        isArmed = false
        phase = .rest
        activeThresholds = thresholds
        restWindow.removeAll()
        restLevel = nil
        cycleStart = nil
        lastConfidentTimestamp = nil
    }

    // MARK: - Private

    /// Lets the rest level follow values on the rest side of it (relative thresholds only).
    private func trackRest(_ value: Float, frameInterval: TimeInterval?) {
        guard let rest = restLevel, thresholds.adaptation == .scale, config.restTrackingAlpha > 0 else { return }
        let restSide = thresholds.direction == .peak ? value < rest : value > rest
        guard restSide else { return }
        let next = rest + FrameTiming.alpha(config.restTrackingAlpha, frameInterval: frameInterval) * (value - rest)
        restLevel = next
        activeThresholds = thresholds.adapted(toRest: next)
    }

    /// Adds an unarmed sample; returns the thresholds to arm with once the newest samples
    /// cover `stableFrames` and form a plausible rest.
    private func armedThresholds(adding value: Float, at timestamp: TimeInterval) -> RepThresholds? {
        restWindow.append(RestSample(value: value, timestamp: timestamp))
        // Keep the shortest run of newest samples that still covers the debounce; at 30 fps that is
        // exactly the last `stableFrames` samples.
        while restWindow.count > 1,
              coversStableFrames(count: restWindow.count - 1, from: restWindow[1].timestamp, to: timestamp) {
            restWindow.removeFirst()
        }
        guard coversStableFrames(count: restWindow.count, from: restWindow[0].timestamp, to: timestamp) else {
            return nil
        }

        var candidate = thresholds
        if let baseline = thresholds.baseline, thresholds.isRelative {
            let rest = restWindow.reduce(0) { $0 + $1.value } / Float(restWindow.count)
            switch thresholds.adaptation {
            case .scale:
                let scale = rest / baseline
                let tolerance = max(config.restBaselineTolerance, 1)
                guard scale <= tolerance, scale >= 1 / tolerance else { return nil }
            case .shift:
                guard abs(rest - baseline) <= config.restShiftTolerance else { return nil }
            }
            candidate = thresholds.adapted(toRest: rest)
        }
        let allInRest = restWindow.allSatisfy { sample in
            let (v, low, _) = normalised(sample.value, candidate)
            return v < low
        }
        return allInRest ? candidate : nil
    }

    private func coversStableFrames(count: Int, from first: TimeInterval, to last: TimeInterval) -> Bool {
        FrameTiming.covers(frames: max(config.stableFrames, 1), count: count, duration: last - first,
                           minCount: FrameTiming.minStableSamples)
    }

    /// Maps the sample and thresholds into "peak" polarity so one state machine handles both directions.
    private func normalised(_ value: Float, _ thresholds: RepThresholds) -> (value: Float, low: Float, high: Float) {
        switch thresholds.direction {
        case .peak:
            return (value, thresholds.low, thresholds.high)
        case .trough:
            return (-value, -thresholds.high, -thresholds.low)
        }
    }

    private func handleLowConfidence(at timestamp: TimeInterval) -> RepDetectorEvent? {
        if !isArmed {
            restWindow.removeAll()
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
