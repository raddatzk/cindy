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

    /// Relative to the calibrated baseline; `SignalPipeline` drops the baseline for
    /// signals that do not scale with distance.
    var thresholds: RepThresholds {
        RepThresholds(low: low, high: high, direction: direction, baseline: baseline)
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

    /// Drops calibrations measured on a different signal source than the config uses now
    /// (squats moved from the face to body pose); those exercises need a new calibration.
    func removingOutdated(for config: SignalConfig) -> CalibrationProfile {
        var profile = self
        profile.exercises = exercises.filter { key, calibration in
            guard let exercise = Exercise(rawValue: key) else { return false }
            return calibration.source == config.source(for: exercise)
        }
        return profile
    }
}

/// Loads and saves the calibration profile.
final class CalibrationStore {
    private let store: JSONFileStore<CalibrationProfile>
    private let config: SignalConfig

    init(fileName: String = "calibration.json", config: SignalConfig = .default) {
        store = JSONFileStore(fileName: fileName)
        self.config = config
    }

    init(url: URL, config: SignalConfig = .default) {
        store = JSONFileStore(url: url)
        self.config = config
    }

    /// The stored profile without calibrations for a signal source the config no longer uses.
    func load() -> CalibrationProfile? {
        guard let profile = store.load(), profile.version == CalibrationProfile.currentVersion else { return nil }
        return profile.removingOutdated(for: config)
    }

    func save(_ profile: CalibrationProfile) throws {
        try store.save(profile)
    }

    func delete() {
        store.delete()
    }
}
