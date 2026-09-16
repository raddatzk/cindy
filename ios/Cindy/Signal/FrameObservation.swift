import Foundation
import CoreGraphics
import ImageIO

/// Face bounding box in Vision's normalised coordinates (origin bottom-left, 0…1).
struct FaceObservation: Equatable, Sendable {
    var boundingBox: CGRect
    var confidence: Float

    var area: Float { Float(boundingBox.width * boundingBox.height) }
    var centerY: Float { Float(boundingBox.midY) }
    var centerX: Float { Float(boundingBox.midX) }
}

enum PoseJoint: String, CaseIterable, Sendable {
    case nose, leftShoulder, rightShoulder, leftHip, rightHip
}

struct PosePoint: Equatable, Sendable {
    var x: Float
    var y: Float
    var confidence: Float
}

/// Selected body-pose landmarks in normalised coordinates (origin bottom-left).
struct BodyPoseObservation: Equatable, Sendable {
    var joints: [PoseJoint: PosePoint]
    /// Width and height of the oriented image relative to its longer side, so distances
    /// between joints have the same unit on both axes and in every orientation.
    var xScale: Float = 1
    var yScale: Float = 1

    subscript(joint: PoseJoint) -> PosePoint? { joints[joint] }

    /// Mean y of the given joints, weighted only by presence; confidence = min of the used joints.
    func meanY(of jointsToUse: [PoseJoint]) -> (y: Float, confidence: Float)? {
        let points = jointsToUse.compactMap { joints[$0] }
        guard !points.isEmpty else { return nil }
        let y = points.map(\.y).reduce(0, +) / Float(points.count)
        let confidence = points.map(\.confidence).min() ?? 0
        return (y, confidence)
    }

    var noseY: Float? { joints[.nose]?.y }
    /// Distance between the shoulders in normalised image units; grows as the athlete gets closer.
    var shoulderWidth: Float? { shoulderWidthSample?.value }

    /// Shoulder width with the confidence of the weaker shoulder.
    var shoulderWidthSample: (value: Float, confidence: Float)? {
        guard let left = joints[.leftShoulder], let right = joints[.rightShoulder] else { return nil }
        return (hypot((left.x - right.x) * xScale, (left.y - right.y) * yScale), min(left.confidence, right.confidence))
    }
    var shoulderY: Float? { meanY(of: [.leftShoulder, .rightShoulder])?.y }
    var hipY: Float? { meanY(of: [.leftHip, .rightHip])?.y }
    var overallConfidence: Float { joints.values.map(\.confidence).max() ?? 0 }
}

/// Image brightness: the squat signal (a closer body covers more of the bright ceiling),
/// measured when the brightness source runs and always in the debug recorder.
struct FrameMetrics: Equatable, Sendable {
    /// Mean luma of the whole frame (0…1).
    var lumaMean: Float?
    /// Mean luma of the central half (width and height) of the frame.
    var lumaCenter: Float?
}

/// Distances from the TrueDepth depth map, in metres. Debug recorder only: it tells whether
/// anything that moves (legs under the pull-up bar, not just a face) shows up in the depth.
struct DepthMetrics: Equatable, Sendable {
    /// Share of sampled pixels with a finite, positive depth (0…1).
    var validFraction: Float
    /// 5th and 10th percentile of the valid depths: the nearest thing in view, robust to single pixels.
    var p05: Float?
    var p10: Float?
    var median: Float?
    /// Median of the central half (width and height) of the map.
    var centerMedian: Float?
    /// Medians of a 3 × 3 grid, row by row in the depth map's own (sensor) orientation.
    var grid: [Float?] = []
    /// Video frame time minus depth frame time, seconds; shows how stale the attached depth is.
    var age: TimeInterval?
}

/// Everything the vision stage extracted from one camera frame.
struct FrameObservation: Equatable, Sendable {
    /// Presentation timestamp of the frame in seconds (monotonic).
    var timestamp: TimeInterval
    var face: FaceObservation?
    var pose: BodyPoseObservation?
    /// Orientation that produced the face hit (nil when no face was found).
    var orientation: CGImagePropertyOrientation?
    /// Orientation that produced the body-pose hit (nil when no pose was found).
    var poseOrientation: CGImagePropertyOrientation?
    var metrics: FrameMetrics?
    var depth: DepthMetrics?

    init(timestamp: TimeInterval, face: FaceObservation? = nil, pose: BodyPoseObservation? = nil,
         orientation: CGImagePropertyOrientation? = nil) {
        self.timestamp = timestamp
        self.face = face
        self.pose = pose
        self.orientation = orientation
    }
}
