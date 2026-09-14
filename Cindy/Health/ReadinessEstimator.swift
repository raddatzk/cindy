import Foundation

/// What Apple Health contributes to the readiness estimate. All optional:
/// without an Apple Watch most of it stays empty, and the estimate falls back
/// to the workout history alone.
struct HealthMetrics: Equatable {
    var heartRateVariability: MetricBaseline?
    var restingHeartRate: MetricBaseline?
    /// Hours actually asleep last night (overlapping sources merged).
    var sleepHours: Double?
    var bodyMassKilograms: Double?

    static let none = HealthMetrics()

    var isEmpty: Bool {
        heartRateVariability == nil && restingHeartRate == nil && sleepHours == nil
    }
}

/// Turns the workout history and the Health metrics into a `Readiness`.
/// Pure: no HealthKit, no clock of its own, so it can be unit-tested.
struct ReadinessEstimator {
    var now: Date = Date()

    func estimate(history: [WorkoutRecord], metrics: HealthMetrics = .none) -> Readiness? {
        var components: [Readiness.Component] = []

        if let last = history.max(by: { $0.date < $1.date }) {
            let hours = now.timeIntervalSince(last.date) / 3600
            let needed = ReadinessScoring.recoveryHours(for: last)
            components.append(component(.recovery,
                                        ReadinessScoring.recovery(fraction: max(hours, 0) / needed),
                                        L("\(Int(max(hours, 0).rounded())) h since the last workout, \(Int(needed.rounded())) h suggested")))
        }
        if let ratio = ReadinessEstimator.loadRatio(history: history, now: now) {
            components.append(component(.trainingLoad,
                                        ReadinessScoring.trainingLoad(ratio: ratio),
                                        L("This week's volume is \(ratio.formattedDecimal(1))× your average")))
        }
        if let hours = metrics.sleepHours {
            components.append(component(.sleep, ReadinessScoring.sleep(hours: hours),
                                        L("\(hours.formattedDecimal(1)) h of sleep last night")))
        }
        if let baseline = metrics.heartRateVariability, let z = baseline.zScore {
            components.append(component(.heartRateVariability,
                                        ReadinessScoring.heartRateVariability(zScore: z),
                                        L("HRV \(Int(baseline.value.rounded())) ms, your average \(Int(baseline.mean.rounded())) ms")))
        }
        if let baseline = metrics.restingHeartRate, let z = baseline.zScore {
            components.append(component(.restingHeartRate,
                                        ReadinessScoring.restingHeartRate(zScore: z),
                                        L("Resting pulse \(Int(baseline.value.rounded())) bpm, your average \(Int(baseline.mean.rounded())) bpm")))
        }

        return Readiness(components: components, usesHealthData: !metrics.isEmpty)
    }

    /// Volume of the last 7 days divided by the weekly average of the last 28,
    /// counted in reps. `nil` below three sessions, where the average says nothing.
    static func loadRatio(history: [WorkoutRecord], now: Date, minimumSessions: Int = 3) -> Double? {
        let week = now.addingTimeInterval(-7 * 24 * 3600)
        let month = now.addingTimeInterval(-28 * 24 * 3600)
        let recent = history.filter { $0.date > month && $0.date <= now }
        guard recent.count >= minimumSessions else { return nil }
        let chronicWeekly = Double(recent.reduce(0) { $0 + $1.score.totalReps }) / 4
        guard chronicWeekly > 0 else { return nil }
        let acute = Double(recent.filter { $0.date > week }.reduce(0) { $0 + $1.score.totalReps })
        return acute / chronicWeekly
    }

    private func component(_ kind: Readiness.Component.Kind, _ score: Double,
                           _ detail: String) -> Readiness.Component {
        Readiness.Component(kind: kind, score: score,
                            weight: ReadinessScoring.weights[kind] ?? 0, detail: detail)
    }
}
