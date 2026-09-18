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
    /// Median distance of the TrueDepth depth map (squats, push-ups, plank): the body fills more
    /// of the map and comes closer the lower it gets, whichever way the athlete looks.
    case depth

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .face: return L("Face")
        case .pose: return L("Body Pose")
        case .brightness: return L("Brightness")
        case .depth: return L("Depth")
        }
    }
}

/// All tunables of the detection chain in one place. Change the defaults here;
/// the values are deliberately plain constants so they can be tweaked after
/// analysing debug CSV recordings.
///
/// Numbers counted in frames (`emaAlpha`, `restTrackingAlpha`, `stableFrames`, `evidenceLostFrames`)
/// mean frames at `referenceFrameRate`; `FrameTiming` applies them to the frame rate the camera
/// actually delivers.
struct SignalConfig: Codable, Equatable, Sendable {
    static let `default`: SignalConfig = {
        var config = SignalConfig()
        config.hasDepthCamera = CameraSession.hasDepthCamera
        return config
    }()

    /// Frame rate the per-frame tunables were tuned at (iPhones, 30 fps). A constant rather than
    /// `targetFrameRate`: the camera request may change per device, the tuning must not move with it.
    static let referenceFrameRate: Double = 30

    // MARK: Smoothing & thresholds

    /// EMA smoothing factor per reference frame (1 = no smoothing), scaled to the frame interval.
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
    /// values with this EMA factor per reference frame (never higher ones, those may be a rep starting).
    /// Corrects a rest measured while the athlete was still walking away from the phone.
    /// Brightness gains nothing from it in the recordings, so shifted thresholds do not track.
    var restTrackingAlpha: Float = 0.03
    /// Rest-anchored thresholds (brightness): a rep leaves rest after this fraction of the
    /// calibrated swing and peaks after `restAnchoredPeak`.
    var restAnchoredLeave: Float = 0.3
    var restAnchoredPeak: Float = 0.7

    /// Brightness reps only count when a body signal moved in the same cycle: the face area
    /// grew by this factor, the shoulder width by `evidenceShoulderRatio`, or face or pose
    /// vanished for `evidenceLostFrames` reference frames (looking ahead at the bottom of a squat):
    /// that many consecutive missing frames, or fewer (at least two) spanning as long as that many
    /// frames do at 30 fps.
    var evidenceFaceAreaRatio: Float = 1.5
    var evidenceShoulderRatio: Float = 1.3
    var evidenceLostFrames: Int = 5
    /// Pose frames below this shoulder confidence do not count as a seen body.
    var evidencePoseMinConfidence: Float = 0.2

    /// Median window applied to body-pose and depth signals before the EMA (single-frame outliers).
    /// A sample count, not a duration: an outlier is one frame at any frame rate.
    var poseMedianWindow: Int = 5

    // MARK: Depth

    /// Below this share of valid pixels the athlete is closer than the TrueDepth camera measures
    /// (bottom of a push-up: 4–10 % valid); the signal is then the nearest depth left (5th percentile)
    /// instead of the median, which the few remaining pixels make meaningless.
    var depthMinValidFraction: Float = 0.5
    /// Depth maps older (or newer) than this relative to the video frame count as missing.
    var depthMaxAge: TimeInterval = 0.25
    /// Depth reps hang off the rest distance: a rep leaves rest after this fraction of the calibrated
    /// swing and peaks after `depthRestPeak`. Low enough for a shallower squat than the calibration one
    /// (recording 2026-09-17: bottoms at 32–57 % of the standing distance).
    var depthRestLeave: Float = 0.25
    var depthRestPeak: Float = 0.5
    /// Something within this distance of the phone (5th percentile) counts as a person in view.
    var depthPresenceDistance: Float = 1.0

    // MARK: Rep plausibility & debounce

    var minRepDuration: TimeInterval = 0.5
    /// Longer cycles are rejected. With relative thresholds a cycle still open after this
    /// long disarms the detector, so a changed standing position gets a fresh rest level.
    var maxRepDuration: TimeInterval = 5.0
    /// Consecutive confident frames in the rest band before the counter is armed, at the reference
    /// frame rate: that many samples, or at least three spanning `(stableFrames − 1) / 30` s. A
    /// Galaxy A20e at 5 fps would otherwise need 2 s of rest, longer than the pause between squats.
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

    /// Signal source per exercise. Squats and push-ups use the TrueDepth
    /// distance (CSV recordings 2026-09-17: 10/10 squats whichever way the athlete looked); phones
    /// without a TrueDepth camera fall back to `pushUpFallbackSource` and `squatFallbackSource`.
    /// Brightness squats: from the floor the face is only found while the athlete looks down, and
    /// body pose drops out at the bottom of the squat when looking ahead (CSV recordings 2026-09-14).
    var pullUpSource: SignalSource = .face
    var pushUpSource: SignalSource = .depth
    var squatSource: SignalSource = .depth
    var pushUpFallbackSource: SignalSource = .face
    var squatFallbackSource: SignalSource = .brightness
    /// Whether the front camera delivers TrueDepth maps; `default` asks the device.
    var hasDepthCamera: Bool = false

    // MARK: Calibration

    var calibrationCountdownSeconds: Int = 3
    var calibrationTimeout: TimeInterval = 15
    /// Length of the initial window used to measure the rest baseline.
    var calibrationBaselineDuration: TimeInterval = 0.5
    /// Minimum number of confident samples the baseline window must contain, at the reference frame
    /// rate; `FrameTiming.scaledCount` lowers it for slower cameras (never below 3).
    var calibrationBaselineMinSamples: Int = 5
    /// Face-area signals must swing by at least this fraction of the baseline.
    var calibrationMinRelativeExcursion: Float = 0.3
    /// Pose signals (normalised image coordinates) must swing by at least this much.
    var calibrationMinAbsoluteExcursion: Float = 0.05
    /// Brightness (0…1) must dip by at least this much; the recorded squats dipped 0.03–0.09.
    var calibrationMinBrightnessExcursion: Float = 0.015

    // MARK: Workout

    var workoutCountdownSeconds: Int = 5
    /// Countdown after the athlete taps start on a hold (plank) before its clock runs.
    var holdCountdownSeconds: Int = 3

    // MARK: Camera

    /// Seconds of auto exposure after the session starts before exposure is locked.
    var exposureSettleDuration: TimeInterval = 2.0
    /// Frame rate requested from the camera. The tuning reference is `referenceFrameRate`, not this.
    var targetFrameRate: Double = 30

    func source(for exercise: Exercise) -> SignalSource {
        switch exercise {
        case .pullUp: return available(pullUpSource, fallback: .face)
        case .pushUp: return available(pushUpSource, fallback: pushUpFallbackSource)
        case .squat: return available(squatSource, fallback: squatFallbackSource)
        case .plank: return source(for: .pushUp) // unused: holds run on a timer
        }
    }

    private func available(_ source: SignalSource, fallback: SignalSource) -> SignalSource {
        source == .depth && !hasDepthCamera ? fallback : source
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
