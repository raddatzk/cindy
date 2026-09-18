import Foundation

/// One entry of a round: an exercise with its target (reps, or seconds for holds).
struct ExerciseSet: Codable, Equatable, Hashable, Identifiable, Sendable {
    var exercise: Exercise
    var target: Int

    var id: Exercise { exercise }

    var label: String {
        exercise.isHold ? L("\(target) s \(exercise.displayName)")
                        : L("\(target) \(exercise.name(for: target))")
    }
}

/// Ordered list of exercises that make up one round, the AMRAP duration, and an optional plank
/// held once after the clock has run out.
struct WorkoutPlan: Codable, Hashable, Sendable {
    /// The round: rep exercises only.
    var sets: [ExerciseSet]
    var durationMinutes: Int = 20
    /// Seconds of the plank after the AMRAP; nil = no plank.
    var plankSeconds: Int?

    init(sets: [ExerciseSet], durationMinutes: Int = 20, plankSeconds: Int? = nil) {
        self.sets = sets
        self.durationMinutes = durationMinutes
        self.plankSeconds = plankSeconds
    }

    private enum CodingKeys: String, CodingKey { case sets, durationMinutes, plankSeconds }

    /// Plans saved while the plank was part of the round (and history records carrying them)
    /// had it in `sets`; it becomes the finisher.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let decoded = try container.decode([ExerciseSet].self, forKey: .sets)
        sets = decoded.filter { !$0.exercise.isHold }
        durationMinutes = try container.decodeIfPresent(Int.self, forKey: .durationMinutes) ?? 20
        plankSeconds = try container.decodeIfPresent(Int.self, forKey: .plankSeconds)
            ?? decoded.first { $0.exercise.isHold }?.target
    }

    /// The real WOD: 5 pull-ups, 10 push-ups, 15 squats, 20 minutes.
    static let cindy = WorkoutPlan(sets: [
        ExerciseSet(exercise: .pullUp, target: 5),
        ExerciseSet(exercise: .pushUp, target: 10),
        ExerciseSet(exercise: .squat, target: 15),
    ])
    /// AMRAP lengths on offer: Cindy is 20 minutes, shorter ones are the way in, and the
    /// progression advisor steps up by five.
    static let durationChoices = [5, 10, 15, 20]

    /// Allowed target per exercise: reps up to the Cindy prescription (more work comes from
    /// more rounds, not bigger sets), plank seconds in steps of five.
    static func targetRange(for exercise: Exercise) -> ClosedRange<Int> {
        exercise.isHold ? 5...300 : 1...WorkoutPlan.cindy.target(for: exercise)
    }

    /// Test plan for home use without a pull-up bar.
    static let withoutPullUps = WorkoutPlan(sets: [
        ExerciseSet(exercise: .pushUp, target: 10),
        ExerciseSet(exercise: .squat, target: 15),
    ])

    /// The round's exercises; the plank is not one of them.
    var exercises: [Exercise] { sets.map(\.exercise) }
    var hasPlank: Bool { plankSeconds != nil }
    var isValid: Bool { !sets.isEmpty && durationMinutes > 0 }
    var duration: TimeInterval { TimeInterval(durationMinutes * 60) }
    var first: Exercise { exercises[0] }
    var last: Exercise { exercises[exercises.count - 1] }

    /// Score units per round: reps for movements, 1 for a completed hold.
    var repsPerRound: Int { sets.reduce(0) { $0 + scoreUnits($1) } }

    func contains(_ exercise: Exercise) -> Bool { exercise.isHold ? hasPlank : exercises.contains(exercise) }
    func isFirst(_ exercise: Exercise) -> Bool { exercise == first }
    func isLast(_ exercise: Exercise) -> Bool { exercise == last }

    /// Target of the given exercise (reps or seconds).
    func target(for exercise: Exercise) -> Int {
        if exercise.isHold { return plankSeconds ?? exercise.defaultTarget }
        return sets.first { $0.exercise == exercise }?.target ?? exercise.defaultTarget
    }

    /// Reps the state machine counts for one round exercise.
    func countTarget(for exercise: Exercise) -> Int {
        target(for: exercise)
    }

    func next(after exercise: Exercise) -> Exercise {
        let index = exercises.firstIndex(of: exercise) ?? 0
        return exercises[(index + 1) % exercises.count]
    }

    func previous(before exercise: Exercise) -> Exercise {
        let index = exercises.firstIndex(of: exercise) ?? 0
        return exercises[(index - 1 + exercises.count) % exercises.count]
    }

    /// Score units of the round completed before `exercise` starts.
    func repsBefore(_ exercise: Exercise) -> Int {
        sets.prefix { $0.exercise != exercise }.reduce(0) { $0 + scoreUnits($1) }
    }

    /// "5 pull-ups · 10 push-ups · 15 squats": one round.
    var summary: String { sets.map(\.label).joined(separator: " · ") }

    /// The round plus the plank after it: "5 pull-ups · 10 push-ups · 15 squats, then 30 s plank".
    var summaryWithPlank: String {
        guard let plankSeconds else { return summary }
        let plankSummary = ExerciseSet(exercise: .plank, target: plankSeconds).label
        return L("\(summary), then \(plankSummary)")
    }

    private func scoreUnits(_ set: ExerciseSet) -> Int { set.target }

    // MARK: - Editing

    mutating func setEnabled(_ exercise: Exercise, _ enabled: Bool) {
        if exercise.isHold {
            plankSeconds = enabled ? plankSeconds ?? exercise.defaultTarget : nil
            return
        }
        if enabled {
            guard !contains(exercise) else { return }
            sets.append(ExerciseSet(exercise: exercise, target: exercise.defaultTarget))
        } else {
            sets.removeAll { $0.exercise == exercise }
        }
    }

    mutating func setTarget(_ target: Int, for exercise: Exercise) {
        if exercise.isHold {
            guard plankSeconds != nil else { return }
            let range = WorkoutPlan.targetRange(for: exercise)
            plankSeconds = min(max(target, range.lowerBound), range.upperBound)
            return
        }
        guard let index = sets.firstIndex(where: { $0.exercise == exercise }) else { return }
        let range = WorkoutPlan.targetRange(for: exercise)
        sets[index].target = min(max(target, range.lowerBound), range.upperBound)
    }

    /// Moves an exercise one place up (-1) or down (+1) in the round.
    mutating func move(_ exercise: Exercise, by offset: Int) {
        guard let index = sets.firstIndex(where: { $0.exercise == exercise }) else { return }
        let destination = index + offset
        guard sets.indices.contains(destination) else { return }
        sets.swapAt(index, destination)
    }

    /// The plan with durations and targets pulled into the allowed values, for plans saved
    /// before those limits existed (e.g. 12 minutes or 25 pull-ups).
    func normalized() -> WorkoutPlan {
        var plan = self
        plan.durationMinutes = WorkoutPlan.durationChoices.min {
            abs($0 - durationMinutes) < abs($1 - durationMinutes)
        } ?? 20
        for set in sets {
            plan.setTarget(set.target, for: set.exercise)
        }
        if let plankSeconds { plan.setTarget((plankSeconds + 2) / 5 * 5, for: .plank) }
        return plan
    }
}
