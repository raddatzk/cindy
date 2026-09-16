package me.raddatz.cindy.core.reminder

import me.raddatz.cindy.core.addingSeconds
import me.raddatz.cindy.core.health.Readiness
import me.raddatz.cindy.core.health.ReadinessScoring
import me.raddatz.cindy.core.persistence.WorkoutRecord
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** When the next session should happen, and why. */
data class NextSession(
    val date: Instant,
    /** Hours added on top of the plain recovery time because the body signals asked for them. */
    val extraRestHours: Double,
    /** Which notification body to show. */
    val reason: NextSessionReason,
)

/** Typed replacement for the localized iOS `NextSession.reason`. */
enum class NextSessionReason {
    /** "Your body asked for an extra day. Now you should be good to go." (16 h or more of extra rest) */
    EXTRA_DAY,

    /** "A little later than usual — your body was still catching up." (2 h or more) */
    LATER_THAN_USUAL,

    /** "You should be recovered. Ready for the next round?" */
    RECOVERED,
}

/**
 * Picks the moment to nudge: as early as recovery from the last session allows, later when
 * today's signals say that session cost more than its duration suggests, and snapped to the time
 * of day the athlete usually trains.
 *
 * [zone] is the time zone the usual training time and the slot are computed in (iOS: the
 * current calendar).
 */
data class NextSessionPlanner(
    val zone: ZoneId = ZoneId.systemDefault(),
    /** A reminder never fires outside this window, whatever the history says. */
    val earliestHour: Int = 7,
    val latestHour: Int = 21,
    /** Used until there are enough workouts to see a pattern. */
    val defaultHour: Int = 18,
    /** Workouts looked at for the usual training time. */
    val timeOfDaySampleSize: Int = 10,
    /**
     * How far before the computed ready time the usual slot may still be used. Without it, being
     * ready at 19:00 when the usual slot is 18:40 would cost a whole day, and the recovery hours
     * are a rule of thumb rather than a deadline.
     */
    val grace: Duration = Duration.ofHours(2),
) {
    fun plan(history: List<WorkoutRecord>, readiness: Readiness?, now: Instant): NextSession? {
        val last = history.maxByOrNull { it.date } ?: return null
        val extra = extraRestHours(readiness)
        val ready = last.date.addingSeconds((ReadinessScoring.recoveryHours(last) + extra) * 3600)
        val target = maxOf(ready.minus(grace), now)
        return NextSession(
            date = slot(target, history),
            extraRestHours = extra,
            reason = reason(extra),
        )
    }

    /** The first usual training time at or after [date]. */
    private fun slot(date: Instant, history: List<WorkoutRecord>): Instant {
        val usual = usualStartTime(history, zone, sampleSize = timeOfDaySampleSize)
        val day = date.atZone(zone).toLocalDate()
        val hour = minOf(maxOf(usual?.hour ?: defaultHour, earliestHour), latestHour)
        val minute = usual?.minute ?: 0
        val candidate = ZonedDateTime.of(day, LocalTime.of(hour, minute), zone)
        if (!candidate.toInstant().isBefore(date)) return candidate.toInstant()
        return candidate.plusDays(1).toInstant()
    }

    companion object {
        /**
         * Signal score → hours of extra rest. An average day adds nothing and the curve grows
         * smoothly to about a day when everything is off.
         *
         * Continuous on purpose: with stepped thresholds a point either way decided half a day,
         * which is far more precision than a score built from a handful of noisy daily values can
         * carry.
         */
        val extraRestCurve: List<Pair<Double, Double>> = listOf(
            0.0 to 24.0, 30.0 to 18.0, 45.0 to 12.0, 65.0 to 4.0, ReadinessScoring.NEUTRAL to 0.0, 100.0 to 0.0,
        )

        /**
         * Rest on top of the recovery time. Deliberately based on [Readiness.signalScore], not the
         * total: the total is already low right after a session, and adding that to the waiting
         * time would count the same fatigue twice.
         */
        fun extraRestHours(readiness: Readiness?): Double {
            val signal = readiness?.signalScore ?: return 0.0
            return ReadinessScoring.interpolate(signal.toDouble(), extraRestCurve)
        }

        fun reason(extraRestHours: Double): NextSessionReason = when {
            extraRestHours >= 16 -> NextSessionReason.EXTRA_DAY
            extraRestHours >= 2 -> NextSessionReason.LATER_THAN_USUAL
            else -> NextSessionReason.RECOVERED
        }

        /**
         * When the athlete usually starts a workout, as hour and minute in [zone].
         * `null` below [minimumSessions], where there is no pattern to follow.
         */
        fun usualStartTime(
            history: List<WorkoutRecord>,
            zone: ZoneId,
            sampleSize: Int = 10,
            minimumSessions: Int = 3,
        ): LocalTime? {
            val starts = history
                .sortedByDescending { it.date }
                .take(sampleSize)
                // `date` is when the workout ended; the nudge belongs at its start.
                .map { it.date.addingSeconds(-it.durationSeconds) }
            if (starts.size < minimumSessions) return null
            val minutes = starts
                .map { val local = it.atZone(zone); local.hour * 60 + local.minute }
                .sorted()
            val median = minutes[minutes.size / 2]
            return LocalTime.of(median / 60, median % 60)
        }
    }
}
