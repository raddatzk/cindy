import Foundation

/// Exponential moving average. `alpha` = 1 passes the input through unchanged.
///
/// `alpha` is the factor per reference frame (30 fps); pass the frame interval to `update` so the
/// smoothing covers the same time at any frame rate (see `FrameTiming.alpha`).
struct EMAFilter: Sendable {
    let alpha: Float
    private(set) var value: Float?

    init(alpha: Float) {
        self.alpha = alpha
    }

    /// Feeds one sample and returns the smoothed value. `frameInterval` is the time in seconds since
    /// the previous camera frame; `nil` applies `alpha` per sample.
    @discardableResult
    mutating func update(_ x: Float, frameInterval: TimeInterval? = nil) -> Float {
        if let v = value {
            let next = v + FrameTiming.alpha(alpha, frameInterval: frameInterval) * (x - v)
            value = next
            return next
        }
        value = x
        return x
    }

    mutating func reset() {
        value = nil
    }
}
