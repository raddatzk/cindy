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

    @Test func armsByDurationAtLowFrameRates() {
        // Galaxy A20e at 5 fps: ten frames would take 2 s. Three samples spanning 0.3 s (the
        // duration of ten frames at 30 fps) arm instead.
        func armedAt(_ interval: TimeInterval) -> Int? {
            let detector = RepDetector(thresholds: thresholds)
            return (0..<10).first { detector.process(value: 0.05, confidence: 1, timestamp: Double($0) * interval) == .armed }
        }
        #expect(armedAt(0.2) == 2)
        // 10 fps: four samples span 0.3 s. Recorded timestamps are rounded (0.0999 s apart), which the tolerance absorbs.
        #expect(armedAt(0.0999) == 3)
        // 15 fps: five samples span only 0.267 s, the sixth arms.
        #expect(armedAt(1.0 / 15) == 5)
    }

    @Test func aSingleSlowFrameDoesNotArm() {
        let detector = RepDetector(thresholds: thresholds)
        #expect(detector.process(value: 0.05, confidence: 1, timestamp: 0) == nil)
        #expect(detector.process(value: 0.05, confidence: 1, timestamp: 1) == nil) // 1 s of rest, but only two samples
        #expect(detector.process(value: 0.05, confidence: 1, timestamp: 1.1) == .armed)
    }

    @Test func countsCleanRepsAtFiveFps() {
        let result = run(SyntheticSignal(fps: 5).reps(10))
        #expect(result.reps == 10)
        #expect(result.rejected == 0)
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

    // MARK: Relative thresholds

    @Test func relativeThresholdsRescaleToMeasuredRest() {
        let relative = RepThresholds(low: 0.0875, high: 0.1625, direction: .peak, baseline: 0.05)
        // Twice as much face area as during calibration: rest 0.10, reps up to 0.40.
        let samples = SyntheticSignal(rest: 0.10, peak: 0.40).reps(5)
        let detector = RepDetector(thresholds: relative)
        var reps = 0
        for sample in samples {
            if case .repCompleted = detector.process(value: sample.value, confidence: sample.confidence, timestamp: sample.t) {
                reps += 1
            }
        }
        #expect(reps == 5)
        #expect(abs(detector.activeThresholds.low - 0.175) < 1e-4)
        #expect(abs(detector.activeThresholds.high - 0.325) < 1e-4)
        #expect(run(samples, thresholds: RepThresholds(low: 0.0875, high: 0.1625, direction: .peak)).reps == 0)
    }

    @Test func relativeThresholdsDoNotArmFarFromBaseline() {
        // Standing right at the phone: seven times the calibrated rest area, perfectly still.
        let detector = RepDetector(thresholds: RepThresholds(low: 0.0875, high: 0.1625, direction: .peak, baseline: 0.05))
        for i in 0..<60 {
            _ = detector.process(value: 0.35, confidence: 1, timestamp: Double(i) / 30)
        }
        #expect(detector.isArmed == false)
    }

    @Test func relativeTroughThresholdsRescale() {
        let peakSamples = SyntheticSignal(rest: 0.05, peak: 0.20).reps(5)
        // Pull-up style at 1.5× the calibrated area: rest 0.30, dips to 0.075.
        let inverted = peakSamples.map { SyntheticSignal.Sample(t: $0.t, value: $0.value.map { (0.25 - $0) * 1.5 }, confidence: $0.confidence) }
        let trough = RepThresholds(low: 0.0875, high: 0.1625, direction: .trough, baseline: 0.20)
        #expect(run(inverted, thresholds: trough).reps == 5)
    }

    @Test func restTrackingRecoversFromArmingWhileWalkingAway() {
        // Arms at about 1.55× the calibrated rest while still walking away, then settles at
        // the calibrated rest: without tracking `high` (0.25) stays out of reach of the 0.20 peaks.
        let relative = RepThresholds(low: 0.0875, high: 0.1625, direction: .peak, baseline: 0.05)
        var samples: [SyntheticSignal.Sample] = []
        var t: TimeInterval = 0
        for i in 0..<60 {
            let value = max(0.05, 0.08 - 0.0006 * Float(i))
            samples.append(.init(t: t, value: value, confidence: 1))
            t += 1 / 30
        }
        samples.append(contentsOf: SyntheticSignal(rest: 0.05, peak: 0.20).reps(5, leadIn: 2).map {
            .init(t: t + $0.t, value: $0.value, confidence: $0.confidence)
        })
        #expect(run(samples, thresholds: relative).reps == 5)
        var untracked = SignalConfig.default
        untracked.restTrackingAlpha = 0
        #expect(run(samples, thresholds: relative, config: untracked).reps == 0)
    }

    @Test func openCycleDisarmsRelativeThresholds() {
        // Stepping closer and staying there: the cycle never closes, so the detector re-arms at the new rest.
        let detector = RepDetector(thresholds: RepThresholds(low: 0.0875, high: 0.1625, direction: .peak, baseline: 0.05))
        var events: [RepDetectorEvent] = []
        var t: TimeInterval = 0
        for value in Array(repeating: Float(0.05), count: 15) + Array(repeating: Float(0.09), count: 300) {
            if let event = detector.process(value: value, confidence: 1, timestamp: t) { events.append(event) }
            t += 1 / 30
        }
        #expect(events.filter { $0 == .armed }.count == 2)
        #expect(events.contains(.disarmed))
        #expect(abs(detector.activeThresholds.low - 0.0875 * 1.8) < 1e-3)
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
