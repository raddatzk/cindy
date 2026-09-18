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
        case .brightness: return brightnessSignal(observation)
        case .depth: return depthSignal(observation, for: exercise)
        }
    }

    /// Median TrueDepth distance in metres; the nearest depths when the map is mostly holes because
    /// the athlete is closer than the camera measures. A plank also needs the face: standing up next
    /// to the phone is barely farther away than the plank itself.
    func depthSignal(_ observation: FrameObservation, for exercise: Exercise) -> SignalSample {
        guard let depth = observation.depth, abs(depth.age ?? 0) <= config.depthMaxAge else { return .missing(.depth) }
        let distance = depth.validFraction >= config.depthMinValidFraction ? depth.median : depth.p05
        guard let distance else { return .missing(.depth) }
        let confidence = exercise.isHold ? observation.face?.confidence ?? 0 : 1
        return SignalSample(value: distance, confidence: confidence, source: .depth)
    }

    /// Mean image brightness; always confident when measured.
    func brightnessSignal(_ observation: FrameObservation) -> SignalSample {
        guard let luma = observation.metrics?.lumaMean else { return .missing(.brightness) }
        return SignalSample(value: luma, confidence: 1, source: .brightness)
    }

    /// Face bounding-box area (plus an optional weighted centre-y term).
    func faceSignal(_ observation: FrameObservation, for exercise: Exercise) -> SignalSample {
        guard let face = observation.face else { return .missing(.face) }
        let weight = config.faceYWeight(for: exercise)
        let value = face.area + weight * (face.centerY - 0.5)
        return SignalSample(value: value, confidence: face.confidence, source: .face)
    }

    /// Body-pose signal: shoulder width for squats (the athlete comes closer to the floor
    /// camera), landmark height otherwise (nose for push-ups, shoulders for pull-ups).
    func poseSignal(_ observation: FrameObservation, for exercise: Exercise) -> SignalSample {
        guard let pose = observation.pose else { return .missing(.pose) }
        let result: (value: Float, confidence: Float)?
        switch exercise {
        case .pushUp, .plank: result = pose.meanY(of: [.nose]).map { ($0.y, $0.confidence) }
        case .squat: result = pose.shoulderWidthSample
        case .pullUp: result = pose.meanY(of: [.leftShoulder, .rightShoulder]).map { ($0.y, $0.confidence) }
        }
        guard let result else { return .missing(.pose) }
        return SignalSample(value: result.value, confidence: result.confidence, source: .pose)
    }

    /// How calibrated thresholds follow the rest level: sizes in the image and depth distances scale
    /// with where the athlete is, brightness shifts. Image heights and a face-y mix stay absolute (nil).
    func restAdaptation(for exercise: Exercise, source: SignalSource) -> RestAdaptation? {
        switch source {
        case .face: return config.faceYWeight(for: exercise) == 0 ? .scale : nil
        case .pose: return exercise == .squat ? .scale : nil
        case .brightness: return .shift
        case .depth: return .scale
        }
    }
}
