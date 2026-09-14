import Foundation

/// When the next session should happen, and why.
struct NextSession: Equatable {
    var date: Date
    /// Hours added on top of the plain recovery time because the body signals
    /// asked for them.
    var extraRestHours: Double
    /// Localized one-liner, used as the notification body.
    var reason: String
}

/// Picks the moment to nudge: as early as recovery from the last session
/// allows, later when today's signals say that session cost more than its
/// duration suggests, and snapped to the time of day the athlete usually trains.
struct NextSessionPlanner {
    var calendar: Calendar = .current
    /// A reminder never fires outside this window, whatever the history says.
    var earliestHour = 7
    var latestHour = 21
    /// Used until there are enough workouts to see a pattern.
    var defaultHour = 18
    /// Workouts looked at for the usual training time.
    var timeOfDaySampleSize = 10
    /// How far before the computed ready time the usual slot may still be
    /// used. Without it, being ready at 19:00 when the usual slot is 18:40
    /// would cost a whole day, and the recovery hours are a rule of thumb
    /// rather than a deadline.
    var grace: TimeInterval = 2 * 3600

    func plan(history: [WorkoutRecord], readiness: Readiness?, now: Date) -> NextSession? {
        guard let last = history.max(by: { $0.date < $1.date }) else { return nil }
        let extra = NextSessionPlanner.extraRestHours(for: readiness)
        let ready = last.date.addingTimeInterval((ReadinessScoring.recoveryHours(for: last) + extra) * 3600)
        let target = max(ready.addingTimeInterval(-grace), now)
        return NextSession(date: slot(onOrAfter: target, history: history),
                           extraRestHours: extra,
                           reason: NextSessionPlanner.reason(extraRestHours: extra))
    }

    /// Signal score → hours of extra rest. An average day adds nothing and
    /// the curve grows smoothly to about a day when everything is off.
    ///
    /// Continuous on purpose: with stepped thresholds a point either way
    /// decided half a day, which is far more precision than a score built
    /// from a handful of noisy daily values can carry.
    static let extraRestCurve: [(Double, Double)] = [
        (0, 24), (30, 18), (45, 12), (65, 4), (ReadinessScoring.neutral, 0), (100, 0),
    ]

    /// Rest on top of the recovery time. Deliberately based on
    /// `Readiness.signalScore`, not the total: the total is already low right
    /// after a session, and adding that to the waiting time would count the
    /// same fatigue twice.
    static func extraRestHours(for readiness: Readiness?) -> Double {
        guard let signal = readiness?.signalScore else { return 0 }
        return ReadinessScoring.interpolate(Double(signal), extraRestCurve)
    }

    static func reason(extraRestHours: Double) -> String {
        switch extraRestHours {
        case 16...: return L("Your body asked for an extra day. Now you should be good to go.")
        case 2...: return L("A little later than usual — your body was still catching up.")
        default: return L("You should be recovered. Ready for the next round?")
        }
    }

    /// When the athlete usually starts a workout, as hour and minute.
    /// `nil` below `minimumSessions`, where there is no pattern to follow.
    static func usualStartTime(of history: [WorkoutRecord], calendar: Calendar,
                               sampleSize: Int = 10, minimumSessions: Int = 3) -> DateComponents? {
        let starts = history
            .sorted { $0.date > $1.date }
            .prefix(sampleSize)
            // `date` is when the workout ended; the nudge belongs at its start.
            .map { $0.date.addingTimeInterval(-$0.durationSeconds) }
        guard starts.count >= minimumSessions else { return nil }
        let minutes = starts
            .map { calendar.component(.hour, from: $0) * 60 + calendar.component(.minute, from: $0) }
            .sorted()
        let median = minutes[minutes.count / 2]
        return DateComponents(hour: median / 60, minute: median % 60)
    }

    /// The first usual training time at or after `date`.
    private func slot(onOrAfter date: Date, history: [WorkoutRecord]) -> Date {
        let usual = NextSessionPlanner.usualStartTime(of: history, calendar: calendar,
                                                      sampleSize: timeOfDaySampleSize)
        var components = calendar.dateComponents([.year, .month, .day], from: date)
        components.hour = min(max(usual?.hour ?? defaultHour, earliestHour), latestHour)
        components.minute = usual?.minute ?? 0
        guard let candidate = calendar.date(from: components) else { return date }
        if candidate >= date { return candidate }
        return calendar.date(byAdding: .day, value: 1, to: candidate) ?? date
    }
}
