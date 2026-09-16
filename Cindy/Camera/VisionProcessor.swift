import AVFoundation
import Foundation
import Vision

/// Runs the Vision requests on a frame and returns a `FrameObservation`.
///
/// The phone lies flat, so the athlete's "up" is unknown relative to the sensor.
/// Face and body-pose detection both prefer upright people, therefore each request
/// tries the orientation that last worked for it first and one alternative per
/// frame when that fails (round robin over the remaining orientations). The two
/// searches are independent: a face found sideways must not tilt the pose request.
final class VisionProcessor {
    /// Whether to run the face request (the signal for push-ups, pull-ups and plank).
    var detectFace: Bool
    /// Whether to run the (expensive) body-pose request (the signal for squats).
    var detectBodyPose: Bool

    private var faceSearch = OrientationSearch()
    private var poseSearch = OrientationSearch()

    init(detectFace: Bool = true, detectBodyPose: Bool = false) {
        self.detectFace = detectFace
        self.detectBodyPose = detectBodyPose
    }

    func process(_ sampleBuffer: CMSampleBuffer) -> FrameObservation? {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds
        return process(pixelBuffer: pixelBuffer, timestamp: timestamp)
    }

    func process(pixelBuffer: CVPixelBuffer, timestamp: TimeInterval) -> FrameObservation {
        var observation = FrameObservation(timestamp: timestamp)
        if detectFace, let hit = faceSearch.find({ detectFace(in: pixelBuffer, orientation: $0) }) {
            observation.face = hit.result
            observation.orientation = hit.orientation
        }
        if detectBodyPose, let hit = poseSearch.find({ detectPose(in: pixelBuffer, orientation: $0) }) {
            observation.pose = hit.result
            observation.poseOrientation = hit.orientation
        }
        return observation
    }

    // MARK: - Private

    private func detectFace(in pixelBuffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) -> FaceObservation? {
        let request = VNDetectFaceRectanglesRequest()
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: orientation, options: [:])
        do {
            try handler.perform([request])
        } catch {
            return nil
        }
        // Largest face wins: the athlete is the closest person to the camera.
        guard let best = request.results?.max(by: { $0.boundingBox.width * $0.boundingBox.height
            < $1.boundingBox.width * $1.boundingBox.height }) else { return nil }
        return FaceObservation(boundingBox: best.boundingBox, confidence: best.confidence)
    }

    private func detectPose(in pixelBuffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) -> BodyPoseObservation? {
        let request = VNDetectHumanBodyPoseRequest()
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: orientation, options: [:])
        do {
            try handler.perform([request])
        } catch {
            return nil
        }
        guard let body = request.results?.first else { return nil }
        var joints: [PoseJoint: PosePoint] = [:]
        let mapping: [(PoseJoint, VNHumanBodyPoseObservation.JointName)] = [
            (.nose, .nose), (.leftShoulder, .leftShoulder), (.rightShoulder, .rightShoulder),
            (.leftHip, .leftHip), (.rightHip, .rightHip)
        ]
        for (joint, name) in mapping {
            if let point = try? body.recognizedPoint(name), point.confidence > 0 {
                joints[joint] = PosePoint(x: Float(point.location.x), y: Float(point.location.y),
                                          confidence: point.confidence)
            }
        }
        // Without both shoulders the orientation is treated as a miss, so the search moves on.
        guard joints[.leftShoulder] != nil, joints[.rightShoulder] != nil else { return nil }
        var width = Float(CVPixelBufferGetWidth(pixelBuffer))
        var height = Float(CVPixelBufferGetHeight(pixelBuffer))
        if [.left, .right, .leftMirrored, .rightMirrored].contains(orientation) {
            swap(&width, &height)
        }
        let longer = max(width, height, 1)
        return BodyPoseObservation(joints: joints, xScale: width / longer, yScale: height / longer)
    }
}

/// Remembers which buffer orientation last produced a detection and probes one
/// alternative per frame after a miss.
struct OrientationSearch {
    /// Candidate orientations for the mirrored front camera buffer.
    static let orientations: [CGImagePropertyOrientation] = [.leftMirrored, .rightMirrored, .upMirrored, .downMirrored]

    private(set) var preferredIndex = 0
    private var fallbackCursor = 1

    mutating func find<T>(_ detect: (CGImagePropertyOrientation) -> T?) -> (result: T, orientation: CGImagePropertyOrientation)? {
        let orientations = Self.orientations
        let preferred = orientations[preferredIndex]
        if let result = detect(preferred) {
            return (result, preferred)
        }
        let alternativeIndex = nextFallbackIndex()
        let alternative = orientations[alternativeIndex]
        guard let result = detect(alternative) else { return nil }
        preferredIndex = alternativeIndex
        return (result, alternative)
    }

    private mutating func nextFallbackIndex() -> Int {
        let count = Self.orientations.count
        var index = fallbackCursor % count
        if index == preferredIndex {
            index = (index + 1) % count
        }
        fallbackCursor = index + 1
        return index
    }
}
