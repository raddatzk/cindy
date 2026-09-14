import Foundation
import Testing
@testable import Cindy

struct RepDetectorTests {
    let thresholds = RepThresholds.from(min: 0.05, max: 0.20, direction: .peak)

    private func run(_ samples: [SyntheticSignal.Sample], thresholds: RepThresholds? = nil,
                     config: SignalConfig = .default) -> (reps: Int, rejected: Int, events: [RepDetectorEvent]) {
        let detector = RepDetector(thresholds: thresholds ?? self.thresholds, config: config)
        var reps = 0, rejected = 0
        var events: [RepDetectorEvent] = []
        for sample in samples {
            if let event = detector.process(value: sample.value, confidence: sample.confidence, timestamp: sample.t) {
                events.append(event)
                if case .repCompleted = event { reps += 1 }
                if case .repRejected = event { rejected += 1 }
            }
        }
        return (reps, rejected, events)
    }

    @Test func countsCleanReps() {
        let samples = SyntheticSignal().reps(10)
        let result = run(samples)
        #expect(result.reps == 10)
        #expect(result.rejected == 0)
        #expect(result.events.first == .armed)
    }

    @Test func thresholdRuleUsesMargin() {
        let t = RepThresholds.from(min: 0, max: 1, direction: .peak, margin: 0.25)
        #expect(t.low == 0.25)
        #expect(t.high == 0.75)
    }

    @Test func requiresStableFramesBeforeArming() {
        let detector = RepDetector(thresholds: thresholds)
        var armedAt: Int?
        for i in 0..<20 {
            let event = detector.process(value: 0.05, confidence: 1, timestamp: Double(i) / 30)
            if event == .armed { armedAt = i }
        }
        #expect(armedAt == SignalConfig.default.stableFrames - 1)
    }

    @Test func doesNotArmOutsideRestBand() {
        let detector = RepDetector(thresholds: thresholds)
        for i in 0..<30 {
            _ = detector.process(value: 0.19, confidence: 1, timestamp: Double(i) / 30)
        }
        #expect(detector.isArmed == false)
    }

    @Test func settlingFromAboveHighDoesNotCount() {
        // Signal starts above `high` (e.g. athlete still in push-up position when squats begin),
        // drops into the rest band, then performs one real rep.
        var samples: [SyntheticSignal.Sample] = []
        var t: TimeInterval = 0
        for _ in 0..<15 { samples.append(.init(t: t, value: 0.25, confidence: 1)); t += 1 / 30 }
        samples.append(contentsOf: SyntheticSignal().reps(1).map { .init(t: t + $0.t, value: $0.value, confidence: $0.confidence) })
        let result = run(samples)
        #expect(result.reps == 1)
    }

    @Test func rejectsTooFastReps() {
        let samples = SyntheticSignal().reps(3, period: 0.2, gap: 0.5)
        let result = run(samples)
        #expect(result.reps == 0)
        #expect(result.rejected == 3)
    }

    @Test func rejectsTooSlowReps() {
        let samples = SyntheticSignal().reps(1, period: 7, gap: 0.5)
        let result = run(samples)
        #expect(result.reps == 0)
        #expect(result.rejected == 1)
    }

    @Test func holdsStateDuringShortFaceLoss() {
        var samples = SyntheticSignal().reps(1)
        // Blank out the peak of the rep (~0.3 s) as if the face left the frame.
        let peakIndex = samples.indices.max { (samples[$0].value ?? 0) < (samples[$1].value ?? 0) }!
        for i in (peakIndex - 4)...(peakIndex + 4) {
            samples[i].value = nil
            samples[i].confidence = 0
        }
        let result = run(samples)
        #expect(result.reps == 1)
    }

    @Test func disarmsAfterLongLoss() {
        var samples = SyntheticSignal().reps(1, leadIn: 1.0)
        let lossFrames = Int(3.0 * 30)
        var t = samples.last!.t
        for _ in 0..<lossFrames {
            t += 1 / 30
            samples.append(.init(t: t, value: nil, confidence: 0))
        }
        let result = run(samples)
        #expect(result.reps == 1)
        #expect(result.events.last == .disarmed)
    }

    @Test func bounceWithoutReachingHighIsNotARep() {
        var samples: [SyntheticSignal.Sample] = []
        var t: TimeInterval = 0
        for _ in 0..<15 { samples.append(.init(t: t, value: 0.05, confidence: 1)); t += 1 / 30 }
        for _ in 0..<15 { samples.append(.init(t: t, value: 0.12, confidence: 1)); t += 1 / 30 } // between low and high
        for _ in 0..<15 { samples.append(.init(t: t, value: 0.05, confidence: 1)); t += 1 / 30 }
        #expect(run(samples).reps == 0)
    }

    @Test func troughDirectionCountsInvertedSignal() {
        // Pull-up style: rest is the high value, the rep dips below `low`.
        let peakSamples = SyntheticSignal(rest: 0.05, peak: 0.20).reps(5)
        let inverted = peakSamples.map { SyntheticSignal.Sample(t: $0.t, value: $0.value.map { 0.25 - $0 }, confidence: $0.confidence) }
        let troughThresholds = RepThresholds.from(min: 0.05, max: 0.20, direction: .trough)
        #expect(run(inverted, thresholds: troughThresholds).reps == 5)
    }

    @Test func lowConfidenceFramesAreIgnoredWhileArming() {
        let detector = RepDetector(thresholds: thresholds)
        for i in 0..<8 { _ = detector.process(value: 0.05, confidence: 1, timestamp: Double(i) / 30) }
        _ = detector.process(value: 0.05, confidence: 0.1, timestamp: 8.0 / 30)
        for i in 9..<17 { _ = detector.process(value: 0.05, confidence: 1, timestamp: Double(i) / 30) }
        #expect(detector.isArmed == false)
        _ = detector.process(value: 0.05, confidence: 1, timestamp: 17.0 / 30)
        _ = detector.process(value: 0.05, confidence: 1, timestamp: 18.0 / 30)
        #expect(detector.isArmed == true)
    }
}
