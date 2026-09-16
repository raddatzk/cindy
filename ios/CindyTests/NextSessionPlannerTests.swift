import Foundation
import Testing
@testable import Cindy

struct NextSessionPlannerTests {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private var planner: NextSessionPlanner {
        NextSessionPlanner(calendar: calendar)
    }

    private func date(day: Int, hour: Int, minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 6, day: day, hour: hour, minute: minute))!
    }

    /// A workout that ended at the given time.
    private func record(endingDay day: Int, hour: Int, minute: Int = 0,
                        duration: TimeInterval = 1200) -> WorkoutRecord {
        WorkoutRecord(date: date(day: day, hour: hour, minute: minute), rounds: 10, extraReps: 0,
                      durationSeconds: duration, completed: duration >= WorkoutPlan.cindy.duration,
                      repsPerRound: 30, roundTimestamps: nil, plan: .cindy)
    }

    private func readiness(signal: Double?) -> Readiness? {
        var components = [Readiness.Component(kind: .recovery, score: 50, weight: 0.35, detail: "")]
        if let signal {
            components.append(Readiness.Component(kind: .sleep, score: signal, weight: 0.2, detail: ""))
        }
        return Readiness(components: components, usesHealthData: signal != nil)
    }

    @Test func withoutHistoryThereIsNothingToRemindAbout() {
        #expect(planner.plan(history: [], readiness: nil, now: date(day: 1, hour: 9)) == nil)
    }

    @Test func aFullSessionIsFollowedTwoDaysLaterAtTheUsualTime() {
        // Three sessions ending 19:00, 20 minutes long, so the usual start is 18:40.
        let history = [record(endingDay: 10, hour: 19), record(endingDay: 7, hour: 19),
                       record(endingDay: 4, hour: 19)]
        let session = planner.plan(history: history, readiness: nil, now: date(day: 10, hour: 20))
        #expect(session?.date == date(day: 12, hour: 18, minute: 40))
        #expect(session?.extraRestHours == 0)
    }

    @Test func weakBodySignalsPushTheReminderBack() {
        let history = [record(endingDay: 10, hour: 19), record(endingDay: 7, hour: 19),
                       record(endingDay: 4, hour: 19)]
        let now = date(day: 10, hour: 20)
        let tired = planner.plan(history: history, readiness: readiness(signal: 20), now: now)
        let slightlyOff = planner.plan(history: history, readiness: readiness(signal: 55), now: now)
        #expect(tired?.extraRestHours == 20)
        #expect(tired?.date == date(day: 13, hour: 18, minute: 40))
        #expect(slightlyOff?.extraRestHours == 8)
        #expect(slightlyOff?.date == date(day: 13, hour: 18, minute: 40))
    }

    @Test func extraRestGrowsSmoothlyInsteadOfJumping() {
        func extra(_ signal: Double) -> Double {
            NextSessionPlanner.extraRestHours(for: readiness(signal: signal))
        }
        // An average day costs nothing, and nothing above it does either.
        #expect(extra(ReadinessScoring.neutral) == 0)
        #expect(extra(95) == 0)
        // Monotone downwards, and no single point decides more than an hour.
        let curve = stride(from: 0.0, through: 100.0, by: 1).map(extra)
        #expect(zip(curve, curve.dropFirst()).allSatisfy { $0 >= $1 })
        #expect(zip(curve, curve.dropFirst()).allSatisfy { $0 - $1 <= 1 })
        // Around the old 65-point step the difference is now hours, not half a day.
        #expect(extra(64) - extra(66) < 1)
        #expect(extra(66) > 0)
        #expect(extra(0) == 24)
    }

    @Test func plainRecoveryAloneAddsNoExtraRest() {
        // Readiness is always low right after a workout; that must not be
        // counted a second time on top of the waiting period.
        #expect(NextSessionPlanner.extraRestHours(for: readiness(signal: nil)) == 0)
        #expect(readiness(signal: nil)?.signalScore == nil)
        #expect(readiness(signal: 20)?.signalScore == 20)
    }

    @Test func aShortSessionNeedsLessRest() {
        let history = [record(endingDay: 10, hour: 19, duration: 600)]
        let session = planner.plan(history: history, readiness: nil, now: date(day: 10, hour: 20))
        // 36 h instead of 48, so the day before — at the default hour with too
        // few sessions to know a usual time.
        #expect(session?.date == date(day: 12, hour: 18))
    }

    @Test func anOverdueReminderLandsOnTheNextUpcomingSlot() {
        let history = [record(endingDay: 1, hour: 19)]
        let sameEvening = planner.plan(history: history, readiness: nil, now: date(day: 20, hour: 16))
        #expect(sameEvening?.date == date(day: 20, hour: 18))
        let afterTheSlot = planner.plan(history: history, readiness: nil, now: date(day: 20, hour: 20))
        #expect(afterTheSlot?.date == date(day: 21, hour: 18))
    }

    @Test func nightOwlHoursAreClampedIntoTheWindow() {
        let history = [record(endingDay: 10, hour: 3), record(endingDay: 7, hour: 3),
                       record(endingDay: 4, hour: 3)]
        let session = planner.plan(history: history, readiness: nil, now: date(day: 10, hour: 4))
        #expect(calendar.component(.hour, from: session!.date) == 7)
    }

    @Test func theUsualTimeIsTheMedianStartOfRecentSessions() {
        let history = [record(endingDay: 10, hour: 7, minute: 20), record(endingDay: 9, hour: 18, minute: 20),
                       record(endingDay: 8, hour: 19, minute: 20)]
        let usual = NextSessionPlanner.usualStartTime(of: history, calendar: calendar)
        #expect(usual?.hour == 18)
        #expect(usual?.minute == 0) // 18:20 end minus the 20-minute workout
        #expect(NextSessionPlanner.usualStartTime(of: Array(history.prefix(2)), calendar: calendar) == nil)
    }
}
