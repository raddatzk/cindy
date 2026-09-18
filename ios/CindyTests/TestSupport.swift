import Foundation
@testable import Cindy

/// Locates test fixtures in the test bundle.
final class FixtureLocator {
    static func url(_ name: String, extension ext: String = "csv") -> URL? {
        Bundle(for: FixtureLocator.self).url(forResource: name, withExtension: ext)
    }
}

/// Synthetic signal generator for detector tests.
struct SyntheticSignal {
    struct Sample {
        var t: TimeInterval
        var value: Float?
        var confidence: Float
    }

    var fps: Double = 30
    var rest: Float = 0.05
    var peak: Float = 0.20
    var noise: Float = 0

    /// `leadIn` seconds of rest, then `reps` half-sine cycles of `period` seconds with `gap` seconds of rest in between.
    func reps(_ reps: Int, period: TimeInterval = 1.6, gap: TimeInterval = 0.4, leadIn: TimeInterval = 1.0,
              leadOut: TimeInterval = 1.0) -> [Sample] {
        var samples: [Sample] = []
        var t: TimeInterval = 0
        var generator = SystemRandomNumberGenerator()
        func jitter() -> Float { noise == 0 ? 0 : Float.random(in: -noise...noise, using: &generator) }
        func emitRest(_ seconds: TimeInterval) {
            for _ in 0..<Int(seconds * fps) {
                samples.append(Sample(t: t, value: rest + jitter(), confidence: 0.95))
                t += 1 / fps
            }
        }
        emitRest(leadIn)
        for _ in 0..<reps {
            let n = Int(period * fps)
            for i in 0..<n {
                let phase = Float(i) / Float(n)
                let value = rest + (peak - rest) * sin(.pi * phase) + jitter()
                samples.append(Sample(t: t, value: value, confidence: 0.95))
                t += 1 / fps
            }
            emitRest(gap)
        }
        emitRest(leadOut)
        return samples
    }
}

extension Array where Element == SyntheticSignal.Sample {
    /// Multiplies every value, e.g. to simulate standing closer to the phone.
    func scaled(by factor: Float) -> [SyntheticSignal.Sample] {
        map { SyntheticSignal.Sample(t: $0.t, value: $0.value.map { $0 * factor }, confidence: $0.confidence) }
    }
}

/// Drops items to about `frameRate` fps and keeps their original timestamps, like a slower camera
/// (Galaxy A20e: 5–20 fps). After skipping `offset` leading items, an item is kept once a target
/// frame interval has passed since the last kept one, less half a 30 fps frame for the jitter of
/// the recorded timestamps (0.0333 / 0.0334 s).
func decimate<T>(_ items: [T], frameRate: Double, offset: Int = 0, timestamp: (T) -> TimeInterval) -> [T] {
    let interval = 1 / frameRate - 0.5 / 30
    var kept: [T] = []
    var last: TimeInterval?
    for item in items.dropFirst(offset) {
        let t = timestamp(item)
        if last == nil || t - (last ?? t) >= interval {
            kept.append(item)
            last = t
        }
    }
    return kept
}

extension Array where Element == SyntheticSignal.Sample {
    func decimated(frameRate: Double, offset: Int = 0) -> [SyntheticSignal.Sample] {
        decimate(self, frameRate: frameRate, offset: offset) { $0.t }
    }
}

extension Array where Element == FrameObservation {
    func decimated(frameRate: Double, offset: Int = 0) -> [FrameObservation] {
        decimate(self, frameRate: frameRate, offset: offset) { $0.timestamp }
    }
}

/// Parses CSV files written by `FrameLogger` (columns t, raw, confidence by default).
enum CSVSignalReplay {
    /// `column` / `confidenceColumn` pick another signal, e.g. `pose_shoulder_w` / `pose_conf`.
    static func load(_ url: URL, column: String = "raw", confidenceColumn: String = "confidence") throws
        -> [SyntheticSignal.Sample] {
        let text = try String(contentsOf: url, encoding: .utf8)
        var lines = text.split(separator: "\n").map(String.init)
        guard !lines.isEmpty else { return [] }
        let header = lines.removeFirst().split(separator: ",").map(String.init)
        guard let tIndex = header.firstIndex(of: "t"),
              let rawIndex = header.firstIndex(of: column),
              let confIndex = header.firstIndex(of: confidenceColumn) else { return [] }
        return lines.map { line in
            let fields = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            let t = Double(fields[tIndex]) ?? 0
            let raw = Float(fields[rawIndex])
            let conf = Float(fields[confIndex]) ?? 0
            return SyntheticSignal.Sample(t: t, value: raw, confidence: conf)
        }
    }

