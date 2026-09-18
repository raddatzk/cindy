import Foundation
import HealthKit

/// Writes finished workouts to Apple Health.
///
/// Which records already went to Health is remembered locally, so the app
/// never needs read access to the workout store just to avoid duplicates.
/// `Sendable`: the exported ids live in `UserDefaults`, not in a stored
/// property, so there is no mutable state of its own to race over.
final class HealthExporter: Sendable {
    static let shared = HealthExporter()

    private let access: HealthAccess
    /// `UserDefaults` is documented as thread-safe but predates `Sendable`, so
    /// the compiler cannot see that and has to be told.
    nonisolated(unsafe) private let defaults: UserDefaults
    private static let exportedIDsKey = "healthExportedWorkoutIDs"
    /// Prefix for Cindy's own metadata keys, which Health passes through untouched.
    private static let metadataPrefix = "me.raddatz.cindy."

    init(access: HealthAccess = .shared, defaults: UserDefaults = .standard) {
        self.access = access
        self.defaults = defaults
    }

    var isAvailable: Bool { access.isAvailable }
    var isAuthorized: Bool { access.canWriteWorkouts }

    func requestAuthorization() async throws {
        try await access.requestAuthorization()
    }

    /// Writes one record as a cross-training session with a segment per round.
    /// Returns false when the record was already in Health.
    @discardableResult
    func export(_ record: WorkoutRecord) async throws -> Bool {
        guard isAvailable else { throw HealthError.unavailable }
        guard !hasExported(record.id) else { return false }
        guard isAuthorized else { throw HealthError.denied }

        let bodyMass = await HealthMetricsReader(access: access).bodyMassKilograms()
        let sample = WorkoutHealthSample(record: record, bodyMassKilograms: bodyMass)
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = .crossTraining
        configuration.locationType = .indoor

        let builder = HKWorkoutBuilder(healthStore: access.store, configuration: configuration, device: .local())
        try await builder.beginCollection(at: sample.start)
        if let kilocalories = sample.activeEnergyKilocalories {
            let quantity = HKQuantity(unit: .kilocalorie(), doubleValue: kilocalories)
            try await builder.addSamples([HKQuantitySample(type: HealthAccess.energyType, quantity: quantity,
                                                           start: sample.start, end: sample.end)])
        }
        for round in sample.rounds {
            let activity = HKWorkoutActivity(workoutConfiguration: configuration,
                                             start: round.start, end: round.end,
                                             metadata: [Self.metadataPrefix + "round": round.index])
            try await builder.addWorkoutActivity(activity)
        }
        try await builder.addMetadata(metadata(for: record, sample: sample))
        try await builder.endCollection(at: sample.end)
        _ = try await builder.finishWorkout()

        markExported(record.id)
        return true
    }

    /// Writes every record that has not been exported yet. Returns how many were added.
    @discardableResult
    func exportMissing(from history: [WorkoutRecord]) async throws -> Int {
        var exported = 0
        for record in history where !hasExported(record.id) {
            if try await export(record) { exported += 1 }
        }
        return exported
    }

    func hasExported(_ id: UUID) -> Bool { exportedIDs.contains(id.uuidString) }

    /// Records that still have to go to Health — drives the backfill button.
    func pendingCount(in history: [WorkoutRecord]) -> Int {
        history.filter { !hasExported($0.id) }.count
    }

    // MARK: - Private

    private func metadata(for record: WorkoutRecord, sample: WorkoutHealthSample) -> [String: Any] {
        var metadata: [String: Any] = [
            HKMetadataKeyExternalUUID: record.id.uuidString,
            HKMetadataKeyIndoorWorkout: true,
            Self.metadataPrefix + "score": sample.score,
            Self.metadataPrefix + "rounds": record.rounds,
            Self.metadataPrefix + "totalReps": sample.totalReps,
            Self.metadataPrefix + "completed": sample.completed,
        ]
        if let plan = record.plan {
            metadata[Self.metadataPrefix + "plan"] = plan.summaryWithPlank
        }
        return metadata
    }

    private var exportedIDs: Set<String> {
        get { Set(defaults.stringArray(forKey: Self.exportedIDsKey) ?? []) }
        set { defaults.set(Array(newValue), forKey: Self.exportedIDsKey) }
    }

    private func markExported(_ id: UUID) {
        exportedIDs.insert(id.uuidString)
    }
}
