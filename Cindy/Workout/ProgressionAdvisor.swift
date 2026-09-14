import Foundation

/// Suggests the plan for the next workout from the last result.
///
/// Rule: measure the reserve as the mean round time of the first quarter
/// divided by the mean round time of the last quarter (1 = steady pace). With enough reserve the difficulty
/// goes up one notch in a fixed order: duration up to 20 min first, then +1 rep
/// on the exercise furthest below the Cindy prescription, then +5 s plank,
/// and finally a higher round goal (density). A collapse in rounds after a
/// harder plan steps back to the previous plan.
struct ProgressionAdvisor {
    struct Recommendation: Equatable {
        enum Kind: Equatable { case hold, duration, reps(Exercise), plank, density(targetRounds: Int), stepBack }
        var kind: Kind
        var plan: WorkoutPlan
        var reason: String

        var changesPlan: Bool { kind != .hold && !(isDensity) }
        private var isDensity: Bool { if case .density = kind { return true } else { return false } }
    }

    /// Reserve ratio at or above this → progress.
    var reserveThreshold: Double = 0.85
    /// Rounds falling by more than this fraction after a harder plan → step back.
    var collapseFraction: Double = 0.2
    var maxDurationMinutes = 20

    func recommend(after record: WorkoutRecord, previous: WorkoutRecord?) -> Recommendation {
        let plan = record.plan ?? .cindy

        guard record.completed else {
            return Recommendation(kind: .hold, plan: plan,
                                  reason: L("Finish the full \(plan.durationMinutes) minutes first, then step up."))
        }
        if let previous, let previousPlan = previous.plan, previous.completed,
           plan.repsPerRound > previousPlan.repsPerRound || plan.durationMinutes > previousPlan.durationMinutes,
           Double(record.rounds) < Double(previous.rounds) * (1 - collapseFraction) {
            return Recommendation(kind: .stepBack, plan: previousPlan,
                                  reason: L("Rounds dropped from \(previous.rounds) to \(record.rounds). Back to the previous plan."))
        }
        guard let reserve = ProgressionAdvisor.reserveRatio(record) else {
            return Recommendation(kind: .hold, plan: plan,
                                  reason: L("Too few rounds to judge. Keep the plan."))
        }
        let reserveText = reserve.formattedPercent
        guard reserve >= reserveThreshold else {
            return Recommendation(kind: .hold, plan: plan,
                                  reason: L("Last quarter only \(reserveText) of the first. Keep the plan until the pace holds throughout."))
        }

        var next = plan
        if plan.durationMinutes < maxDurationMinutes {
            next.durationMinutes = min(maxDurationMinutes, plan.durationMinutes + 5)
            return Recommendation(kind: .duration, plan: next,
                                  reason: L("Pace steady (\(reserveText)). Raise the time to \(next.durationMinutes) minutes."))
        }
        if let weakest = weakestExercise(in: plan) {
            next.setTarget(plan.target(for: weakest) + 1, for: weakest)
            return Recommendation(kind: .reps(weakest), plan: next,
                                  reason: L("Pace steady (\(reserveText)). \(weakest.displayName) up to \(next.target(for: weakest)) per round."))
        }
        if plan.contains(.plank) {
            next.setTarget(plan.target(for: .plank) + 5, for: .plank)
            return Recommendation(kind: .plank, plan: next,
                                  reason: L("Sets are at Cindy level. Plank up to \(next.target(for: .plank)) s."))
        }
        return Recommendation(kind: .density(targetRounds: record.rounds + 1), plan: plan,
                              reason: L("Full Cindy, pace steady. Target next time: \(record.rounds + 1) rounds."))
    }

    /// Pace reserve: mean round duration in the first quarter divided by the mean
    /// round duration in the last quarter (1 = steady, below 1 = slowing down).
    static func reserveRatio(_ record: WorkoutRecord) -> Double? {
        let duration = record.plan?.duration ?? record.durationSeconds
        guard duration > 0, let stamps = record.roundTimestamps, stamps.count >= 4 else { return nil }
        let durations = roundDurations(record)
        let quarter = duration / 4
        let first = zip(stamps, durations).filter { $0.0 <= quarter }.map(\.1)
        let last = zip(stamps, durations).filter { $0.0 > duration - quarter }.map(\.1)
        guard !first.isEmpty, !last.isEmpty else { return nil }
        let firstMean = first.reduce(0, +) / Double(first.count)
        let lastMean = last.reduce(0, +) / Double(last.count)
        guard lastMean > 0 else { return nil }
        return min(firstMean / lastMean, 1.5)
    }

    /// Durations of the individual rounds in seconds.
    static func roundDurations(_ record: WorkoutRecord) -> [TimeInterval] {
        guard let stamps = record.roundTimestamps else { return [] }
        var previous: TimeInterval = 0
        return stamps.map { stamp in
            defer { previous = stamp }
            return stamp - previous
        }
    }

    /// Rep exercise with the smallest current/Cindy ratio, if any is below the prescription.
    private func weakestExercise(in plan: WorkoutPlan) -> Exercise? {
        let candidates = plan.sets
            .filter { !$0.exercise.isHold && WorkoutPlan.cindy.contains($0.exercise) }
            .map { ($0.exercise, Double($0.target) / Double(WorkoutPlan.cindy.target(for: $0.exercise))) }
            .filter { $0.1 < 1 }
        return candidates.min { $0.1 < $1.1 }?.0
    }
}
