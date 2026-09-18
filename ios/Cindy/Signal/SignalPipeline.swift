import Foundation

/// Result of pushing one frame through extractor → EMA → rep detector.
struct PipelineOutput: Equatable, Sendable {
    var timestamp: TimeInterval
    var exercise: Exercise
    var source: SignalSource
    var raw: Float?
    var smoothed: Float?
    var confidence: Float
    var event: RepDetectorEvent?
    var phase: RepPhase
    var isArmed: Bool
    var repCount: Int
    /// Thresholds the detector currently compares against (rescaled once armed if relative).
    var thresholds: RepThresholds? = nil
}

/// Extractor → (median for pose and depth) → EMA smoothing → Schmitt-trigger rep detector for one exercise;
/// brightness reps additionally need `BodyEvidence`.
/// Not thread-safe; call `process` from a single queue.
final class SignalPipeline {
    let exercise: Exercise
    let source: SignalSource
    let thresholds: RepThresholds
    let config: SignalConfig

    private let extractor: SignalExtractor
    private var ema: EMAFilter
    private var median: MedianFilter?
    private let detector: RepDetector
    private var evidence: BodyEvidence?
    /// Timestamp of the previous frame of any confidence; the EMA scales to this interval.
    private var lastTimestamp: TimeInterval?

    init(exercise: Exercise, thresholds: RepThresholds, source: SignalSource? = nil, config: SignalConfig = .default) {
        let source = source ?? config.source(for: exercise)
        let extractor = SignalExtractor(config: config)
        var thresholds = thresholds
        if let adaptation = extractor.restAdaptation(for: exercise, source: source) {
            thresholds.adaptation = adaptation
        } else {
            thresholds.baseline = nil
        }
        self.exercise = exercise
        self.thresholds = thresholds
        self.config = config
        self.source = source
        self.extractor = extractor
        self.ema = EMAFilter(alpha: config.emaAlpha)
        self.median = source == .pose || source == .depth ? MedianFilter(window: config.poseMedianWindow) : nil
        self.detector = RepDetector(thresholds: thresholds, config: config)
        if source == .brightness {
            evidence = BodyEvidence(config: config)
            detector.cycleValidator = { [weak self] in
                guard let evidence = self?.evidence, evidence.hasObservations else { return true }
                return evidence.supportsCycle
            }
        }
    }

    var isArmed: Bool { detector.isArmed }
    var repCount: Int { detector.repCount }
    private var phase: RepPhase { detector.phase }

    func process(_ observation: FrameObservation) -> PipelineOutput {
        evidence?.update(observation, cycleActive: phase != .rest)
        let sample = extractor.extract(observation, for: exercise, source: source)
        return process(value: sample.value, confidence: sample.confidence, timestamp: observation.timestamp)
    }

    /// Feeds an already extracted scalar (also used by tests and CSV replay).
    func process(value: Float?, confidence: Float, timestamp: TimeInterval) -> PipelineOutput {
        // The interval to the previous camera frame, not to the previous EMA update: a frame without
        // a face holds the EMA like before, only a lower frame rate moves it further per sample.
        let frameInterval = lastTimestamp.map { timestamp - $0 }
        lastTimestamp = timestamp
        var smoothed: Float?
        if let value, confidence >= config.minConfidence {
            let filtered = median?.update(value) ?? value
            smoothed = ema.update(filtered, frameInterval: frameInterval)
        } else {
            smoothed = ema.value // hold the last value; the detector ignores this frame anyway
        }
        let event = detector.process(
            value: confidence >= config.minConfidence ? smoothed : nil,
            confidence: confidence,
            timestamp: timestamp
        )
        return PipelineOutput(
            timestamp: timestamp, exercise: exercise, source: source, raw: value, smoothed: smoothed,
            confidence: confidence, event: event, phase: phase, isArmed: isArmed,
            repCount: repCount, thresholds: detector.activeThresholds
        )
    }
}
