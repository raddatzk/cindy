import Foundation
// The framework has not been audited for concurrency; its types are safe to
// hold across isolation but not yet marked as such.
@preconcurrency import UserNotifications

/// The single local notification that nudges towards the next session.
///
/// Nothing leaves the device: the app schedules the notification itself, for
/// the date the planner picked. A pending notification cannot re-evaluate
/// itself, so it is rewritten whenever the app recomputes readiness — on
/// launch, after a workout, and on a background refresh.
/// `Sendable`: the pending nudge is state of the notification centre, which is
/// itself sendable; nothing is stored here but a reference to it.
final class WorkoutReminder: Sendable {
    static let shared = WorkoutReminder()
    /// One identifier, so scheduling always replaces the previous nudge.
    static let identifier = "nextWorkout"

    private let center: UNUserNotificationCenter

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    /// Shows the system prompt. Returns whether notifications may be posted.
    func requestAuthorization() async -> Bool {
        ((try? await center.requestAuthorization(options: [.alert, .sound])) ?? false)
    }

    var isAuthorized: Bool {
        get async { await center.notificationSettings().authorizationStatus == .authorized }
    }

    func schedule(_ session: NextSession, now: Date = Date()) async {
        cancel()
        guard session.date > now, await isAuthorized else { return }
        let content = UNMutableNotificationContent()
        content.title = L("Time for Cindy")
        content.body = session.reason
        content.sound = .default
        let components = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute],
                                                         from: session.date)
        let request = UNNotificationRequest(
            identifier: Self.identifier,
            content: content,
            trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false))
        try? await center.add(request)
    }

    func cancel() {
        center.removePendingNotificationRequests(withIdentifiers: [Self.identifier])
    }

    /// When the pending nudge will fire, if one is scheduled.
    func pendingDate() async -> Date? {
        let requests = await center.pendingNotificationRequests()
        let trigger = requests.first { $0.identifier == Self.identifier }?.trigger
        return (trigger as? UNCalendarNotificationTrigger)?.nextTriggerDate()
    }
}
