import Foundation
import Testing
@testable import Cindy

struct CalibrationAnalyzerTests {
    private func trace(_ samples: [SyntheticSignal.Sample]) -> [CalibrationSample] {
        samples.compactMap { sample in
            guard let value = sample.value, sample.confidence >= 0.5 else { return nil }
            return CalibrationSample(timestamp: sample.t, value: value)
        }
    }

    @Test func detectsOnePeakCycle() throws {
        let samples = SyntheticSignal(rest: 0.05, peak: 0.20).reps(1, period: 1.6, leadIn: 0.8, leadOut: 0.5)
        let analyzer = CalibrationAnalyzer(source: .face)
        let calibration = try #require(analyzer.evaluate(trace(samples)))
        #expect(calibration.direction == .peak)
        #expect(abs(calibration.minValue - 0.05) < 0.01)
        #expect(abs(calibration.maxValue - 0.20) < 0.01)
        #expect(calibration.low > calibration.minValue)
        #expect(calibration.high < calibration.maxValue)
        #expect(calibration.low < calibration.high)
        #expect(calibration.repDuration > 0.8 && calibration.repDuration < 2.0)
        #expect(abs(calibration.baseline - 0.05) < 0.01)
    }

    @Test func calibratesAtFiveFramesPerSecond() throws {
        // Galaxy A20e: half a second of rest holds three samples, not the five the 30 fps rule wants.
        let samples = SyntheticSignal(fps: 5, rest: 0.05, peak: 0.20).reps(1, period: 1.6, leadIn: 0.8, leadOut: 0.5)
        let calibration = try #require(CalibrationAnalyzer(source: .face).evaluate(trace(samples)))
        #expect(calibration.direction == .peak)
        #expect(abs(calibration.baseline - 0.05) < 0.01)
    }

    @Test func aPatchyBaselineAtThirtyFramesPerSecondIsStillRejected() {
        let samples = SyntheticSignal(rest: 0.05, peak: 0.20).reps(1, period: 1.6, leadIn: 0.8, leadOut: 0.5)
        // Only four confident samples in the first half second; the rest of the trace at full rate.
        let patchy = samples.enumerated().map { i, s in
            s.t <= 0.5 && i % 4 != 0 ? SyntheticSignal.Sample(t: s.t, value: s.value, confidence: 0) : s
        }
        #expect(CalibrationAnalyzer(source: .face).evaluate(trace(patchy)) == nil)
    }

    @Test func detectsTroughCycle() throws {
        let peak = SyntheticSignal(rest: 0.05, peak: 0.20).reps(1, leadIn: 0.8, leadOut: 0.5)
        let inverted = peak.map { SyntheticSignal.Sample(t: $0.t, value: $0.value.map { 0.25 - $0 }, confidence: $0.confidence) }
        let calibration = try #require(CalibrationAnalyzer(source: .face).evaluate(trace(inverted)))
        #expect(calibration.direction == .trough)
        #expect(abs(calibration.baseline - 0.20) < 0.01)
    }

    @Test func incompleteCycleIsNotAccepted() {
        // Cut the trace right after the peak: no return to the rest band.
        let samples = SyntheticSignal().reps(1, leadIn: 0.8, leadOut: 0)
        let peakIndex = samples.indices.max { (samples[$0].value ?? 0) < (samples[$1].value ?? 0) }!
        let cut = Array(samples[...peakIndex])
        let analyzer = CalibrationAnalyzer(source: .face)
        #expect(analyzer.evaluate(trace(cut)) == nil)
        #expect(analyzer.diagnose(trace(cut)) == .noReturn)
    }

    @Test func weakSignalIsDiagnosed() {
        let samples = SyntheticSignal(rest: 0.05, peak: 0.055).reps(1, leadIn: 0.8)
        let analyzer = CalibrationAnalyzer(source: .face)
        #expect(analyzer.evaluate(trace(samples)) == nil)
        #expect(analyzer.diagnose(trace(samples)) == .tooWeak)
    }

    @Test func noSamplesMeansNoFace() {
        let analyzer = CalibrationAnalyzer(source: .face)
        #expect(analyzer.diagnose([]) == .noFace)
        #expect(analyzer.evaluate([]) == nil)
    }

    @Test func tooFastCycleIsDiagnosed() {
        let samples = SyntheticSignal().reps(1, period: 0.2, leadIn: 0.8)
        let analyzer = CalibrationAnalyzer(source: .face)
        #expect(analyzer.evaluate(trace(samples)) == nil)
        if case .implausibleDuration(let duration) = analyzer.diagnose(trace(samples)) {
            #expect(duration < 0.5)
        } else {
            Issue.record("expected implausibleDuration")
        }
    }

    @Test func profileRoundTripsThroughJSON() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let store = CalibrationStore(url: directory.appendingPathComponent("calibration.json"))
        var profile = CalibrationProfile()
        #expect(profile.isComplete == false)
        for exercise in Exercise.allCases {
            profile.set(ExerciseCalibration(source: SignalConfig.default.source(for: exercise), minValue: 0.1, maxValue: 0.3, baseline: 0.1, low: 0.15,
                                            high: 0.25, direction: exercise.defaultRepDirection, repDuration: 1.2,
                                            calibratedAt: Date()), for: exercise)
        }
        #expect(profile.isComplete)
        try store.save(profile)
        let loaded = try #require(store.load())
        #expect(loaded.isComplete)
        #expect(loaded.calibration(for: .pullUp)?.direction == .trough)
        #expect(loaded.calibration(for: .squat)?.thresholds == RepThresholds(low: 0.15, high: 0.25, direction: .peak,
                                                                                baseline: 0.1))
    }

    @Test func calibrationsOnAnOldSignalSourceAreDropped() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let store = CalibrationStore(url: directory.appendingPathComponent("calibration.json"))
        var profile = CalibrationProfile()
        for exercise in Exercise.allCases {
            // Every exercise on the face, as squats were calibrated before they moved to body pose.
            profile.set(ExerciseCalibration(source: .face, minValue: 0.1, maxValue: 0.3, baseline: 0.1, low: 0.15,
                                            high: 0.25, direction: exercise.defaultRepDirection, repDuration: 1.2,
                                            calibratedAt: Date()), for: exercise)
        }
        try store.save(profile)
        let loaded = try #require(store.load())
        #expect(loaded.missingExercises(for: .cindy) == [.squat])
        #expect(loaded.calibration(for: .pushUp) != nil)
    }

    @Test func thePlankNeedsNoCalibration() {
        var plan = WorkoutPlan.cindy
        plan.setEnabled(.plank, true)
        var profile = CalibrationProfile()
        for exercise in WorkoutPlan.cindy.exercises {
            profile.set(ExerciseCalibration(source: SignalConfig.default.source(for: exercise), minValue: 0.1,
                                            maxValue: 0.3, baseline: 0.1, low: 0.15, high: 0.25,
                                            direction: exercise.defaultRepDirection, repDuration: 1.2,
                                            calibratedAt: Date()), for: exercise)
        }
        #expect(profile.missingExercises(for: plan).isEmpty)
        #expect(profile.isComplete(for: plan))
        // A plank calibration from before the timer is dropped on load.
        profile.set(ExerciseCalibration(source: SignalConfig.default.source(for: .plank), minValue: 0.1, maxValue: 0.3,
                                        baseline: 0.2, low: 0.15, high: 0.25, direction: .peak, repDuration: 3,
                                        calibratedAt: Date()), for: .plank)
        #expect(profile.removingOutdated(for: .default).calibration(for: .plank) == nil)
    }
}
