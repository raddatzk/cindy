import Foundation

/// One smoothed, confident sample of the calibration trace.
struct CalibrationSample: Equatable, Sendable {
    var timestamp: TimeInterval
    var value: Float
}

enum CalibrationFailure: Equatable, Sendable {
    /// Not enough confident frames to even measure a baseline (face signal).
    case noFace
    /// Same for the body-pose signal, or no body at all next to the brightness signal.
    case noPerson
    /// The signal never swung far enough from the baseline.
    case tooWeak
    /// The brightness barely changed: too little contrast between the athlete and the background.
    case lowContrast
    /// The signal moved but did not come back to the start position.
    case noReturn
    /// A cycle was found but was too fast or too slow.
    case implausibleDuration(TimeInterval)

    var message: String {
        switch self {
        case .noFace:
            return L("No face detected. Check light and position: your face has to be right above the phone.")
        case .noPerson:
            return L("No person detected. Check light and position: your shoulders have to be above the camera and in the picture.")
        case .tooWeak:
            return L("The movement was too weak in the signal. Keep your face right above the phone; for pull-ups put the phone directly under the bar.")
        case .lowContrast:
            return L("The picture hardly got darker during the squat. Keep your toes right behind the phone; a bright ceiling light above you helps, a bright top on a bright ceiling does not.")
        case .noReturn:
            return L("The movement was detected but the start position was not reached again. Please start and end in the start position.")
        case .implausibleDuration(let duration):
            return L("The rep took \(duration.formattedDecimal(1)) s. Plausible is \((0.5).formattedDecimal(1))–\((5.0).formattedDecimal(0)) s. Please repeat at a normal pace.")
        }
    }
}

/// Pure analysis of a calibration trace. Fed with the growing trace after
/// every frame; returns a calibration once one full cycle is visible.
///
/// Algorithm: median of the first `calibrationBaselineDuration` seconds is the
/// rest baseline. The direction is whichever side of the baseline the signal
/// swung further to. Thresholds follow the standard margin rule on the
/// observed extremes. The cycle is complete when the signal has crossed the far
/// threshold and afterwards re-entered the rest band.
struct CalibrationAnalyzer: Sendable {
    let config: SignalConfig
    let source: SignalSource

    init(config: SignalConfig = .default, source: SignalSource) {
        self.config = config
        self.source = source
    }

    /// Returns a calibration once the trace contains one plausible full cycle.
    func evaluate(_ trace: [CalibrationSample]) -> ExerciseCalibration? {
        guard let stats = stats(of: trace), stats.excursionOK else { return nil }
        guard let cycle = findCycle(trace, stats: stats) else { return nil }
        guard cycle.duration >= config.minRepDuration, cycle.duration <= config.maxRepDuration else { return nil }
        return ExerciseCalibration(
            source: source,
            minValue: stats.min,
            maxValue: stats.max,
            baseline: stats.baseline,
            low: stats.thresholds.low,
            high: stats.thresholds.high,
            direction: stats.direction,
            repDuration: cycle.duration,
            calibratedAt: Date()
        )
    }

    /// Hold calibration (plank): after `calibrationHoldDuration` seconds of
    /// confident samples the band is the observed min/max widened by
    /// `calibrationHoldBandMargin` of the mean (at least the observed spread).
    func evaluateHold(_ trace: [CalibrationSample]) -> ExerciseCalibration? {
        guard let first = trace.first, let last = trace.last,
              last.timestamp - first.timestamp >= config.calibrationHoldDuration,
              trace.count >= config.calibrationBaselineMinSamples else { return nil }
        let values = trace.map(\.value)
        let mean = values.reduce(0, +) / Float(values.count)
        let observedMin = values.min() ?? mean
        let observedMax = values.max() ?? mean
        let halfBand = Swift.max((observedMax - observedMin) / 2, config.calibrationHoldBandMargin * abs(mean))
        return ExerciseCalibration(
            source: source, minValue: observedMin, maxValue: observedMax, baseline: mean,
            low: mean - halfBand, high: mean + halfBand, direction: .peak,
            repDuration: config.calibrationHoldDuration, calibratedAt: Date()
        )
    }

    func diagnoseHold(_ trace: [CalibrationSample]) -> CalibrationFailure {
        trace.count < config.calibrationBaselineMinSamples ? noSubject : .noReturn
    }

    /// Best explanation for why `evaluate` has not succeeded (used on timeout).
    func diagnose(_ trace: [CalibrationSample]) -> CalibrationFailure {
        guard let stats = stats(of: trace) else { return noSubject }
        guard stats.excursionOK else { return source == .brightness ? .lowContrast : .tooWeak }
        guard let cycle = findCycle(trace, stats: stats) else { return .noReturn }
        return .implausibleDuration(cycle.duration)
    }

