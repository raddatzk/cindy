import Foundation

/// One finished (or aborted) workout.
struct WorkoutRecord: Codable, Hashable, Identifiable, Sendable {
    var id: UUID = UUID()
    var date: Date
    var rounds: Int
    var extraReps: Int
    var durationSeconds: TimeInterval
    /// false when the workout was stopped before the planned time was up.
    var completed: Bool
    var repsPerRound: Int = WorkoutPlan.cindy.repsPerRound
    /// Elapsed seconds at which each round was completed.
    var roundTimestamps: [TimeInterval]?
    /// The plan that was performed.
    var plan: WorkoutPlan?

    var score: WorkoutScore { WorkoutScore(rounds: rounds, reps: extraReps, repsPerRound: repsPerRound) }

    /// Full AMRAP duration of the plan that was performed. Records written
    /// before plans were stored fall back to the classic 20 minutes.
    var plannedDuration: TimeInterval { plan?.duration ?? WorkoutPlan.cindy.duration }
}

final class HistoryStore {
    private let store: JSONFileStore<[WorkoutRecord]>

    init(fileName: String = "history.json") {
        store = JSONFileStore(fileName: fileName)
    }

    init(url: URL) {
        store = JSONFileStore(url: url)
    }

    /// Newest first.
    func load() -> [WorkoutRecord] {
        (store.load() ?? []).sorted { $0.date > $1.date }
    }

    func append(_ record: WorkoutRecord) throws {
        var records = load()
        records.insert(record, at: 0)
        try store.save(records)
    }

    func delete(id: UUID) throws {
        let records = load().filter { $0.id != id }
        try store.save(records)
    }
}
