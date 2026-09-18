import Foundation

/// Which way the signal moves during a rep, relative to the rest position.
enum RepDirection: String, Codable, Sendable {
    /// Rest at the low end; a rep rises above `high` and returns below `low`.
    case peak
    /// Rest at the high end; a rep falls below `low` and returns above `high`.
    case trough
}

/// How relative thresholds follow the rest level measured when the detector arms.
enum RestAdaptation: String, Codable, Sendable {
    /// Multiply by rest / baseline: sizes in the image (face area, shoulder width),
    /// which scale with the distance to the phone.
    case scale
    /// Add rest − baseline: image brightness, which shifts with light and position.
    case shift
}

/// Schmitt-trigger thresholds for one exercise.
struct RepThresholds: Codable, Equatable, Sendable {
    var low: Float
    var high: Float
    var direction: RepDirection
    /// Rest value `low` and `high` were derived from. When set, the thresholds are
    /// relative: the detector adapts them to the rest level it measures when arming,
    /// so the signal still works when the athlete stands elsewhere than during
    /// calibration. `nil` = absolute thresholds.
    var baseline: Float?
    var adaptation: RestAdaptation

    init(low: Float, high: Float, direction: RepDirection, baseline: Float? = nil,
         adaptation: RestAdaptation = .scale) {
        self.low = low
        self.high = high
        self.direction = direction
        self.baseline = baseline
        self.adaptation = adaptation
    }

    var isRelative: Bool {
        guard let baseline else { return false }
        return adaptation == .shift || baseline > 0
    }

    /// The same thresholds for a measured rest level; `baseline` becomes that level.
    func adapted(toRest rest: Float) -> RepThresholds {
        guard let baseline, isRelative else { return self }
        switch adaptation {
        case .scale:
            let scale = rest / baseline
            return RepThresholds(low: low * scale, high: high * scale, direction: direction, baseline: rest,
                                 adaptation: adaptation)
        case .shift:
            let offset = rest - baseline
            return RepThresholds(low: low + offset, high: high + offset, direction: direction, baseline: rest,
                                 adaptation: adaptation)
        }
    }

    /// Thresholds measured from the rest level instead of the cycle extremes: the rep leaves
    /// rest after `leave` of the swing towards `extreme` and peaks after `peak` of it. Used
    /// where a rep overshoots past rest on the way back (brightness), which would otherwise
    /// put the rest-side threshold beyond the rest level itself, or where reps vary in depth (depth).
    static func fromRest(baseline: Float, extreme: Float, direction: RepDirection,
                         leave: Float, peak: Float, adaptation: RestAdaptation = .shift) -> RepThresholds {
        let swing = extreme - baseline
        let leaveValue = baseline + leave * swing
        let peakValue = baseline + peak * swing
        return RepThresholds(low: min(leaveValue, peakValue), high: max(leaveValue, peakValue), direction: direction,
                             baseline: baseline, adaptation: adaptation)
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
