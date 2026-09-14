import Foundation

enum WorkoutPhase: Equatable, Sendable {
    case idle
    case countdown
    /// Counting reps of `exercise`.
    case active
    /// Last rep of an exercise done; waiting for the next exercise's signal to stabilise.
    case transition
    case paused
    case finished
}

enum WorkoutEvent: Equatable, Sendable {
    case started
    case repCounted(Exercise, count: Int)
    case repRemoved(Exercise, count: Int)
    case exerciseCompleted(Exercise, next: Exercise)
    case exerciseReopened(Exercise)
    case exerciseStarted(Exercise)
    case roundCompleted(Int)
    case roundReopened(Int)
    case finished
}

/// CrossFit notation: full rounds plus reps of the unfinished round ("14 + 7").
struct WorkoutScore: Equatable, Codable, Sendable {
    var rounds: Int
    var reps: Int
    var repsPerRound: Int = WorkoutPlan.cindy.repsPerRound

    var notation: String { reps == 0 ? "\(rounds)" : "\(rounds) + \(reps)" }
    var totalReps: Int { rounds * repsPerRound + reps }
}

/// Pure state machine for the AMRAP: exercise order, rep counter, rounds and
/// manual corrections. Time is handled by the engine that drives it.
final class WorkoutStateMachine {
    let plan: WorkoutPlan
    private(set) var phase: WorkoutPhase = .idle
    private(set) var exercise: Exercise
    private(set) var repCount = 0
    private(set) var completedRounds = 0
    private var phaseBeforePause: WorkoutPhase = .active

    init(plan: WorkoutPlan = .cindy) {
        self.plan = plan
        self.exercise = plan.first
    }

    var score: WorkoutScore {
        WorkoutScore(rounds: completedRounds, reps: plan.repsBefore(exercise) + repCount,
                     repsPerRound: plan.repsPerRound)
    }

    /// Round number shown to the athlete (1-based, the one currently in progress).
    var currentRound: Int { completedRounds + 1 }

    var isRunning: Bool { phase == .active || phase == .transition }

    // MARK: - Lifecycle

    func beginCountdown() {
        guard phase == .idle else { return }
        phase = .countdown
    }

    /// Countdown finished: the first exercise waits for its signal to stabilise.
    @discardableResult
    func start() -> [WorkoutEvent] {
        guard phase == .countdown || phase == .idle else { return [] }
        phase = .transition
        return [.started]
    }

    /// The next exercise's signal is stable; reps count from now on.
    @discardableResult
    func activate() -> [WorkoutEvent] {
        guard phase == .transition else { return [] }
        phase = .active
        return [.exerciseStarted(exercise)]
    }

    func pause() {
        guard isRunning else { return }
        phaseBeforePause = phase
        phase = .paused
    }

    func resume() {
        guard phase == .paused else { return }
        // Always come back through a transition so the detector re-arms.
        phase = .transition
    }

    @discardableResult
    func finish() -> [WorkoutEvent] {
        guard phase != .finished, phase != .idle else { return [] }
        phase = .finished
        return [.finished]
    }

    // MARK: - Counting

    /// A rep detected by the signal chain (only accepted while active).
    @discardableResult
    func registerRep() -> [WorkoutEvent] {
        guard phase == .active else { return [] }
        return increment()
    }

    /// Manual +1 / −1 correction; allowed while active, in transition or paused.
    @discardableResult
    func adjust(by delta: Int) -> [WorkoutEvent] {
        guard phase == .active || phase == .transition || phase == .paused else { return [] }
        switch delta {
        case 1: return increment()
        case -1: return decrement()
        default: return []
        }
    }

    private func increment() -> [WorkoutEvent] {
        repCount += 1
        var events: [WorkoutEvent] = [.repCounted(exercise, count: repCount)]
        if repCount >= plan.countTarget(for: exercise) {
            let finished = exercise
            if plan.isLast(finished) {
                completedRounds += 1
                events.append(.roundCompleted(completedRounds))
            }
            exercise = plan.next(after: finished)
            repCount = 0
            events.append(.exerciseCompleted(finished, next: exercise))
            if phase == .active {
                phase = .transition
            }
        }
        return events
    }

    private func decrement() -> [WorkoutEvent] {
        if repCount > 0 {
            repCount -= 1
            return [.repRemoved(exercise, count: repCount)]
        }
        // At 0: step back into the previous exercise (undo a wrongly completed exercise).
        guard completedRounds > 0 || !plan.isFirst(exercise) else { return [] }
        var events: [WorkoutEvent] = []
        let previous = plan.previous(before: exercise)
        if plan.isLast(previous) {
            completedRounds -= 1
            events.append(.roundReopened(completedRounds + 1))
        }
        exercise = previous
        repCount = plan.countTarget(for: previous) - 1
        events.append(.exerciseReopened(previous))
        events.append(.repRemoved(previous, count: repCount))
        if phase == .active {
            phase = .transition
        }
        return events
    }
}
