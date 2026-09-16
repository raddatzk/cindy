import Foundation

/// Turns the per-frame tunables of the detection chain (`emaAlpha`, `restTrackingAlpha`,
/// `stableFrames`, `evidenceLostFrames`) into rules on time.
///
/// The chain was tuned on iPhones at 30 fps, and those numbers counted frames. A Galaxy A20e
/// delivers 5–20 fps, where 10 arming frames take up to 2 s (longer than the pause between two
/// squats) and a per-frame EMA lags six times as long. So every per-frame number now means "at
/// `SignalConfig.referenceFrameRate`": at 30 fps the rules are the old frame rules, at lower
/// rates they cover the same time with fewer samples.
///
/// `poseMedianWindow` deliberately stays a sample count: it removes single-frame outliers, and
/// a window measured in time would hold a single sample at 5 fps and filter nothing.
enum FrameTiming {
    /// Longest frame interval an alpha is scaled for. A camera stall longer than this moves a
    /// filter as far as half a second does (15 reference frames, alpha 0.3 → 0.995), so a stall
    /// does not read as an exact jump to the newest value.
    static let maxFrameInterval: TimeInterval = 0.5

    /// Slack on the duration rules: half a reference frame. Timestamps are rounded and jitter,
    /// so 10 fps frames lie 0.0999 s apart and four of them span 0.2997 s, not 0.3 s.
    static let durationTolerance: TimeInterval = 0.5 / SignalConfig.referenceFrameRate

    /// A run of rest samples needs at least this many to arm by duration (one slow frame must not arm).
    static let minStableSamples = 3

    /// A missing run needs at least this many samples to count as a vanish by duration.
    static let minLostSamples = 2

    /// Per-sample factor of an EMA tuned as `alpha` per reference frame, for a sample
    /// `frameInterval` seconds after the previous frame: `1 − (1 − alpha)^(dt · referenceFrameRate)`.
    /// `nil`, zero or negative intervals (the first frame, a timestamp reset) use `alpha` itself;
    /// intervals are capped at `maxFrameInterval`.
    static func alpha(_ alpha: Float, frameInterval: TimeInterval?) -> Float {
        guard let frameInterval, frameInterval > 0, alpha > 0, alpha < 1 else { return alpha }
        let frames = min(frameInterval, maxFrameInterval) * SignalConfig.referenceFrameRate
        return Float(1 - pow(1 - Double(alpha), frames))
    }

    /// Whether a run of `count` consecutive samples spanning `duration` seconds (first to last
    /// sample) covers `frames` reference frames: either it has that many samples (the 30 fps
    /// rule), or it has at least `minCount` samples and lasts as long as `frames` frames do at the
    /// reference rate, `(frames − 1) / referenceFrameRate`, minus `durationTolerance`.
    static func covers(frames: Int, count: Int, duration: TimeInterval, minCount: Int) -> Bool {
        if count >= frames { return true }
        let required = Double(frames - 1) / SignalConfig.referenceFrameRate - durationTolerance
        return count >= minCount && duration >= required
    }

    /// A minimum sample count tuned at the reference rate, scaled to the rate `timestamps` actually
    /// arrive at: `ceil(count · rate / referenceFrameRate)`, at least `floor`, never above `count`.
    /// At the reference rate or faster it is `count` itself.
    ///
    /// The calibration's baseline window is half a second — about 16 samples at 30 fps but 3 at
    /// 5 fps — so a fixed minimum of 5 failed every calibration on a slow phone.
    static func scaledCount(_ count: Int, timestamps: [TimeInterval], floor: Int) -> Int {
        guard let rate = sampleRate(timestamps), rate < SignalConfig.referenceFrameRate else { return count }
        // The small epsilon keeps 29.97 fps of jitter from rounding 4.995 up past a whole sample.
        let scaled = Int((Double(count) * rate / SignalConfig.referenceFrameRate - 0.01).rounded(.up))
        return max(min(floor, count), min(scaled, count))
    }

    /// Samples per second from the median interval between consecutive `timestamps`, so dropped
    /// frames and low-confidence gaps do not pull it down. `nil` without two distinct timestamps.
    static func sampleRate(_ timestamps: [TimeInterval]) -> Double? {
        let intervals = zip(timestamps, timestamps.dropFirst()).map { $1 - $0 }.filter { $0 > 0 }.sorted()
        guard !intervals.isEmpty else { return nil }
        let middle = intervals.count / 2
        let median = intervals.count % 2 == 1 ? intervals[middle] : (intervals[middle - 1] + intervals[middle]) / 2
        return 1 / median
    }
}
