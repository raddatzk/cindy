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

/// Ordered list of exercises that make up one round, plus the AMRAP duration.
struct WorkoutPlan: Codable, Hashable, Sendable {
    var sets: [ExerciseSet]
    var durationMinutes: Int = 20

    /// The real WOD: 5 pull-ups, 10 push-ups, 15 squats, 20 minutes.
    static let cindy = WorkoutPlan(sets: [
        ExerciseSet(exercise: .pullUp, target: 5),
        ExerciseSet(exercise: .pushUp, target: 10),
        ExerciseSet(exercise: .squat, target: 15),
    ])
    /// Test plan for home use without a pull-up bar.
    static let withoutPullUps = WorkoutPlan(sets: [
        ExerciseSet(exercise: .pushUp, target: 10),
        ExerciseSet(exercise: .squat, target: 15),
    ])

    var exercises: [Exercise] { sets.map(\.exercise) }
    var isValid: Bool { !sets.isEmpty && durationMinutes > 0 }
    var duration: TimeInterval { TimeInterval(durationMinutes * 60) }
    var first: Exercise { exercises[0] }
    var last: Exercise { exercises[exercises.count - 1] }

    /// Score units per round: reps for movements, 1 for a completed hold.
    var repsPerRound: Int { sets.reduce(0) { $0 + scoreUnits($1) } }

    func contains(_ exercise: Exercise) -> Bool { exercises.contains(exercise) }
    func isFirst(_ exercise: Exercise) -> Bool { exercise == first }
    func isLast(_ exercise: Exercise) -> Bool { exercise == last }

    /// Target of the given exercise (reps or seconds).
    func target(for exercise: Exercise) -> Int {
        sets.first { $0.exercise == exercise }?.target ?? exercise.defaultTarget
    }

    /// Reps the state machine counts for one exercise: holds count as a single rep.
    func countTarget(for exercise: Exercise) -> Int {
        exercise.isHold ? 1 : target(for: exercise)
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

    /// "5 pull-ups · 10 push-ups · 15 squats"
    var summary: String { sets.map(\.label).joined(separator: " · ") }

    private func scoreUnits(_ set: ExerciseSet) -> Int { set.exercise.isHold ? 1 : set.target }

    // MARK: - Editing

    mutating func setEnabled(_ exercise: Exercise, _ enabled: Bool) {
        if enabled {
            guard !contains(exercise) else { return }
            sets.append(ExerciseSet(exercise: exercise, target: exercise.defaultTarget))
        } else {
            sets.removeAll { $0.exercise == exercise }
        }
    }

    mutating func setTarget(_ target: Int, for exercise: Exercise) {
        guard let index = sets.firstIndex(where: { $0.exercise == exercise }) else { return }
        sets[index].target = max(1, target)
    }
}
