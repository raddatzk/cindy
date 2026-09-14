import Foundation

/// The values Apple Health needs for one finished workout, derived from a
/// `WorkoutRecord`. Free of HealthKit so the mapping can be unit-tested.
///
/// Cindy's clock counts active time only — pausing leaves no gap in
/// `roundTimestamps`. The sample therefore spans the active time and ends when
/// the workout ended: a paused session appears in Health as having started
/// later than it really did, but its duration and round segments stay exact.
struct WorkoutHealthSample: Equatable {
    /// One completed round as a segment of the session.
    struct Round: Equatable {
        /// 1-based, as shown in the app.
        var index: Int
        var start: Date
        var end: Date
    }

    var start: Date
    var end: Date
    var rounds: [Round]
    /// MET estimate; `nil` when the body mass is unknown — better no number than a made-up one.
    var activeEnergyKilocalories: Double?
    /// Score in the app's notation ("12 + 7"), for the Health metadata.
    var score: String
    var totalReps: Int
    var completed: Bool

    /// Calisthenics circuit, vigorous effort (Compendium of Physical Activities).
    static let metabolicEquivalent = 8.0

    init(record: WorkoutRecord, bodyMassKilograms: Double?) {
        let duration = max(record.durationSeconds, 0)
        let start = record.date.addingTimeInterval(-duration)
        self.start = start
        self.end = record.date
        self.score = record.score.notation
        self.totalReps = record.score.totalReps
        self.completed = record.completed

        var previous: TimeInterval = 0
        rounds = (record.roundTimestamps ?? []).enumerated().compactMap { index, stamp in
            // Rounds finishing after the recorded duration can only be rounding
            // noise from the final tick, so they are clamped rather than dropped.
            let stamp = min(stamp, duration)
            defer { previous = max(previous, stamp) }
            guard stamp > previous else { return nil }
            return Round(index: index + 1,
                         start: start.addingTimeInterval(previous),
                         end: start.addingTimeInterval(stamp))
        }

        if let bodyMassKilograms, bodyMassKilograms > 0, duration > 0 {
            activeEnergyKilocalories =
                Self.metabolicEquivalent * 3.5 * bodyMassKilograms / 200 * (duration / 60)
        } else {
            activeEnergyKilocalories = nil
        }
    }
}
