import Foundation
import Testing
@testable import Cindy

/// Squats and push-ups on the TrueDepth distance, replayed from device recordings (2026-09-17)
/// through calibration and the full pipeline.
struct DepthSignalTests {
    /// Squat bottoms: 3 s still, 5 squats looking down at the phone, 3 s still, 5 looking ahead.
    static let squatBottoms: [TimeInterval] = [5.3, 7.2, 9.2, 11.2, 13.3, 18.1, 19.9, 21.9, 23.7, 25.6]
    /// Push-up bottoms; the recording starts in the top position.
    static let pushUpBottoms: [TimeInterval] = [2.6, 4.5, 6.3, 8.0, 9.8]

    private func frames(_ name: String) throws -> [FrameObservation] {
        try CSVSignalReplay.observations(#require(FixtureLocator.url(name)))
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

    @Test func squatsCountWhicheverWayTheAthleteLooks() throws {
        let recording = try frames("recorded_squats_10_depth")
        // Calibrated on the still stand and the first squat.
        let calibration = try #require(CSVSignalReplay.calibrate(recording, from: 1.5, to: 7.0, exercise: .squat,
                                                                 source: .depth))
        #expect(calibration.direction == .trough)
        // Counted from the end of the still stand, as a workout would start.
        let workout = recording.filter { $0.timestamp >= 1.5 }
        let result = CSVSignalReplay.countReps(workout, exercise: .squat, thresholds: calibration.thresholds,
                                               source: .depth)
        let match = matchedBottoms(result.reps, Self.squatBottoms)
        #expect(match.hits == 10)
        #expect(match.extra == 0)
        #expect(result.rejected == 0)
    }

    @Test func pushUpsCountAlthoughTheBottomIsTooCloseToMeasure() throws {
        let recording = try frames("recorded_pushups_5_depth")
        let calibration = try #require(CSVSignalReplay.calibrate(recording, from: 0, to: 4.0, exercise: .pushUp,
                                                                 source: .depth))
        #expect(calibration.direction == .trough)
        let result = CSVSignalReplay.countReps(recording, exercise: .pushUp, thresholds: calibration.thresholds,
                                               source: .depth)
        let match = matchedBottoms(result.reps, Self.pushUpBottoms)
        #expect(match.hits == 5)
        #expect(match.extra == 0)
    }

    @Test(arguments: [15.0, 10.0])
    func depthRepsCountTheSameAtLowerFrameRates(frameRate: Double) throws {
        let squats = try frames("recorded_squats_10_depth")
        let squatCalibration = try #require(CSVSignalReplay.calibrate(squats, from: 1.5, to: 7.0, exercise: .squat,
                                                                      source: .depth))
        let pushUps = try frames("recorded_pushups_5_depth")
        let pushUpCalibration = try #require(CSVSignalReplay.calibrate(pushUps, from: 0, to: 4.0, exercise: .pushUp,
                                                                       source: .depth))
        for offset in 0..<Int(30 / frameRate) {
            let label = "at \(frameRate) fps, offset \(offset)"
            let squatResult = CSVSignalReplay.countReps(
                squats.filter { $0.timestamp >= 1.5 }.decimated(frameRate: frameRate, offset: offset),
                exercise: .squat, thresholds: squatCalibration.thresholds, source: .depth)
            let squatMatch = matchedBottoms(squatResult.reps, Self.squatBottoms)
            #expect(squatMatch.hits == 10, "squat hits \(label)")
            #expect(squatMatch.extra == 0, "squat extra \(label)")
            let pushUpResult = CSVSignalReplay.countReps(pushUps.decimated(frameRate: frameRate, offset: offset),
                                                         exercise: .pushUp, thresholds: pushUpCalibration.thresholds,
                                                         source: .depth)
            let pushUpMatch = matchedBottoms(pushUpResult.reps, Self.pushUpBottoms)
            #expect(pushUpMatch.hits == 5, "push-up hits \(label)")
            #expect(pushUpMatch.extra == 0, "push-up extra \(label)")
        }
    }

    @Test func mostlyEmptyMapsUseTheNearestDepth() {
        let extractor = SignalExtractor()
        var observation = FrameObservation(timestamp: 0)
        observation.depth = DepthMetrics(validFraction: 0.06, p05: 0.16, p10: 0.17, median: 1.7, centerMedian: nil)
        #expect(extractor.depthSignal(observation).value == 0.16)
        observation.depth = DepthMetrics(validFraction: 0.94, p05: 0.42, p10: 0.44, median: 0.9, centerMedian: 0.5)
        #expect(extractor.depthSignal(observation).value == 0.9)
        observation.depth?.age = 1
        #expect(extractor.depthSignal(observation).value == nil) // stale map
    }
}
