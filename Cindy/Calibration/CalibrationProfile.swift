import Foundation

/// Calibration result for one exercise.
struct ExerciseCalibration: Codable, Equatable, Sendable {
    var source: SignalSource
    var minValue: Float
    var maxValue: Float
    var baseline: Float
    var low: Float
    var high: Float
    var direction: RepDirection
    var repDuration: TimeInterval
    var calibratedAt: Date

    var thresholds: RepThresholds {
        RepThresholds(low: low, high: high, direction: direction)
    }

    var range: Float { maxValue - minValue }
}

/// All three exercises; stored as JSON in the Documents directory.
struct CalibrationProfile: Codable, Equatable, Sendable {
    static let currentVersion = 1

    var version: Int = CalibrationProfile.currentVersion
    var createdAt: Date = Date()
    var exercises: [String: ExerciseCalibration] = [:]

    func calibration(for exercise: Exercise) -> ExerciseCalibration? {
        exercises[exercise.rawValue]
    }

    mutating func set(_ calibration: ExerciseCalibration, for exercise: Exercise) {
        exercises[exercise.rawValue] = calibration
    }

    /// Only a profile covering every exercise of the plan can start a workout.
    func isComplete(for plan: WorkoutPlan) -> Bool {
        plan.exercises.allSatisfy { exercises[$0.rawValue] != nil }
    }

    var isComplete: Bool { isComplete(for: .cindy) }

    func missingExercises(for plan: WorkoutPlan) -> [Exercise] {
        plan.exercises.filter { exercises[$0.rawValue] == nil }
    }
}

/// Loads and saves the calibration profile.
final class CalibrationStore {
    private let store: JSONFileStore<CalibrationProfile>

    init(fileName: String = "calibration.json") {
        store = JSONFileStore(fileName: fileName)
    }

    init(url: URL) {
        store = JSONFileStore(url: url)
    }

    func load() -> CalibrationProfile? {
        guard let profile = store.load(), profile.version == CalibrationProfile.currentVersion else { return nil }
        return profile
    }

    func save(_ profile: CalibrationProfile) throws {
        try store.save(profile)
    }

    func delete() {
        store.delete()
    }
}
