import Foundation
import Testing
@testable import Cindy

struct WorkoutHealthSampleTests {
    private let end = Date(timeIntervalSince1970: 1_700_000_000)

    private func record(duration: TimeInterval, stamps: [TimeInterval]?, completed: Bool = true) -> WorkoutRecord {
        WorkoutRecord(date: end, rounds: stamps?.count ?? 0, extraReps: 3, durationSeconds: duration,
                      completed: completed, repsPerRound: 30, roundTimestamps: stamps, plan: .cindy)
    }

    @Test func sampleSpansTheActiveTimeEndingWhenTheWorkoutEnded() {
        let sample = WorkoutHealthSample(record: record(duration: 1200, stamps: [60, 130]),
                                         bodyMassKilograms: nil)
        #expect(sample.end == end)
        #expect(sample.start == end.addingTimeInterval(-1200))
        #expect(sample.score == "2 + 3")
        #expect(sample.totalReps == 63)
    }

    @Test func roundsBecomeBackToBackSegments() {
        let sample = WorkoutHealthSample(record: record(duration: 300, stamps: [60, 130, 210]),
                                         bodyMassKilograms: nil)
        #expect(sample.rounds.map(\.index) == [1, 2, 3])
        #expect(sample.rounds[0].start == sample.start)
        #expect(sample.rounds[0].end == sample.start.addingTimeInterval(60))
        #expect(sample.rounds[1].start == sample.rounds[0].end)
        #expect(sample.rounds[2].end == sample.start.addingTimeInterval(210))
    }

    @Test func roundsBeyondTheRecordedDurationAreClampedNotDropped() {
        // The final tick can push the last stamp a hair past the truncated duration.
        let sample = WorkoutHealthSample(record: record(duration: 200, stamps: [100, 200.4]),
                                         bodyMassKilograms: nil)
        #expect(sample.rounds.count == 2)
        #expect(sample.rounds[1].end == sample.end)
        // A duplicate stamp after the clamp adds no zero-length segment.
        let clamped = WorkoutHealthSample(record: record(duration: 200, stamps: [100, 200.4, 200.9]),
                                          bodyMassKilograms: nil)
        #expect(clamped.rounds.count == 2)
    }

    @Test func recordWithoutRoundTimestampsStillMaps() {
        let sample = WorkoutHealthSample(record: record(duration: 600, stamps: nil), bodyMassKilograms: 80)
        #expect(sample.rounds.isEmpty)
        #expect(sample.end.timeIntervalSince(sample.start) == 600)
    }

    @Test func energyIsEstimatedOnlyWithABodyMass() {
        let withoutMass = WorkoutHealthSample(record: record(duration: 1200, stamps: [60]),
                                              bodyMassKilograms: nil)
        #expect(withoutMass.activeEnergyKilocalories == nil)
        let zeroMass = WorkoutHealthSample(record: record(duration: 1200, stamps: [60]),
                                           bodyMassKilograms: 0)
        #expect(zeroMass.activeEnergyKilocalories == nil)

        // 8 MET · 3.5 · 80 kg / 200 · 20 min = 224 kcal
        let sample = WorkoutHealthSample(record: record(duration: 1200, stamps: [60]),
                                         bodyMassKilograms: 80)
        #expect(sample.activeEnergyKilocalories == 224)
    }

    @Test func abortedWorkoutKeepsItsFlag() {
        let sample = WorkoutHealthSample(record: record(duration: 400, stamps: [90], completed: false),
                                         bodyMassKilograms: nil)
        #expect(sample.completed == false)
    }
}
