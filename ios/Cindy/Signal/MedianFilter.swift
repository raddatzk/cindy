import Foundation

/// Running median over the last `window` samples; removes single-frame outliers
/// (e.g. a body-pose frame with the shoulders swapped for other joints).
struct MedianFilter: Sendable {
    let window: Int
    private var samples: [Float] = []

    init(window: Int) {
        self.window = max(window, 1)
    }

    mutating func update(_ x: Float) -> Float {
        samples.append(x)
        if samples.count > window { samples.removeFirst(samples.count - window) }
        let sorted = samples.sorted()
        let mid = sorted.count / 2
        return sorted.count % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid]
    }
}
