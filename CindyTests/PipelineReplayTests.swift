import Foundation
import Testing
@testable import Cindy

/// Runs recorded/synthetic CSV traces (FrameLogger format) through the full
/// extractor → EMA → detector chain. Drop real recordings from the debug mode
/// into `Fixtures/` and add a case here.
struct PipelineReplayTests {
    @Test func syntheticPushUpsCountTen() throws {
        let url = try #require(FixtureLocator.url("synthetic_pushups_10"))
        let samples = try CSVSignalReplay.load(url)
        #expect(samples.count > 500)
        let thresholds = RepThresholds.from(min: 0.05, max: 0.20, direction: .peak)
        let reps = CSVSignalReplay.countReps(samples, exercise: .pushUp, thresholds: thresholds)
        #expect(reps == 10)
    }

    @Test func smoothingReducesNoise() {
        let noisy = SyntheticSignal(noise: 0.01).reps(5)
        let thresholds = RepThresholds.from(min: 0.05, max: 0.20, direction: .peak)
        #expect(CSVSignalReplay.countReps(noisy, exercise: .pushUp, thresholds: thresholds) == 5)
    }

    @Test func faceSignalUsesBoundingBoxArea() {
        let extractor = SignalExtractor()
        let face = FaceObservation(boundingBox: CGRect(x: 0.1, y: 0.2, width: 0.4, height: 0.5), confidence: 0.9)
        let observation = FrameObservation(timestamp: 0, face: face)
        let sample = extractor.extract(observation, for: .pushUp, source: .face)
        #expect(sample.value != nil)
        #expect(abs((sample.value ?? 0) - 0.2) < 1e-6)
        #expect(sample.confidence == 0.9)
        #expect(extractor.extract(FrameObservation(timestamp: 0), for: .pushUp, source: .face).value == nil)
    }

    @Test func poseSignalPicksJointPerExercise() {
        let extractor = SignalExtractor()
        let pose = BodyPoseObservation(joints: [
            .nose: PosePoint(x: 0.5, y: 0.9, confidence: 0.8),
            .leftShoulder: PosePoint(x: 0.4, y: 0.7, confidence: 0.7),
            .rightShoulder: PosePoint(x: 0.6, y: 0.5, confidence: 0.6),
            .leftHip: PosePoint(x: 0.4, y: 0.3, confidence: 0.5),
        ])
        let observation = FrameObservation(timestamp: 0, pose: pose)
        #expect(extractor.extract(observation, for: .pushUp, source: .pose).value == 0.9)
        #expect(extractor.extract(observation, for: .pullUp, source: .pose).value == 0.6)
        #expect(extractor.extract(observation, for: .pullUp, source: .pose).confidence == 0.6)
        #expect(extractor.extract(observation, for: .squat, source: .pose).value == 0.3)
    }
}
