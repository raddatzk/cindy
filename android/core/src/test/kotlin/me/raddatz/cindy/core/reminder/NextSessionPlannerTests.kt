package me.raddatz.cindy.core.reminder

import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.health.Readiness
import me.raddatz.cindy.core.health.ReadinessScoring
import me.raddatz.cindy.core.persistence.WorkoutRecord
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextSessionPlannerTests {
    private val zone = ZoneOffset.UTC
    private val planner = NextSessionPlanner(zone = zone)

    private fun date(day: Int, hour: Int, minute: Int = 0): Instant =
        ZonedDateTime.of(2026, 6, day, hour, minute, 0, 0, zone).toInstant()

    /** A workout that ended at the given time. */
    private fun record(endingDay: Int, hour: Int, minute: Int = 0, duration: Double = 1200.0): WorkoutRecord =
        WorkoutRecord(
            date = date(endingDay, hour, minute), rounds = 10, extraReps = 0,
            durationSeconds = duration, completed = duration >= WorkoutPlan.cindy.duration,
            repsPerRound = 30, roundTimestamps = null, plan = WorkoutPlan.cindy,
        )

    private fun readiness(signal: Double?): Readiness? {
        val components = mutableListOf(Readiness.Component(Readiness.Component.Kind.RECOVERY, 50.0, 0.35))
        if (signal != null) {
            components += Readiness.Component(Readiness.Component.Kind.SLEEP, signal, 0.2)
        }
        return Readiness.from(components, usesHealthData = signal != null)
    }

    @Test
    fun withoutHistoryThereIsNothingToRemindAbout() {
        assertNull(planner.plan(emptyList(), readiness = null, now = date(1, 9)))
    }

    @Test
    fun aFullSessionIsFollowedTwoDaysLaterAtTheUsualTime() {
        // Three sessions ending 19:00, 20 minutes long, so the usual start is 18:40.
        val history = listOf(record(10, 19), record(7, 19), record(4, 19))
        val session = planner.plan(history, readiness = null, now = date(10, 20))
        assertEquals(date(12, 18, 40), session?.date)
        assertEquals(0.0, session?.extraRestHours)
    }

    @Test
    fun weakBodySignalsPushTheReminderBack() {
        val history = listOf(record(10, 19), record(7, 19), record(4, 19))
        val now = date(10, 20)
        val tired = planner.plan(history, readiness(20.0), now)
        val slightlyOff = planner.plan(history, readiness(55.0), now)
        assertEquals(20.0, tired?.extraRestHours)
        assertEquals(date(13, 18, 40), tired?.date)
        assertEquals(8.0, slightlyOff?.extraRestHours)
        assertEquals(date(13, 18, 40), slightlyOff?.date)
    }

    @Test
    fun extraRestGrowsSmoothlyInsteadOfJumping() {
        fun extra(signal: Double): Double = NextSessionPlanner.extraRestHours(readiness(signal))
        // An average day costs nothing, and nothing above it does either.
        assertEquals(0.0, extra(ReadinessScoring.NEUTRAL))
        assertEquals(0.0, extra(95.0))
        // Monotone downwards, and no single point decides more than an hour.
        val curve = (0..100).map { extra(it.toDouble()) }
        assertTrue(curve.zipWithNext().all { (a, b) -> a >= b })
        assertTrue(curve.zipWithNext().all { (a, b) -> a - b <= 1 })
        // Around the old 65-point step the difference is now hours, not half a day.
        assertTrue(extra(64.0) - extra(66.0) < 1)
        assertTrue(extra(66.0) > 0)
        assertEquals(24.0, extra(0.0))
    }

    @Test
    fun plainRecoveryAloneAddsNoExtraRest() {
        // Readiness is always low right after a workout; that must not be
        // counted a second time on top of the waiting period.
        assertEquals(0.0, NextSessionPlanner.extraRestHours(readiness(null)))
        assertNull(readiness(null)?.signalScore)
        assertEquals(20, readiness(20.0)?.signalScore)
    }

    @Test
    fun aShortSessionNeedsLessRest() {
        val history = listOf(record(10, 19, duration = 600.0))
        val session = planner.plan(history, readiness = null, now = date(10, 20))
        // 36 h instead of 48, so the day before — at the default hour with too
        // few sessions to know a usual time.
        assertEquals(date(12, 18), session?.date)
    }

    @Test
    fun anOverdueReminderLandsOnTheNextUpcomingSlot() {
        val history = listOf(record(1, 19))
        val sameEvening = planner.plan(history, readiness = null, now = date(20, 16))
        assertEquals(date(20, 18), sameEvening?.date)
        val afterTheSlot = planner.plan(history, readiness = null, now = date(20, 20))
        assertEquals(date(21, 18), afterTheSlot?.date)
    }

    @Test
    fun nightOwlHoursAreClampedIntoTheWindow() {
        val history = listOf(record(10, 3), record(7, 3), record(4, 3))
        val session = planner.plan(history, readiness = null, now = date(10, 4))
        assertEquals(7, session!!.date.atZone(zone).hour)
    }

    @Test
    fun theUsualTimeIsTheMedianStartOfRecentSessions() {
        val history = listOf(record(10, 7, 20), record(9, 18, 20), record(8, 19, 20))
        val usual = NextSessionPlanner.usualStartTime(history, zone)
        assertEquals(18, usual?.hour)
        assertEquals(0, usual?.minute) // 18:20 end minus the 20-minute workout
        assertNull(NextSessionPlanner.usualStartTime(history.take(2), zone))
    }
}
