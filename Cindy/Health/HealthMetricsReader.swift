import Foundation
import HealthKit

/// Reads the values the readiness estimate is built from. Read-only, and
/// forgiving: every query that fails or comes back empty — which is what a
/// refused read permission looks like — simply drops its component.
struct HealthMetricsReader {
    var access: HealthAccess = .shared
    /// Days of history the personal baseline is taken over.
    var baselineDays = 60

    func read(now: Date = Date()) async -> HealthMetrics {
        guard access.isAvailable else { return .none }
        async let heartRateVariability = baseline(for: HealthAccess.heartRateVariabilityType,
                                                  unit: .secondUnit(with: .milli), now: now)
        async let restingHeartRate = baseline(for: HealthAccess.restingHeartRateType,
                                              unit: .count().unitDivided(by: .minute()), now: now)
        async let sleep = sleepHours(now: now)
        async let bodyMass = bodyMassKilograms()
        return await HealthMetrics(heartRateVariability: heartRateVariability,
                                   restingHeartRate: restingHeartRate,
                                   sleepHours: sleep,
                                   bodyMassKilograms: bodyMass)
    }

    /// Latest daily average of a metric against the personal baseline.
    private func baseline(for type: HKQuantityType, unit: HKUnit, now: Date) async -> MetricBaseline? {
        let calendar = Calendar.current
        guard let start = calendar.date(byAdding: .day, value: -baselineDays, to: now) else { return nil }
        let descriptor = HKStatisticsCollectionQueryDescriptor(
            predicate: .quantitySample(type: type,
                                       predicate: HKQuery.predicateForSamples(withStart: start, end: now)),
            options: .discreteAverage,
            anchorDate: calendar.startOfDay(for: now),
            intervalComponents: DateComponents(day: 1))
        guard let collection = try? await descriptor.result(for: access.store) else { return nil }
        let daily = collection.statistics().compactMap { statistics -> HealthSeries.DailyValue? in
            guard let average = statistics.averageQuantity()?.doubleValue(for: unit) else { return nil }
            return HealthSeries.DailyValue(date: statistics.startDate, value: average)
        }
        return HealthSeries.baseline(from: daily, now: now)
    }

    /// Time asleep last night. The window starts at noon the day before, so it
    /// covers the night whether the app asks at 6 in the morning or at 11 at night.
    private func sleepHours(now: Date) async -> Double? {
        let calendar = Calendar.current
        guard let yesterday = calendar.date(byAdding: .day, value: -1, to: calendar.startOfDay(for: now)) else {
            return nil
        }
        let windowStart = yesterday.addingTimeInterval(12 * 3600)
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.categorySample(type: HealthAccess.sleepType,
                                         predicate: HKQuery.predicateForSamples(withStart: windowStart, end: now))],
            sortDescriptors: [])
        guard let samples = try? await descriptor.result(for: access.store) else { return nil }
        let asleep = samples
            .filter { HealthMetricsReader.asleepValues.contains($0.value) }
            .map { DateInterval(start: $0.startDate, end: max($0.startDate, $0.endDate)) }
        guard !asleep.isEmpty else { return nil }
        return HealthSeries.asleepHours(asleep)
    }

    /// Latest body weight, also used for the energy estimate of an exported workout.
    func bodyMassKilograms() async -> Double? {
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.quantitySample(type: HealthAccess.bodyMassType)],
            sortDescriptors: [SortDescriptor(\.endDate, order: .reverse)],
            limit: 1)
        let samples = try? await descriptor.result(for: access.store)
        return samples?.first?.quantity.doubleValue(for: .gramUnit(with: .kilo))
    }

    /// Time in bed and awake phases do not count as sleep.
    private static let asleepValues: Set<Int> = [
        HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue,
        HKCategoryValueSleepAnalysis.asleepCore.rawValue,
        HKCategoryValueSleepAnalysis.asleepDeep.rawValue,
        HKCategoryValueSleepAnalysis.asleepREM.rawValue,
    ]
}
