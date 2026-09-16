import Foundation
import Testing
@testable import Cindy

/// Squats on the image brightness, replayed from device recordings (2026-09-14) with the
/// face and pose next to it, so calibration, rest shift and `BodyEvidence` all run.
struct BrightnessSquatTests {
    /// Bottoms of the squats, read off the recording protocol: 3 s still, 5 squats looking
    /// ahead, 3 s still, 5 squats looking down at the phone.
    static let cleanBottoms: [TimeInterval] = [5.9, 8.1, 10.2, 12.3, 14.5, 19.6, 21.6, 23.8, 26.0, 28.4]
    /// Mixed gaze: 1–4 looking down, 5–8 looking ahead.
    static let mixedBottoms: [TimeInterval] = [4.3, 6.1, 8.2, 10.2, 12.5, 14.5, 16.5, 18.5]

    private func frames(_ name: String, lumaOffset: Float = 0) throws -> [FrameObservation] {
        try CSVSignalReplay.observations(#require(FixtureLocator.url(name)), lumaOffset: lumaOffset)
    }

    /// Each counted rep must land within 1.6 s after a distinct bottom.
    private func matchedBottoms(_ reps: [TimeInterval], _ bottoms: [TimeInterval]) -> (hits: Int, extra: Int) {
        var used = Set<Int>()
        var extra = 0
        for rep in reps {
            if let index = bottoms.indices.first(where: { !used.contains($0) && rep >= bottoms[$0] - 0.2
                && rep <= bottoms[$0] + 1.6 }) {
                used.insert(index)
            } else {
                extra += 1
            }
        }
        return (used.count, extra)
    }

    @Test(arguments: [-0.06, 0, 0.06] as [Float])
    func cleanRecordingCountsAllTenSquats(lumaOffset: Float) throws {
        // Calibrated on the still stand and the first squat looking ahead; the offset shifts the
        // whole workout (not the calibration) brighter or darker.
        let calibration = try #require(CSVSignalReplay.calibrate(try frames("recorded_squats_10_clean"),
                                                                 from: 3.4, to: 7.4, exercise: .squat,
                                                                 source: .brightness))
        #expect(calibration.direction == .trough)
        let result = CSVSignalReplay.countReps(try frames("recorded_squats_10_clean", lumaOffset: lumaOffset),
                                               exercise: .squat, thresholds: calibration.thresholds,
                                               source: .brightness)
        let match = matchedBottoms(result.reps, Self.cleanBottoms)
        #expect(match.hits == 10)
        #expect(match.extra == 0)
        #expect(result.rejected == 0)
    }

    @Test func mixedGazeRecordingCountsAllEightSquats() throws {
        let recording = try frames("recorded_squats_8_mixed_gaze")
        let calibration = try #require(CSVSignalReplay.calibrate(recording, from: 3.0, to: 5.6, exercise: .squat,
                                                                 source: .brightness))
        let result = CSVSignalReplay.countReps(recording, exercise: .squat, thresholds: calibration.thresholds,
                                               source: .brightness)
        let match = matchedBottoms(result.reps, Self.mixedBottoms)
        #expect(match.hits == 8)
        #expect(match.extra == 0)
    }

    @Test(arguments: [15.0, 10.0, 5.0])
    func brightnessSquatsCountTheSameAtLowerFrameRates(frameRate: Double) throws {
        // Galaxy A20e: 5–20 fps. Every phase of the decimation has to find the same squats. Calibrated
        // at the full rate: the calibration capture wants `calibrationBaselineMinSamples` (5) in its
        // first 0.5 s, which it does not get below 10 fps.
        let clean = try frames("recorded_squats_10_clean")
        let cleanCalibration = try #require(CSVSignalReplay.calibrate(clean, from: 3.4, to: 7.4, exercise: .squat,
                                                                      source: .brightness))
        let mixed = try frames("recorded_squats_8_mixed_gaze")
        let mixedCalibration = try #require(CSVSignalReplay.calibrate(mixed, from: 3.0, to: 5.6, exercise: .squat,
                                                                      source: .brightness))
        for offset in 0..<Int(30 / frameRate) {
            let label = "at \(frameRate) fps, offset \(offset)"
            let cleanResult = CSVSignalReplay.countReps(clean.decimated(frameRate: frameRate, offset: offset),
                                                        exercise: .squat, thresholds: cleanCalibration.thresholds,
                                                        source: .brightness)
            let cleanMatch = matchedBottoms(cleanResult.reps, Self.cleanBottoms)
            #expect(cleanMatch.hits == 10, "clean hits \(label)")
            #expect(cleanMatch.extra == 0, "clean extra \(label)")
            #expect(cleanResult.rejected == 0, "clean rejected \(label)")
            let mixedResult = CSVSignalReplay.countReps(mixed.decimated(frameRate: frameRate, offset: offset),
                                                        exercise: .squat, thresholds: mixedCalibration.thresholds,
                                                        source: .brightness)
            let mixedMatch = matchedBottoms(mixedResult.reps, Self.mixedBottoms)
            #expect(mixedMatch.hits == 8, "mixed hits \(label)")
            #expect(mixedMatch.extra == 0, "mixed extra \(label)")
        }
    }

    @Test func vanishingCountsByDurationAtLowFrameRates() {
        func supports(fps: Double, missing: Int) -> Bool {
            var evidence = BodyEvidence()
            let face = FaceObservation(boundingBox: CGRect(x: 0.4, y: 0.4, width: 0.08, height: 0.08), confidence: 0.9)
            var t: TimeInterval = 0
            for _ in 0..<5 {
                evidence.update(FrameObservation(timestamp: t, face: face), cycleActive: false)
                t += 1 / fps
            }
            for _ in 0..<missing {
                evidence.update(FrameObservation(timestamp: t), cycleActive: true)
                t += 1 / fps
            }
            evidence.update(FrameObservation(timestamp: t, face: face), cycleActive: true)
            return evidence.supportsCycle
        }
        // 30 fps: five missing frames, as always.
        #expect(!supports(fps: 30, missing: 4))
        #expect(supports(fps: 30, missing: 5))
        // 5 fps: two missing samples 0.2 s apart outlast five reference frames (0.133 s); one sample is not a run.
        #expect(!supports(fps: 5, missing: 1))
        #expect(supports(fps: 5, missing: 2))
    }

    @Test func darkeningWithoutAMovingBodyIsRejected() {
        // A cloud passes while the athlete stands still: brightness dips like a squat,
        // but face and pose stay put.
        let thresholds = RepThresholds(low: 0.44, high: 0.47, direction: .trough, baseline: 0.49, adaptation: .shift)
        let pipeline = SignalPipeline(exercise: .squat, thresholds: thresholds, source: .brightness)
        let still = BodyPoseObservation(joints: [
            .leftShoulder: PosePoint(x: 0, y: 0, confidence: 0.7),
            .rightShoulder: PosePoint(x: 0.14, y: 0, confidence: 0.7),
        ])
        let face = FaceObservation(boundingBox: CGRect(x: 0.4, y: 0.4, width: 0.08, height: 0.08), confidence: 0.9)
        var events: [RepDetectorEvent] = []
        for sample in SyntheticSignal(rest: 0.49, peak: 0.40).reps(3) {
            var frame = FrameObservation(timestamp: sample.t, face: face, pose: still)
            frame.metrics = FrameMetrics(lumaMean: sample.value)
            if let event = pipeline.process(frame).event { events.append(event) }
        }
        #expect(events.contains(.armed))
        #expect(!events.contains { if case .repCompleted = $0 { return true } else { return false } })
        #expect(events.filter { if case .repRejected = $0 { return true } else { return false } }.count == 3)
    }

    @Test func darkeningWithAGrowingFaceCounts() {
        let thresholds = RepThresholds(low: 0.44, high: 0.47, direction: .trough, baseline: 0.49, adaptation: .shift)
        let pipeline = SignalPipeline(exercise: .squat, thresholds: thresholds, source: .brightness)
        var reps = 0
        for sample in SyntheticSignal(rest: 0.49, peak: 0.40).reps(3) {
            // The face grows as the brightness falls (looking down, coming closer).
            let depth = CGFloat((0.49 - (sample.value ?? 0.49)) / 0.09)
            let side = 0.08 + 0.1 * depth
            var frame = FrameObservation(timestamp: sample.t,
                                         face: FaceObservation(boundingBox: CGRect(x: 0.4, y: 0.4, width: side,
                                                                                   height: side), confidence: 0.9))
            frame.metrics = FrameMetrics(lumaMean: sample.value)
            if case .repCompleted = pipeline.process(frame).event { reps += 1 }
        }
        #expect(reps == 3)
    }

    @Test func calibrationFollowsTheFirstExcursionPastAnOvershoot() {
        // Darker first (the squat), then brighter than rest while standing up: still a trough.
        var samples: [CalibrationSample] = []
        var t: TimeInterval = 0
        func add(_ value: Float, frames: Int) {
            for _ in 0..<frames { samples.append(CalibrationSample(timestamp: t, value: value)); t += 1.0 / 30 }
        }
        add(0.50, frames: 20)
        for i in 0..<20 { add(0.50 - 0.04 * sin(Float(i) / 19 * .pi), frames: 1) }
        for i in 0..<15 { add(0.50 + 0.05 * sin(Float(i) / 14 * .pi), frames: 1) }
        add(0.50, frames: 15)
        let calibration = CalibrationAnalyzer(source: .brightness).evaluate(samples)
        #expect(calibration?.direction == .trough)
        // Rest-anchored: leave at 30 % and peak at 70 % of the 0.04 dip.
        #expect(abs((calibration?.high ?? 0) - 0.488) < 0.002)
        #expect(abs((calibration?.low ?? 0) - 0.472) < 0.002)
    }

    @Test func tooLittleDarkeningIsLowContrast() {
        var samples: [CalibrationSample] = []
        for i in 0..<90 {
            let dip: Float = (30..<50).contains(i) ? 0.008 : 0
            samples.append(CalibrationSample(timestamp: Double(i) / 30, value: 0.5 - dip))
        }
        #expect(CalibrationAnalyzer(source: .brightness).diagnose(samples) == .lowContrast)
    }

    @Test func shiftedThresholdsMoveWithTheRestLevel() {
        let thresholds = RepThresholds(low: 0.44, high: 0.47, direction: .trough, baseline: 0.49, adaptation: .shift)
        let adapted = thresholds.adapted(toRest: 0.55)
        #expect(abs(adapted.low - 0.50) < 1e-5)
        #expect(abs(adapted.high - 0.53) < 1e-5)
        // Arms at a rest 0.06 brighter, but not at the phone (0.2 brighter, beyond `restShiftTolerance`).
        let detector = RepDetector(thresholds: thresholds)
        for i in 0..<20 { _ = detector.process(value: 0.69, confidence: 1, timestamp: Double(i) / 30) }
        #expect(detector.isArmed == false)
        for i in 20..<40 { _ = detector.process(value: 0.55, confidence: 1, timestamp: Double(i) / 30) }
        #expect(detector.isArmed)
    }
}
