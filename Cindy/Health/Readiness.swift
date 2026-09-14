import Foundation

/// How ready the body is for the next hard session, on a 0–100 scale.
///
/// Apple publishes no readiness figure — iOS 26 has no such HealthKit type —
/// so Cindy computes one from the values it can actually read and from its own
/// workout history. Every input is optional and the score is the weighted mean
/// of the components that exist, so the estimate degrades instead of
/// disappearing when there is no Apple Watch (or no Health access at all).
struct Readiness: Equatable {
    enum Band: String, Equatable {
        case rest, easy, ready, primed
    }

    /// One input, already translated to the common 0–100 scale.
    struct Component: Equatable, Identifiable {
        enum Kind: String, Equatable {
            case recovery, sleep, heartRateVariability, restingHeartRate, trainingLoad
        }

        var kind: Kind
        var score: Double
        var weight: Double
        /// Localized one-liner naming the measured value, e.g. "6.2 h of sleep last night".
        var detail: String

        var id: Kind { kind }
        /// How far this component pulls the total away from neutral.
        var pull: Double { (score - ReadinessScoring.neutral) * weight }
    }

    var score: Int
    var components: [Component]
    /// False when the score rests on the workout history alone.
    var usesHealthData: Bool

    var band: Band {
        switch score {
        case ..<40: return .rest
        case ..<60: return .easy
        case ..<80: return .ready
        default: return .primed
        }
    }

    /// Weighted score of everything except the plain hours-since-the-last-session
    /// rule: what the body says beyond what the calendar already knows.
    /// `nil` when recovery is the only thing known.
    ///
    /// The reminder uses this rather than the total, which already contains the
    /// waiting time it is about to add.
    var signalScore: Int? {
        let signals = components.filter { $0.kind != .recovery }
        let totalWeight = signals.reduce(0) { $0 + $1.weight }
        guard !signals.isEmpty, totalWeight > 0 else { return nil }
        return Int((signals.reduce(0) { $0 + $1.score * $1.weight } / totalWeight).rounded())
    }

    /// The components that move the score most, strongest first.
    func reasons(limit: Int = 3) -> [Component] {
        components.sorted { abs($0.pull) > abs($1.pull) }.prefix(limit).map { $0 }
    }

    /// `nil` when nothing at all is known — no workout in the history and no Health data.
    init?(components: [Component], usesHealthData: Bool) {
        let totalWeight = components.reduce(0) { $0 + $1.weight }
        guard !components.isEmpty, totalWeight > 0 else { return nil }
        let weighted = components.reduce(0) { $0 + $1.score * $1.weight }
        self.score = Int((weighted / totalWeight).rounded())
        self.components = components
        self.usesHealthData = usesHealthData
    }
}

/// The curves that turn each raw measurement into a 0–100 component score.
///
/// Deliberately piecewise linear and readable: these are judgement calls from
/// the training literature, not a validated model, and they should be easy to
/// argue with and to adjust.
enum ReadinessScoring {
    /// Score of an average day. Components sit around this when nothing stands out.
    static let neutral: Double = 78

    static let weights: [Readiness.Component.Kind: Double] = [
        .recovery: 0.35,
        .sleep: 0.20,
        .heartRateVariability: 0.25,
        .restingHeartRate: 0.10,
        .trainingLoad: 0.10,
    ]

    /// Rest taken since the last session, as a fraction of the rest it needs.
    static func recovery(fraction: Double) -> Double {
        interpolate(fraction, [(0, 10), (0.5, 45), (1, 85), (2, 100)])
    }

    /// Hours a session needs before the next hard one: two days for a full
    /// AMRAP, proportionally less for one that was cut short.
    static func recoveryHours(for record: WorkoutRecord) -> Double {
        let planned = max(record.plannedDuration, 1)
        let effort = min(1, record.durationSeconds / planned)
        return 24 + 24 * effort
    }

    static func sleep(hours: Double) -> Double {
        interpolate(hours, [(0, 0), (4, 25), (6, 60), (7.5, 88), (8.5, 100), (11, 85)])
    }

    /// Heart rate variability against the personal baseline: above is good.
    static func heartRateVariability(zScore: Double) -> Double {
        interpolate(zScore, [(-2.5, 5), (-1, 45), (0, 80), (1, 93), (2.5, 100)])
    }

    /// Resting heart rate against the personal baseline: above is bad.
    static func restingHeartRate(zScore: Double) -> Double {
        interpolate(-zScore, [(-2.5, 5), (-1, 45), (0, 80), (1, 93), (2.5, 100)])
    }

    /// Volume of the last 7 days divided by the weekly average of the last 28.
    /// Around 1 is sustainable; a sharp spike is the classic overreaching sign.
    static func trainingLoad(ratio: Double) -> Double {
        interpolate(ratio, [(0, 70), (0.5, 82), (0.8, 92), (1.0, 88), (1.3, 75), (1.6, 45), (2.5, 20)])
    }

    /// Linear interpolation between the given points, flat outside their range.
    static func interpolate(_ x: Double, _ points: [(Double, Double)]) -> Double {
        guard let first = points.first, let last = points.last else { return neutral }
        if x <= first.0 { return first.1 }
        if x >= last.0 { return last.1 }
        for (lower, upper) in zip(points, points.dropFirst()) where x <= upper.0 {
            let span = upper.0 - lower.0
            guard span > 0 else { return upper.1 }
            return lower.1 + (upper.1 - lower.1) * (x - lower.0) / span
        }
        return last.1
    }
}

/// A measurement together with the personal baseline it is judged against.
struct MetricBaseline: Equatable {
    var value: Double
    var mean: Double
    var standardDeviation: Double

    /// Deviation in standard deviations, clamped to ±3.
    /// `nil` when the baseline barely varies, where a z-score would explode.
    var zScore: Double? {
        guard standardDeviation > mean * 0.01, standardDeviation > 0 else { return nil }
        return min(max((value - mean) / standardDeviation, -3), 3)
    }

    /// Deviation from the baseline as a fraction, for the human-readable line.
    var relativeDeviation: Double {
        guard mean != 0 else { return 0 }
        return (value - mean) / mean
    }

    /// Baseline over earlier daily values, judged against the most recent one.
    /// `nil` below `minimumDays` of history, where "your normal" is guesswork.
    init?(current: Double, earlierDailyValues: [Double], minimumDays: Int = 10) {
        guard earlierDailyValues.count >= minimumDays else { return nil }
        let mean = earlierDailyValues.reduce(0, +) / Double(earlierDailyValues.count)
        let variance = earlierDailyValues.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(earlierDailyValues.count)
        self.value = current
        self.mean = mean
        self.standardDeviation = variance.squareRoot()
    }

    init(value: Double, mean: Double, standardDeviation: Double) {
        self.value = value
        self.mean = mean
        self.standardDeviation = standardDeviation
    }
}
