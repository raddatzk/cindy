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

    // Real recordings (iPhone flat on the floor, 2026-09-14). The thresholds are the
    // calibration the app was running with, recovered from the phase changes in the logs.
    static let pushUpCalibration = RepThresholds(low: 0.1616, high: 0.374, direction: .peak, baseline: 0.0554)
    static let squatCalibration = RepThresholds(low: 0.00752, high: 0.01178, direction: .peak, baseline: 0.00539)

    @Test func recordedPushUpsCountThree() throws {
        // Face lost for up to 8 frames at the bottom of each rep; one rep was counted before the recording started.
        let samples = try CSVSignalReplay.load(#require(FixtureLocator.url("recorded_pushups_3_face")))
        #expect(CSVSignalReplay.countReps(samples, exercise: .pushUp, thresholds: Self.pushUpCalibration) == 3)
    }

    @Test func recordedSquatsCountFour() throws {
        // Walks away from the phone, 4 squats, 3.5 s without a face (disarms), walks back to stop the recording.
        let samples = try CSVSignalReplay.load(#require(FixtureLocator.url("recorded_squats_4_face")))
        #expect(CSVSignalReplay.countReps(samples, exercise: .squat, thresholds: Self.squatCalibration) == 4)
    }

    // Shoulder width from body pose, recorded with the face request still steering the pose
    // orientation: squats 1–4 looking down, 5–8 looking ahead. From 12 s the face is only found
    // rotated and the pose breaks with it, so only the first four squats carry a usable signal.
    static let squatPoseCalibration = RepThresholds(low: 0.3375, high: 0.5125, direction: .peak, baseline: 0.25)

    @Test(arguments: [0.6, 1.0, 1.5] as [Float])
    func recordedSquatShoulderWidthCountsTheUsableSquats(scale: Float) throws {
        let url = try #require(FixtureLocator.url("recorded_squats_8_mixed_gaze"))
        let samples = try CSVSignalReplay.load(url, column: "pose_shoulder_w", confidenceColumn: "pose_conf").scaled(by: scale)
        // Arms while still walking away from the phone (rest ≈ 0.40); tracking the rest down recovers the reps.
        #expect(CSVSignalReplay.countReps(samples, exercise: .squat, thresholds: Self.squatPoseCalibration,
                                          source: .pose) == 4)
    }

    @Test(arguments: [15.0, 10.0, 5.0])
    func recordedFixturesCountTheSameAtLowerFrameRates(frameRate: Double) throws {
        // Galaxy A20e: 5–20 fps. Rows are dropped, their timestamps kept, and every phase of the
        // decimation (which rows survive) has to count what the full rate counts.
        let synthetic = try CSVSignalReplay.load(#require(FixtureLocator.url("synthetic_pushups_10")))
        let syntheticThresholds = RepThresholds.from(min: 0.05, max: 0.20, direction: .peak)
        let pushUps = try CSVSignalReplay.load(#require(FixtureLocator.url("recorded_pushups_3_face")))
        let squats = try CSVSignalReplay.load(#require(FixtureLocator.url("recorded_squats_4_face")))
        let shoulders = try CSVSignalReplay.load(#require(FixtureLocator.url("recorded_squats_8_mixed_gaze")),
                                                 column: "pose_shoulder_w", confidenceColumn: "pose_conf")
        for offset in 0..<Int(30 / frameRate) {
            let label = "at \(frameRate) fps, offset \(offset)"
            #expect(CSVSignalReplay.countReps(synthetic.decimated(frameRate: frameRate, offset: offset),
                                              exercise: .pushUp, thresholds: syntheticThresholds) == 10,
                    "synthetic push-ups \(label)")
            #expect(CSVSignalReplay.countReps(pushUps.decimated(frameRate: frameRate, offset: offset),
                                              exercise: .pushUp, thresholds: Self.pushUpCalibration) == 3,
                    "push-ups \(label)")
            #expect(CSVSignalReplay.countReps(squats.decimated(frameRate: frameRate, offset: offset),
                                              exercise: .squat, thresholds: Self.squatCalibration) == 4,
                    "squats \(label)")
            // The shoulder width keeps its count down to 8 fps. Below that the 5-sample pose median
            // spans a second, half a squat, and flattens the reps (0–2 of 4 at 5 fps); a shorter window
            // lets the pose outliers through (5 of 4). The median stays a sample count, and pose is no
            // exercise's default source, so 5 fps is not asserted here.
            if frameRate >= 10 {
                #expect(CSVSignalReplay.countReps(shoulders.decimated(frameRate: frameRate, offset: offset),
                                                  exercise: .squat, thresholds: Self.squatPoseCalibration,
                                                  source: .pose) == 4,
                        "shoulder width \(label)")
            }
        }
    }

    @Test(arguments: [0.5, 0.7, 1.3, 1.5, 2.0] as [Float])
    func relativeThresholdsSurviveADifferentDistance(areaScale: Float) throws {
        // Face area scales with the inverse square of the distance to the phone.
        let pushUps = try CSVSignalReplay.load(#require(FixtureLocator.url("recorded_pushups_3_face"))).scaled(by: areaScale)
        let squats = try CSVSignalReplay.load(#require(FixtureLocator.url("recorded_squats_4_face"))).scaled(by: areaScale)
        #expect(CSVSignalReplay.countReps(pushUps, exercise: .pushUp, thresholds: Self.pushUpCalibration) == 3)
        #expect(CSVSignalReplay.countReps(squats, exercise: .squat, thresholds: Self.squatCalibration) == 4)
    }

    @Test func absoluteThresholdsMissSquatsWhenStandingCloser() throws {
        var absolute = Self.squatCalibration
        absolute.baseline = nil
        let squats = try CSVSignalReplay.load(#require(FixtureLocator.url("recorded_squats_4_face"))).scaled(by: 1.5)
        #expect(CSVSignalReplay.countReps(squats, exercise: .squat, thresholds: absolute) == 0)
    }

    @Test func restAdaptationFollowsTheSignal() {
        // Face area and shoulder width scale with the distance, brightness shifts; landmark heights stay absolute.
        #expect(SignalPipeline(exercise: .squat, thresholds: Self.squatCalibration, source: .face).thresholds.adaptation == .scale)
        #expect(SignalPipeline(exercise: .squat, thresholds: Self.squatCalibration, source: .pose).thresholds.isRelative)
        #expect(SignalPipeline(exercise: .squat, thresholds: Self.squatCalibration, source: .brightness).thresholds.adaptation == .shift)
        #expect(SignalPipeline(exercise: .pushUp, thresholds: Self.pushUpCalibration, source: .pose).thresholds.isRelative == false)
        var faceY = SignalConfig.default
        faceY.squatFaceYWeight = 0.1
        #expect(SignalPipeline(exercise: .squat, thresholds: Self.squatCalibration, source: .face, config: faceY)
            .thresholds.isRelative == false)
    }

    @Test func squatsUseBrightnessByDefault() {
        #expect(SignalConfig.default.source(for: .squat) == .brightness)
        #expect(SignalConfig.default.source(for: .pushUp) == .face)
    }

    @Test func medianRemovesSingleFrameOutliers() {
        var median = MedianFilter(window: 5)
        let output = ([0.25, 0.26, 0.04, 0.25, 0.27, 0.26] as [Float]).map { median.update($0) }
        #expect(output.min() ?? 0 >= 0.25) // the 0.04 frame never gets through
        #expect(output.last == 0.26)
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
        // Squats: shoulder width, with the weaker shoulder's confidence.
        let squat = extractor.extract(observation, for: .squat, source: .pose)
        #expect(abs((squat.value ?? 0) - 0.2828427) < 1e-5)
        #expect(squat.confidence == 0.6)
    }
}
