import Foundation

/// Which measurement feeds the rep detector.
enum SignalSource: String, Codable, CaseIterable, Identifiable, Sendable {
    /// Face bounding-box based signal (primary, clothing independent).
    case face
    /// Body-pose landmark based signal (fallback; unreliable from below).
    case pose

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .face: return L("Face")
        case .pose: return L("Body Pose")
        }
    }
}

/// All tunables of the detection chain in one place. Change the defaults here;
/// the values are deliberately plain constants so they can be tweaked after
/// analysing debug CSV recordings.
struct SignalConfig: Codable, Equatable, Sendable {
    static let `default` = SignalConfig()

    // MARK: Smoothing & thresholds

    /// EMA smoothing factor (1 = no smoothing).
    var emaAlpha: Float = 0.3
    /// Margin used to derive the Schmitt-trigger thresholds from the calibrated
    /// extremes: low = min + margin·range, high = max − margin·range.
    var thresholdMargin: Float = 0.25

    // MARK: Rep plausibility & debounce

    var minRepDuration: TimeInterval = 0.5
    var maxRepDuration: TimeInterval = 5.0
    /// Consecutive confident frames in the rest band before the counter is armed.
    var stableFrames: Int = 10
    /// Frames with lower confidence are ignored (state is held, not reset).
    var minConfidence: Float = 0.5
    /// After this long without a confident frame the detector drops its cycle
    /// state and has to be re-armed.
    var lostTimeout: TimeInterval = 2.0

    // MARK: Signal composition

    /// Weight of the normalised face centre-y added to the face area for squats.
    /// 0 = area only. The sign depends on where the athlete stands relative to
    /// the phone, so it is left at 0 until CSV recordings say otherwise.
    var squatFaceYWeight: Float = 0
    /// Same for pull-ups.
    var pullUpFaceYWeight: Float = 0

    /// Signal source per exercise.
    var pullUpSource: SignalSource = .face
    var pushUpSource: SignalSource = .face
    var squatSource: SignalSource = .face

    // MARK: Calibration

    var calibrationCountdownSeconds: Int = 3
    var calibrationTimeout: TimeInterval = 15
    /// Length of the initial window used to measure the rest baseline.
    var calibrationBaselineDuration: TimeInterval = 0.5
    /// Minimum number of confident samples the baseline window must contain.
    var calibrationBaselineMinSamples: Int = 5
    /// Face-area signals must swing by at least this fraction of the baseline.
    var calibrationMinRelativeExcursion: Float = 0.3
    /// Pose signals (normalised image coordinates) must swing by at least this much.
    var calibrationMinAbsoluteExcursion: Float = 0.05
    /// Seconds the athlete holds the plank during calibration.
    var calibrationHoldDuration: TimeInterval = 3
    /// Half-width of the plank band relative to the mean signal (± 25 %).
    var calibrationHoldBandMargin: Float = 0.25

    // MARK: Workout

    var workoutCountdownSeconds: Int = 5
    /// Remaining-time marks (seconds) that trigger a spoken announcement.
    var announcementMarks: [TimeInterval] = [600, 300, 60]

    // MARK: Camera

    /// Seconds of auto exposure after the session starts before exposure is locked.
    var exposureSettleDuration: TimeInterval = 2.0
    var targetFrameRate: Double = 30

    func source(for exercise: Exercise) -> SignalSource {
        switch exercise {
        case .pullUp: return pullUpSource
        case .pushUp: return pushUpSource
        case .squat: return squatSource
        case .plank: return pushUpSource // same geometry as the push-up top position
        }
    }

    func faceYWeight(for exercise: Exercise) -> Float {
        switch exercise {
        case .pullUp: return pullUpFaceYWeight
        case .pushUp: return 0
        case .squat: return squatFaceYWeight
        case .plank: return 0
        }
    }
}
