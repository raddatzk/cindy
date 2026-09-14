import Foundation

/// Which way the signal moves during a rep, relative to the rest position.
enum RepDirection: String, Codable, Sendable {
    /// Rest at the low end; a rep rises above `high` and returns below `low`.
    case peak
    /// Rest at the high end; a rep falls below `low` and returns above `high`.
    case trough
}

/// Schmitt-trigger thresholds for one exercise.
struct RepThresholds: Codable, Equatable, Sendable {
    var low: Float
    var high: Float
    var direction: RepDirection

    init(low: Float, high: Float, direction: RepDirection) {
        self.low = low
        self.high = high
        self.direction = direction
    }

    /// Derives thresholds from the observed signal extremes:
    /// `low = min + margin·range`, `high = max − margin·range`.
    static func from(min: Float, max: Float, direction: RepDirection,
                     margin: Float = SignalConfig.default.thresholdMargin) -> RepThresholds {
        let range = max - min
        return RepThresholds(low: min + margin * range, high: max - margin * range, direction: direction)
    }

    /// Rough starting values for the debug mode when no calibration exists.
    /// Face area is normalised (0…1); these assume a flat phone on the floor.
    static func hardcoded(for exercise: Exercise) -> RepThresholds {
        switch exercise {
        case .pushUp: return RepThresholds(low: 0.06, high: 0.16, direction: .peak)
        case .squat: return RepThresholds(low: 0.006, high: 0.012, direction: .peak)
        case .pullUp: return RepThresholds(low: 0.0015, high: 0.003, direction: .trough)
        case .plank: return RepThresholds(low: 0.04, high: 0.10, direction: .peak) // band, not a cycle
        }
    }
}
