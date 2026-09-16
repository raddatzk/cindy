import Foundation

/// Exponential moving average. `alpha` = 1 passes the input through unchanged.
struct EMAFilter: Sendable {
    let alpha: Float
    private(set) var value: Float?

    init(alpha: Float) {
        self.alpha = alpha
    }

    /// Feeds one sample and returns the smoothed value.
    @discardableResult
    mutating func update(_ x: Float) -> Float {
        if let v = value {
            let next = v + alpha * (x - v)
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