    // MARK: - Internals

    private var noSubject: CalibrationFailure { source == .face ? .noFace : .noPerson }

    struct TraceStats {
        var baseline: Float
        var min: Float
        var max: Float
        var direction: RepDirection
        var excursion: Float
        var excursionOK: Bool
        var thresholds: RepThresholds
        var baselineEndIndex: Int
    }

    func stats(of trace: [CalibrationSample]) -> TraceStats? {
        guard let first = trace.first else { return nil }
        let baselineEnd = first.timestamp + config.calibrationBaselineDuration
        let baselineSamples = trace.prefix { $0.timestamp <= baselineEnd }
        // A count at the reference frame rate: a slower camera fits fewer samples in the window.
        let minSamples = FrameTiming.scaledCount(config.calibrationBaselineMinSamples,
                                                 timestamps: trace.map(\.timestamp),
                                                 floor: FrameTiming.minStableSamples)
        guard baselineSamples.count >= minSamples else { return nil }
        let baseline = median(baselineSamples.map(\.value))
        let values = trace.map(\.value)
        let minValue = values.min() ?? baseline
        let maxValue = values.max() ?? baseline
        let up = maxValue - baseline
        let down = baseline - minValue
        let required: Float
        switch source {
        case .face: required = Swift.max(config.calibrationMinRelativeExcursion * abs(baseline), 1e-4)
        case .pose: required = config.calibrationMinAbsoluteExcursion
        case .brightness: required = config.calibrationMinBrightnessExcursion
        }
        let direction: RepDirection
        let thresholds: RepThresholds
        if source == .brightness {
            // Brightness overshoots past rest when the athlete stands back up, so the side
            // the signal left first is the rep, and the thresholds hang off the rest level.
            direction = firstExcursion(of: values, from: baseline, reach: Swift.max(up, down) / 2) ?? .trough
            thresholds = RepThresholds.fromRest(baseline: baseline, extreme: direction == .peak ? maxValue : minValue,
                                                direction: direction, leave: config.restAnchoredLeave,
                                                peak: config.restAnchoredPeak)
        } else {
            direction = up >= down ? .peak : .trough
            thresholds = RepThresholds.from(min: minValue, max: maxValue, direction: direction,
                                            margin: config.thresholdMargin)
        }
        let excursion = direction == .peak ? up : down
        return TraceStats(
            baseline: baseline, min: minValue, max: maxValue, direction: direction,
            excursion: excursion, excursionOK: excursion >= required, thresholds: thresholds,
            baselineEndIndex: baselineSamples.count
        )
    }

    struct Cycle {
        var startIndex: Int
        var extremeIndex: Int
        var endIndex: Int
        var duration: TimeInterval
    }

    /// Finds departure → extreme → return using the derived thresholds.
    func findCycle(_ trace: [CalibrationSample], stats: TraceStats) -> Cycle? {
        let values = trace.map(\.value)
        let isPeak = stats.direction == .peak
        // Index of the extreme (max for peak, min for trough).
        guard let extremeIndex = isPeak
            ? values.indices.max(by: { values[$0] < values[$1] })
            : values.indices.min(by: { values[$0] < values[$1] }) else { return nil }
        let inRest: (Float) -> Bool = isPeak
            ? { $0 < stats.thresholds.low }
            : { $0 > stats.thresholds.high }
        // Return: first sample after the extreme that is back in the rest band.
        guard let endIndex = ((extremeIndex + 1)..<values.count).first(where: { inRest(values[$0]) }) else {
            return nil
        }
        // Departure: last sample before the extreme that was still in the rest band.
        let startIndex = (0..<extremeIndex).reversed().first(where: { inRest(values[$0]) }) ?? 0
        let duration = trace[endIndex].timestamp - trace[startIndex].timestamp
        return Cycle(startIndex: startIndex, extremeIndex: extremeIndex, endIndex: endIndex, duration: duration)
    }

    /// Direction of the first sample that moves `reach` away from the baseline.
    private func firstExcursion(of values: [Float], from baseline: Float, reach: Float) -> RepDirection? {
        guard reach > 0, let first = values.first(where: { abs($0 - baseline) >= reach }) else { return nil }
        return first > baseline ? .peak : .trough
    }

    private func median(_ values: [Float]) -> Float {
        let sorted = values.sorted()
        guard !sorted.isEmpty else { return 0 }
        let mid = sorted.count / 2
        return sorted.count % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid]
    }
}
