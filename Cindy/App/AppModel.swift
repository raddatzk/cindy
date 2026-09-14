import Foundation
import Observation

/// App-wide state: calibration profile, workout history and the signal config.
@MainActor
@Observable
final class AppModel {
    private(set) var calibration: CalibrationProfile?
    private(set) var history: [WorkoutRecord] = []
    /// Editable workout plan (exercises, targets, duration); persisted as JSON in UserDefaults.
    var plan: WorkoutPlan {
        didSet { AppModel.savePlan(plan) }
    }
    /// Write every saved workout to Apple Health.
    private(set) var syncToHealth: Bool {
        didSet { UserDefaults.standard.set(syncToHealth, forKey: AppModel.syncToHealthKey) }
    }
    /// Nudge towards the next session with a local notification.
    private(set) var remindersEnabled: Bool {
        didSet { UserDefaults.standard.set(remindersEnabled, forKey: AppModel.remindersEnabledKey) }
    }
    /// Debug: write a per-frame CSV for every workout (Documents/DebugLogs).
    var recordWorkouts: Bool {
        didSet { UserDefaults.standard.set(recordWorkouts, forKey: AppModel.recordWorkoutsKey) }
    }
    /// Whether the first-run intro has been shown. Settings can replay it,
    /// so this is only ever set, never cleared.
    private(set) var hasSeenIntro: Bool {
        didSet { UserDefaults.standard.set(hasSeenIntro, forKey: AppModel.hasSeenIntroKey) }
    }
    /// Light/dark override; `.system` follows the device setting.
    var theme: AppTheme {
        didSet { UserDefaults.standard.set(theme.rawValue, forKey: AppModel.themeKey) }
    }
    /// UI and speech language; `.system` follows the device setting.
    var language: AppLanguage {
        didSet {
            UserDefaults.standard.set(language.rawValue, forKey: AppModel.languageKey)
            Localization.apply(language)
        }
    }
    let config = SignalConfig.default
    /// Estimated readiness for the next session; `nil` until it is computed
    /// and when nothing at all is known yet.
    private(set) var readiness: Readiness?
    /// When the next session is suggested; also what the reminder is set to.
    private(set) var nextSession: NextSession?
    let calibrationStore: CalibrationStore
    let historyStore: HistoryStore
    let health: HealthExporter
    let healthMetrics: HealthMetricsReader
    let reminder: WorkoutReminder

    init(calibrationStore: CalibrationStore = CalibrationStore(), historyStore: HistoryStore = HistoryStore(),
         health: HealthExporter = .shared, healthMetrics: HealthMetricsReader = HealthMetricsReader(),
         reminder: WorkoutReminder = .shared) {
        self.calibrationStore = calibrationStore
        self.historyStore = historyStore
        self.health = health
        self.healthMetrics = healthMetrics
        self.reminder = reminder
        self.plan = AppModel.loadPlan()
        self.syncToHealth = UserDefaults.standard.bool(forKey: AppModel.syncToHealthKey)
        self.remindersEnabled = UserDefaults.standard.bool(forKey: AppModel.remindersEnabledKey)
        self.recordWorkouts = UserDefaults.standard.bool(forKey: AppModel.recordWorkoutsKey)
        self.hasSeenIntro = UserDefaults.standard.bool(forKey: AppModel.hasSeenIntroKey)
        self.theme = AppModel.loadSetting(AppModel.themeKey) ?? .system
        self.language = AppModel.loadSetting(AppModel.languageKey) ?? .system
        Localization.apply(language)
        reload()
    }

    var isCalibrated: Bool { calibration?.isComplete(for: plan) == true }

    /// Most recent completed workout (for the result comparison).
    var lastCompletedRecord: WorkoutRecord? { history.first { $0.completed } }

    private static let planKey = "workoutPlan"
    private static let recordWorkoutsKey = "recordWorkouts"
    private static let hasSeenIntroKey = "hasSeenIntro"
    private static let syncToHealthKey = "syncToHealth"
    private static let remindersEnabledKey = "remindersEnabled"
    private static let themeKey = "appTheme"
    private static let languageKey = "appLanguage"

    /// `nonisolated`: it only reads `UserDefaults`, and as a main-actor member
    /// the generic initializer it hands to `flatMap` would have to cross isolation.
    private nonisolated static func loadSetting<Value: RawRepresentable & Sendable>(_ key: String) -> Value? where Value.RawValue == String {
        UserDefaults.standard.string(forKey: key).flatMap(Value.init(rawValue:))
    }

    private static func loadPlan() -> WorkoutPlan {
        guard let data = UserDefaults.standard.data(forKey: planKey),
              let plan = try? JSONDecoder().decode(WorkoutPlan.self, from: data), plan.isValid else { return .cindy }
        return plan
    }

    private static func savePlan(_ plan: WorkoutPlan) {
        if let data = try? JSONEncoder().encode(plan) {
            UserDefaults.standard.set(data, forKey: planKey)
        }
    }

    func markIntroSeen() {
        hasSeenIntro = true
    }

    func reload() {
        calibration = calibrationStore.load()
        history = historyStore.load()
    }

    func save(_ record: WorkoutRecord) {
        try? historyStore.append(record)
        history = historyStore.load()
        Task { await refreshReadiness() }
        guard syncToHealth else { return }
        // Fire and forget: a failed Health write must not cost the user the result.
        Task { try? await health.export(record) }
    }

    /// Recomputes the readiness estimate. Health is only read while the sync
    /// is on — with the switch off Cindy does not touch Health at all and the
    /// estimate rests on the workout history.
    func refreshReadiness() async {
        let metrics = syncToHealth ? await healthMetrics.read() : .none
        readiness = ReadinessEstimator().estimate(history: history, metrics: metrics)
        await refreshReminder()
    }

    // MARK: - Reminder

    /// Recomputes the suggested date and rewrites the pending notification.
    /// A scheduled notification cannot re-evaluate itself, so this runs on
    /// every readiness refresh.
    func refreshReminder() async {
        nextSession = NextSessionPlanner().plan(history: history, readiness: readiness, now: Date())
        guard remindersEnabled, let nextSession else {
            reminder.cancel()
            return
        }
        await reminder.schedule(nextSession)
    }

    /// Turns reminders on, asking for permission first.
    /// Returns false when notifications were refused.
    @discardableResult
    func enableReminders() async -> Bool {
        guard await reminder.requestAuthorization() else { return false }
        remindersEnabled = true
        await refreshReminder()
        return true
    }

    func disableReminders() {
        remindersEnabled = false
        reminder.cancel()
    }

    // MARK: - Apple Health

    var isHealthAvailable: Bool { health.isAvailable }

    /// Workouts still missing from Health (only meaningful while the sync is on).
    var workoutsPendingInHealth: Int { health.pendingCount(in: history) }

    /// Turns the Health sync on, asking for permission first.
    /// Returns false when writing was refused.
    @discardableResult
    func enableHealthSync() async -> Bool {
        try? await health.requestAuthorization()
        guard health.isAuthorized else { return false }
        syncToHealth = true
        await refreshReadiness()
        return true
    }

    func disableHealthSync() {
        syncToHealth = false
        Task { await refreshReadiness() }
    }

    /// Writes the workouts saved before the sync was switched on. Returns how many were added.
    @discardableResult
    func exportHistoryToHealth() async throws -> Int {
        try await health.exportMissing(from: history)
    }

    func delete(_ record: WorkoutRecord) {
        try? historyStore.delete(id: record.id)
        history = historyStore.load()
    }
}
