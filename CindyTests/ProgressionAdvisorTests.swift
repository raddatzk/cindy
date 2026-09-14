import Foundation
import Testing
@testable import Cindy

struct ProgressionAdvisorTests {
    private func record(plan: WorkoutPlan, rounds: Int, secondsPerRound: [TimeInterval], completed: Bool = true,
                        date: Date = Date()) -> WorkoutRecord {
        var stamps: [TimeInterval] = []
        var t: TimeInterval = 0
        for d in secondsPerRound { t += d; stamps.append(t) }
        return WorkoutRecord(date: date, rounds: rounds, extraReps: 0, durationSeconds: plan.duration, completed: completed,
                             repsPerRound: plan.repsPerRound, roundTimestamps: stamps, plan: plan)
    }

    private var homePlan: WorkoutPlan {
        var plan = WorkoutPlan.cindy
        plan.setTarget(2, for: .pullUp)
        plan.setTarget(4, for: .pushUp)
        plan.setTarget(9, for: .squat)
        plan.durationMinutes = 15
        return plan
    }

    @Test func reserveRatioComparesLastAndFirstQuarter() {
        let steady = record(plan: homePlan, rounds: 14, secondsPerRound: Array(repeating: 64, count: 14))
        #expect(ProgressionAdvisor.reserveRatio(steady) == 1)
        let fading = record(plan: homePlan, rounds: 12, secondsPerRound: [40, 40, 40, 40, 40, 60, 60, 80, 100, 100, 100, 120])
        let ratio = ProgressionAdvisor.reserveRatio(fading) ?? 1
        #expect(ratio < 0.85)
        #expect(ProgressionAdvisor.roundDurations(steady).count == 14)
    }

    @Test func durationComesFirst() {
        let r = record(plan: homePlan, rounds: 14, secondsPerRound: Array(repeating: 64, count: 14))
        let rec = ProgressionAdvisor().recommend(after: r, previous: nil)
        #expect(rec.kind == .duration)
        #expect(rec.plan.durationMinutes == 20)
        #expect(rec.changesPlan)
    }

    @Test func thenWeakestExerciseGetsOneRep() {
        var plan = homePlan
        plan.durationMinutes = 20
        let r = record(plan: plan, rounds: 18, secondsPerRound: Array(repeating: 66, count: 18))
        let rec = ProgressionAdvisor().recommend(after: r, previous: nil)
        #expect(rec.kind == .reps(.pullUp)) // 2/5 is the smallest ratio
        #expect(rec.plan.target(for: .pullUp) == 3)
    }

    @Test func fullCindyRecommendsDensity() {
        let r = record(plan: .cindy, rounds: 12, secondsPerRound: Array(repeating: 100, count: 12))
        let rec = ProgressionAdvisor().recommend(after: r, previous: nil)
        #expect(rec.kind == .density(targetRounds: 13))
        #expect(!rec.changesPlan)
    }

    @Test func fadingPaceHolds() {
        let r = record(plan: homePlan, rounds: 12, secondsPerRound: [40, 40, 40, 40, 40, 60, 60, 80, 100, 100, 100, 120])
        #expect(ProgressionAdvisor().recommend(after: r, previous: nil).kind == .hold)
    }

    @Test func abortedWorkoutHolds() {
        let r = record(plan: homePlan, rounds: 5, secondsPerRound: Array(repeating: 60, count: 5), completed: false)
        #expect(ProgressionAdvisor().recommend(after: r, previous: nil).kind == .hold)
    }

    @Test func collapseAfterHarderPlanStepsBack() {
        let previous = record(plan: homePlan, rounds: 14, secondsPerRound: Array(repeating: 64, count: 14),
                              date: Date().addingTimeInterval(-86_400))
        var harder = homePlan
        harder.setTarget(4, for: .pullUp)
        let current = record(plan: harder, rounds: 9, secondsPerRound: Array(repeating: 100, count: 9))
        let rec = ProgressionAdvisor().recommend(after: current, previous: previous)
        #expect(rec.kind == .stepBack)
        #expect(rec.plan == homePlan)
    }

    @Test func recordWithoutTimestampsStillDecodes() throws {
        let json = #"{"id":"6C1B0B8E-5A0B-4B4F-9C53-1F2D7B2B1A11","date":"2026-09-01T10:00:00Z","rounds":3,"extraReps":2,"durationSeconds":1200,"completed":true,"repsPerRound":30}"#
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let record = try decoder.decode(WorkoutRecord.self, from: Data(json.utf8))
        #expect(record.roundTimestamps == nil)
        #expect(ProgressionAdvisor.reserveRatio(record) == nil)
    }
}
