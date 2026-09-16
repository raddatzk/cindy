package me.raddatz.cindy.core.health

import me.raddatz.cindy.core.DateInterval
import java.time.Duration
import java.time.Instant

/**
 * Pure helpers that turn health-store series (HealthKit on iOS, Health Connect on Android) into
 * readiness inputs. Separate from the queries so the arithmetic can be unit-tested.
 */
object HealthSeries {
    /** One day's aggregate of a metric. */
    data class DailyValue(val date: Instant, val value: Double)

    /**
     * The newest day's value together with the earlier days it is judged against. `null` when
     * the newest value is stale — a readiness estimate built on last week's HRV would be worse
     * than none at all.
     */
    fun baseline(
        values: List<DailyValue>,
        now: Instant,
        freshness: Duration = Duration.ofHours(36),
        minimumDays: Int = 10,
    ): MetricBaseline? {
        val sorted = values.sortedBy { it.date }
        val latest = sorted.lastOrNull() ?: return null
        if (Duration.between(latest.date, now) > freshness) return null
        return MetricBaseline.fromHistory(
            current = latest.value,
            earlierDailyValues = sorted.dropLast(1).map { it.value },
            minimumDays = minimumDays,
        )
    }

    /**
     * Total time asleep in hours. Watch and phone can both write the same night, so overlapping
     * intervals are merged instead of added up.
     */
    fun asleepHours(intervals: List<DateInterval>): Double {
        val sorted = intervals.sortedBy { it.start }
        val merged = mutableListOf<DateInterval>()
        for (interval in sorted) {
            val last = merged.lastOrNull()
            if (last != null && interval.start <= last.end) {
                merged[merged.size - 1] = DateInterval(last.start, maxOf(last.end, interval.end))
            } else {
                merged += interval
            }
        }
        return merged.fold(0.0) { sum, interval -> sum + interval.duration } / 3600
    }
}
