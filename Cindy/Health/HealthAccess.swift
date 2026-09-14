import Foundation
import HealthKit

enum HealthError: LocalizedError {
    case unavailable
    case denied
    case failed

    var errorDescription: String? {
        switch self {
        case .unavailable: return L("Apple Health is not available on this device.")
        case .denied: return L("Cindy may not write to Apple Health. Allow it in Settings › Apps › Health › Data Access.")
        case .failed: return L("The workout could not be written to Apple Health.")
        }
    }
}

/// The one `HKHealthStore` and the one authorization request Cindy makes:
/// writing workouts plus their energy, reading the handful of values the
/// readiness estimate needs. Asking for everything at once means the user
/// decides about both directions in a single sheet — and HealthKit lets them
/// refuse each type individually right there.
final class HealthAccess {
    static let shared = HealthAccess()

    let store = HKHealthStore()

    static let workoutType = HKWorkoutType.workoutType()
    static let energyType = HKQuantityType(.activeEnergyBurned)
    static let bodyMassType = HKQuantityType(.bodyMass)
    static let heartRateVariabilityType = HKQuantityType(.heartRateVariabilitySDNN)
    static let restingHeartRateType = HKQuantityType(.restingHeartRate)
    static let sleepType = HKCategoryType(.sleepAnalysis)

    static var shareTypes: Set<HKSampleType> { [workoutType, energyType] }
    static var readTypes: Set<HKObjectType> {
        [bodyMassType, heartRateVariabilityType, restingHeartRateType, sleepType]
    }

    var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// Whether workouts may be written. Read permissions stay opaque by
    /// design — HealthKit never reveals them, so reads simply come back empty.
    var canWriteWorkouts: Bool {
        isAvailable && store.authorizationStatus(for: Self.workoutType) == .sharingAuthorized
    }

    /// Shows the permission sheet. Returns without error even when the user
    /// says no — check `canWriteWorkouts` afterwards.
    func requestAuthorization() async throws {
        guard isAvailable else { throw HealthError.unavailable }
        try await store.requestAuthorization(toShare: Self.shareTypes, read: Self.readTypes)
    }
}
