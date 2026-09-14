import Foundation

/// Pure helpers that turn HealthKit series into readiness inputs.
/// Separate from the queries so the arithmetic can be unit-tested.
enum HealthSeries {
    /// One day's aggregate of a metric.
    struct DailyValue: Equatable {
        var date: Date
        var value: Double
    }

    /// The newest day's value together with the earlier days it is judged
    /// against. `nil` when the newest value is stale — a readiness estimate
    /// built on last week's HRV would be worse than none at all.
    static func baseline(from values: [DailyValue], now: Date,
                         freshness: TimeInterval = 36 * 3600,
                         minimumDays: Int = 10) -> MetricBaseline? {
        let sorted = values.sorted { $0.date < $1.date }
        guard let latest = sorted.last, now.timeIntervalSince(latest.date) <= freshness else { return nil }
        return MetricBaseline(current: latest.value,
                              earlierDailyValues: sorted.dropLast().map(\.value),
                              minimumDays: minimumDays)
    }

    /// Total time asleep in hours. Watch and iPhone can both write the same
    /// night, so overlapping intervals are merged instead of added up.
    static func asleepHours(_ intervals: [DateInterval]) -> Double {
        let sorted = intervals.sorted { $0.start < $1.start }
        var merged: [DateInterval] = []
        for interval in sorted {
            if let last = merged.last, interval.start <= last.end {
                merged[merged.count - 1] = DateInterval(start: last.start, end: max(last.end, interval.end))
            } else {
                merged.append(interval)
            }
        }
        return merged.reduce(0) { $0 + $1.duration } / 3600
    }
}
