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
    /// Accumulated hold time (plank only).
    var heldSeconds: TimeInterval? = nil
}

/// Extractor → EMA smoothing → Schmitt-trigger rep detector for one exercise.
/// Not thread-safe; call `process` from a single queue.
final class SignalPipeline {
    let exercise: Exercise
    let source: SignalSource
    let thresholds: RepThresholds
    let config: SignalConfig

    private let extractor: SignalExtractor
    private var ema: EMAFilter
    private let detector: RepDetector?
    private let holdDetector: HoldDetector?

    /// `holdSeconds` switches the pipeline to the time-based hold detector (plank).
    init(exercise: Exercise, thresholds: RepThresholds, source: SignalSource? = nil, config: SignalConfig = .default,
         holdSeconds: TimeInterval? = nil) {
        self.exercise = exercise
        self.thresholds = thresholds
        self.config = config
        self.source = source ?? config.source(for: exercise)
        self.extractor = SignalExtractor(config: config)
        self.ema = EMAFilter(alpha: config.emaAlpha)
        if let holdSeconds {
            self.holdDetector = HoldDetector(thresholds: thresholds, targetSeconds: holdSeconds, config: config)
            self.detector = nil
        } else {
            self.detector = RepDetector(thresholds: thresholds, config: config)
            self.holdDetector = nil
        }
    }

    var isArmed: Bool { detector?.isArmed ?? holdDetector?.isArmed ?? false }
    var repCount: Int { detector?.repCount ?? holdDetector?.repCount ?? 0 }
    private var phase: RepPhase { detector?.phase ?? holdDetector?.phase ?? .rest }
    private var heldSeconds: TimeInterval? { holdDetector?.heldSeconds }

    private func detect(value: Float?, confidence: Float, timestamp: TimeInterval) -> RepDetectorEvent? {
        if let detector { return detector.process(value: value, confidence: confidence, timestamp: timestamp) }
        return holdDetector?.process(value: value, confidence: confidence, timestamp: timestamp)
    }

    func process(_ observation: FrameObservation) -> PipelineOutput {
        let sample = extractor.extract(observation, for: exercise, source: source)
        var smoothed: Float?
        if let raw = sample.value, sample.confidence >= config.minConfidence {
            smoothed = ema.update(raw)
        } else {
            smoothed = ema.value // hold the last value; the detector ignores this frame anyway
        }
        let event = detect(
            value: sample.confidence >= config.minConfidence ? smoothed : nil,
            confidence: sample.confidence,
            timestamp: observation.timestamp
        )
        return PipelineOutput(
            timestamp: observation.timestamp,
            exercise: exercise,
            source: source,
            raw: sample.value,
            smoothed: smoothed,
            confidence: sample.confidence,
            event: event,
            phase: phase,
            isArmed: isArmed,
            repCount: repCount,
            heldSeconds: heldSeconds
        )
    }

    /// Feeds an already extracted scalar (used by tests and CSV replay).
    func process(value: Float?, confidence: Float, timestamp: TimeInterval) -> PipelineOutput {
        var smoothed: Float?
        if let value, confidence >= config.minConfidence {
            smoothed = ema.update(value)
        } else {
            smoothed = ema.value
        }
        let event = detect(
            value: confidence >= config.minConfidence ? smoothed : nil,
            confidence: confidence,
            timestamp: timestamp
        )
        return PipelineOutput(
            timestamp: timestamp, exercise: exercise, source: source, raw: value, smoothed: smoothed,
            confidence: confidence, event: event, phase: phase, isArmed: isArmed,
            repCount: repCount, heldSeconds: heldSeconds
        )
    }
}