    /// Rebuilds full frames (face box, shoulder width, brightness, depth) so a replay also exercises
    /// `BodyEvidence` and the depth signal. `lumaOffset` simulates a brighter or darker scene.
    static func observations(_ url: URL, lumaOffset: Float = 0) throws -> [FrameObservation] {
        let text = try String(contentsOf: url, encoding: .utf8)
        var lines = text.split(separator: "\n").map(String.init)
        guard !lines.isEmpty else { return [] }
        let header = lines.removeFirst().split(separator: ",").map(String.init)
        return lines.map { line in
            let fields = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            func value(_ name: String) -> Float? {
                guard let index = header.firstIndex(of: name), index < fields.count else { return nil }
                return Float(fields[index])
            }
            var observation = FrameObservation(timestamp: TimeInterval(value("t") ?? 0))
            if let x = value("face_x"), let y = value("face_y"), let w = value("face_w"), let h = value("face_h") {
                observation.face = FaceObservation(boundingBox: CGRect(x: CGFloat(x), y: CGFloat(y),
                                                                       width: CGFloat(w), height: CGFloat(h)),
                                                   confidence: value("face_conf") ?? 1)
            }
            if let width = value("pose_shoulder_w") {
                let confidence = value("pose_shoulder_conf") ?? value("pose_conf") ?? 1
                observation.pose = BodyPoseObservation(joints: [
                    .leftShoulder: PosePoint(x: 0, y: 0, confidence: confidence),
                    .rightShoulder: PosePoint(x: width, y: 0, confidence: confidence),
                ])
            }
            if let luma = value("luma_mean") {
                observation.metrics = FrameMetrics(lumaMean: luma + lumaOffset, lumaCenter: value("luma_center"))
            }
            if let valid = value("depth_valid") {
                observation.depth = DepthMetrics(validFraction: valid, p05: value("depth_p05"), p10: value("depth_p10"),
                                                 median: value("depth_median"), centerMedian: value("depth_center"),
                                                 grid: (0..<9).map { value("depth_g\($0)") },
                                                 age: value("depth_age").map { TimeInterval($0) })
            }
            return observation
        }
    }

    /// Feeds frames between `from` and `to` through a calibration capture, like `CalibrationEngine`.
    static func calibrate(_ frames: [FrameObservation], from: TimeInterval, to: TimeInterval, exercise: Exercise,
                          source: SignalSource, config: SignalConfig = .default) -> ExerciseCalibration? {
        let pipeline = SignalPipeline(exercise: exercise, thresholds: .hardcoded(for: exercise), source: source,
                                      config: config)
        let analyzer = CalibrationAnalyzer(config: config, source: source)
        var trace: [CalibrationSample] = []
        for frame in frames where frame.timestamp >= from && frame.timestamp <= to {
            let output = pipeline.process(frame)
            if let value = output.smoothed, output.confidence >= config.minConfidence {
                trace.append(CalibrationSample(timestamp: frame.timestamp, value: value))
            }
            if let calibration = analyzer.evaluate(trace) { return calibration }
        }
        return nil
    }

    /// Counted and rejected reps of full frames through the pipeline.
    static func countReps(_ frames: [FrameObservation], exercise: Exercise, thresholds: RepThresholds,
                          source: SignalSource, config: SignalConfig = .default) -> (reps: [TimeInterval], rejected: Int) {
        let pipeline = SignalPipeline(exercise: exercise, thresholds: thresholds, source: source, config: config)
        var reps: [TimeInterval] = []
        var rejected = 0
        for frame in frames {
            switch pipeline.process(frame).event {
            case .repCompleted: reps.append(frame.timestamp)
            case .repRejected: rejected += 1
            default: break
            }
        }
        return (reps, rejected)
    }

    /// Runs the pipeline over the samples and returns the number of counted reps.
    static func countReps(_ samples: [SyntheticSignal.Sample], exercise: Exercise, thresholds: RepThresholds,
                          source: SignalSource = .face, config: SignalConfig = .default) -> Int {
        let pipeline = SignalPipeline(exercise: exercise, thresholds: thresholds, source: source, config: config)
        var reps = 0
        for sample in samples {
            let output = pipeline.process(value: sample.value, confidence: sample.confidence, timestamp: sample.t)
            if case .repCompleted = output.event { reps += 1 }
        }
        return reps
    }
}
