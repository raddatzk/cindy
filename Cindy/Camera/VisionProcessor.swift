import AVFoundation
import Foundation
import Vision

/// Runs the Vision requests on a frame and returns a `FrameObservation`.
///
/// The phone lies flat, so the athlete's "up" is unknown relative to the sensor.
/// Face detection prefers upright faces, therefore the processor tries the
/// orientation that last worked first and one alternative per frame when that
/// fails (round robin over the remaining orientations). Steady state costs one
/// face request per frame.
final class VisionProcessor {
    /// Whether to run the (expensive) body-pose request too.
    var detectBodyPose: Bool

    /// Candidate orientations for the mirrored front camera buffer.
    private let orientations: [CGImagePropertyOrientation] = [.leftMirrored, .rightMirrored, .upMirrored, .downMirrored]
    private var preferredIndex = 0
    private var fallbackCursor = 1

    init(detectBodyPose: Bool = false) {
        self.detectBodyPose = detectBodyPose
    }

    func process(_ sampleBuffer: CMSampleBuffer) -> FrameObservation? {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds
        return process(pixelBuffer: pixelBuffer, timestamp: timestamp)
    }

    func process(pixelBuffer: CVPixelBuffer, timestamp: TimeInterval) -> FrameObservation {
        var observation = FrameObservation(timestamp: timestamp)

        let preferred = orientations[preferredIndex]
        if let face = detectFace(in: pixelBuffer, orientation: preferred) {
            observation.face = face
            observation.orientation = preferred
        } else {
            let alternativeIndex = nextFallbackIndex()
            let alternative = orientations[alternativeIndex]
            if let face = detectFace(in: pixelBuffer, orientation: alternative) {
                observation.face = face
                observation.orientation = alternative
                preferredIndex = alternativeIndex
            }
        }

        if detectBodyPose {
            observation.pose = detectPose(in: pixelBuffer, orientation: observation.orientation ?? preferred)
        }
        return observation
    }

    // MARK: - Private

    private func nextFallbackIndex() -> Int {
        var index = fallbackCursor % orientations.count
        if index == preferredIndex {
            index = (index + 1) % orientations.count
        }
        fallbackCursor = index + 1
        return index
    }

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
        guard !joints.isEmpty else { return nil }
        return BodyPoseObservation(joints: joints)
    }
}
