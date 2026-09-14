import Foundation

/// One scalar per frame for the current exercise, plus how much to trust it.
struct SignalSample: Equatable, Sendable {
    /// nil when there is no usable measurement in this frame.
    var value: Float?
    var confidence: Float
    var source: SignalSource

    static func missing(_ source: SignalSource) -> SignalSample {
        SignalSample(value: nil, confidence: 0, source: source)
    }
}

/// Turns a `FrameObservation` into the 1-D signal of the expected exercise.
struct SignalExtractor: Sendable {
    var config: SignalConfig

    init(config: SignalConfig = .default) {
        self.config = config
    }

    func extract(_ observation: FrameObservation, for exercise: Exercise, source: SignalSource) -> SignalSample {
        switch source {
        case .face: return faceSignal(observation, for: exercise)
        case .pose: return poseSignal(observation, for: exercise)
        }
    }

    /// Face bounding-box area (plus an optional weighted centre-y term).
    func faceSignal(_ observation: FrameObservation, for exercise: Exercise) -> SignalSample {
        guard let face = observation.face else { return .missing(.face) }
        let weight = config.faceYWeight(for: exercise)
        let value = face.area + weight * (face.centerY - 0.5)
        return SignalSample(value: value, confidence: face.confidence, source: .face)
    }

    /// Landmark height in the image: nose for push-ups, hips for squats, shoulders for pull-ups.
    func poseSignal(_ observation: FrameObservation, for exercise: Exercise) -> SignalSample {
        guard let pose = observation.pose else { return .missing(.pose) }
        let result: (y: Float, confidence: Float)?
        switch exercise {
        case .pushUp, .plank: result = pose.meanY(of: [.nose])
        case .squat: result = pose.meanY(of: [.leftHip, .rightHip])
        case .pullUp: result = pose.meanY(of: [.leftShoulder, .rightShoulder])
        }
        guard let result else { return .missing(.pose) }
        return SignalSample(value: result.y, confidence: result.confidence, source: .pose)
    }
}
