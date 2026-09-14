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

/// Parses CSV files written by `FrameLogger` (columns t, raw, confidence).
enum CSVSignalReplay {
    static func load(_ url: URL) throws -> [SyntheticSignal.Sample] {
        let text = try String(contentsOf: url, encoding: .utf8)
        var lines = text.split(separator: "\n").map(String.init)
        guard !lines.isEmpty else { return [] }
        let header = lines.removeFirst().split(separator: ",").map(String.init)
        guard let tIndex = header.firstIndex(of: "t"),
              let rawIndex = header.firstIndex(of: "raw"),
              let confIndex = header.firstIndex(of: "confidence") else { return [] }
        return lines.map { line in
            let fields = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            let t = Double(fields[tIndex]) ?? 0
            let raw = Float(fields[rawIndex])
            let conf = Float(fields[confIndex]) ?? 0
            return SyntheticSignal.Sample(t: t, value: raw, confidence: conf)
        }
    }

    /// Runs the pipeline over the samples and returns the number of counted reps.
    static func countReps(_ samples: [SyntheticSignal.Sample], exercise: Exercise, thresholds: RepThresholds,
                          config: SignalConfig = .default) -> Int {
        let pipeline = SignalPipeline(exercise: exercise, thresholds: thresholds, source: .face, config: config)
        var reps = 0
        for sample in samples {
            let output = pipeline.process(value: sample.value, confidence: sample.confidence, timestamp: sample.t)
            if case .repCompleted = output.event { reps += 1 }
        }
        return reps
    }
}
