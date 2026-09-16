import Foundation

/// Which measurement feeds the rep detector.
enum SignalSource: String, Codable, CaseIterable, Identifiable, Sendable {
    /// Face bounding-box based signal (push-ups, pull-ups, plank).
    case face
    /// Body-pose landmark based signal.
    case pose
    /// Mean image brightness (squats): the body covers more of the bright ceiling the lower
    /// it gets, whichever way the athlete looks. Face and pose run alongside as a plausibility check.
    case brightness

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .face: return L("Face")
        case .pose: return L("Body Pose")
        case .brightness: return L("Brightness")
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
    /// Relative thresholds only: the rest level measured when arming may differ from
    /// the calibrated baseline by at most this factor (either way). Keeps the counter
    /// from arming while the athlete stands right at the phone or holds the far position.
    var restBaselineTolerance: Float = 2.5
    /// Shifted thresholds (brightness) only: the rest level measured when arming may differ
    /// from the calibrated baseline by at most this much (brightness 0…1).
    var restShiftTolerance: Float = 0.1
    /// Scaled thresholds only: while armed and at rest, the rest level follows lower
    /// values with this EMA factor (never higher ones, those may be a rep starting).
    /// Corrects a rest measured while the athlete was still walking away from the phone.
    /// Brightness gains nothing from it in the recordings, so shifted thresholds do not track.
    var restTrackingAlpha: Float = 0.03
    /// Rest-anchored thresholds (brightness): a rep leaves rest after this fraction of the
    /// calibrated swing and peaks after `restAnchoredPeak`.
    var restAnchoredLeave: Float = 0.3
    var restAnchoredPeak: Float = 0.7

    /// Brightness reps only count when a body signal moved in the same cycle: the face area
    /// grew by this factor, the shoulder width by `evidenceShoulderRatio`, or face or pose
    /// vanished for `evidenceLostFrames` frames (looking ahead at the bottom of a squat).
    var evidenceFaceAreaRatio: Float = 1.5
    var evidenceShoulderRatio: Float = 1.3
    var evidenceLostFrames: Int = 5
    /// Pose frames below this shoulder confidence do not count as a seen body.
    var evidencePoseMinConfidence: Float = 0.2

    /// Median window applied to body-pose signals before the EMA (single-frame outliers).
    var poseMedianWindow: Int = 5

    // MARK: Rep plausibility & debounce

    var minRepDuration: TimeInterval = 0.5
    /// Longer cycles are rejected. With relative thresholds a cycle still open after this
    /// long disarms the detector, so a changed standing position gets a fresh rest level.
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

    /// Signal source per exercise. Squats use the brightness: from the floor the face is only
    /// found while the athlete looks down, and body pose drops out at the bottom of the squat
    /// when looking ahead (CSV recordings 2026-09-14).
    var pullUpSource: SignalSource = .face
    var pushUpSource: SignalSource = .face
    var squatSource: SignalSource = .brightness

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
    /// Brightness (0…1) must dip by at least this much; the recorded squats dipped 0.03–0.09.
    var calibrationMinBrightnessExcursion: Float = 0.015
    /// Seconds the athlete holds the plank during calibration.
    var calibrationHoldDuration: TimeInterval = 3
    /// Half-width of the plank band relative to the mean signal (± 25 %).
    var calibrationHoldBandMargin: Float = 0.25

    // MARK: Workout

    var workoutCountdownSeconds: Int = 5

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
