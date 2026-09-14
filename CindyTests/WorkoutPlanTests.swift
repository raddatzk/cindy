import Foundation
import Testing
@testable import Cindy

struct WorkoutPlanTests {
    @Test func cindyPlanOrderAndReps() {
        let plan = WorkoutPlan.cindy
        #expect(plan.repsPerRound == 30)
        #expect(plan.next(after: .squat) == .pullUp)
        #expect(plan.previous(before: .pullUp) == .squat)
        #expect(plan.repsBefore(.squat) == 15)
        #expect(plan.summary == "5 Klimmzüge · 10 Liegestütze · 15 Kniebeugen")
    }

    @Test func planWithoutPullUpsRunsRoundsOfTwoExercises() {
        let plan = WorkoutPlan.withoutPullUps
        #expect(plan.repsPerRound == 25)
        #expect(plan.first == .pushUp)
        #expect(plan.next(after: .squat) == .pushUp)
        #expect(plan.repsBefore(.squat) == 10)

        let machine = WorkoutStateMachine(plan: plan)
        machine.beginCountdown()
        machine.start()
        machine.activate()
        #expect(machine.exercise == .pushUp)
        for _ in 0..<10 { machine.registerRep() }
        #expect(machine.exercise == .squat)
        machine.activate()
        var events: [WorkoutEvent] = []
        for _ in 0..<15 { events += machine.registerRep() }
        #expect(events.contains(.roundCompleted(1)))
        #expect(machine.exercise == .pushUp)
        #expect(machine.score == WorkoutScore(rounds: 1, reps: 0, repsPerRound: 25))
        #expect(machine.score.totalReps == 25)
        #expect(machine.adjust(by: -1).contains(.roundReopened(1)))
        #expect(machine.exercise == .squat)
        #expect(machine.repCount == 14)
    }

    @Test func customTargetsAndHoldsAreRespected() {
        var plan = WorkoutPlan.withoutPullUps
        plan.setTarget(3, for: .pushUp)
        plan.setEnabled(.plank, true)
        plan.setTarget(20, for: .plank)
        #expect(plan.exercises == [.pushUp, .squat, .plank])
        #expect(plan.countTarget(for: .plank) == 1)
        #expect(plan.repsPerRound == 3 + 15 + 1)
        #expect(plan.summary == "3 Liegestütze · 15 Kniebeugen · 20 s Plank")

        let machine = WorkoutStateMachine(plan: plan)
        machine.beginCountdown(); machine.start(); machine.activate()
        for _ in 0..<3 { machine.registerRep() }
        #expect(machine.exercise == .squat)
        machine.activate()
        for _ in 0..<15 { machine.registerRep() }
        #expect(machine.exercise == .plank)
        machine.activate()
        let events = machine.registerRep()
        #expect(events.contains(.roundCompleted(1)))
        #expect(machine.score.totalReps == 19)
    }

    @Test func planRoundTripsThroughJSON() throws {
        var plan = WorkoutPlan.cindy
        plan.durationMinutes = 12
        plan.setEnabled(.pullUp, false)
        let data = try JSONEncoder().encode(plan)
        let decoded = try JSONDecoder().decode(WorkoutPlan.self, from: data)
        #expect(decoded == plan)
        #expect(decoded.duration == 720)
    }

    @Test func calibrationCompletenessDependsOnPlan() {
        var profile = CalibrationProfile()
        for exercise in [Exercise.pushUp, .squat] {
            profile.set(ExerciseCalibration(source: .face, minValue: 0, maxValue: 1, baseline: 0, low: 0.25, high: 0.75,
                                            direction: .peak, repDuration: 1, calibratedAt: .init()), for: exercise)
        }
        #expect(profile.isComplete(for: .withoutPullUps))
        #expect(!profile.isComplete(for: .cindy))
        #expect(profile.missingExercises(for: .cindy) == [.pullUp])
    }

    @Test func setLabelAgreesWithItsCount() {
        Localization.apply(.german)
        #expect(ExerciseSet(exercise: .pullUp, target: 1).label == "1 Klimmzug")
        #expect(ExerciseSet(exercise: .pullUp, target: 5).label == "5 Klimmzüge")
        #expect(ExerciseSet(exercise: .plank, target: 30).label == "30 s Plank")

        Localization.apply(.english)
        #expect(ExerciseSet(exercise: .pullUp, target: 1).label == "1 Pull-up")
        #expect(ExerciseSet(exercise: .pullUp, target: 5).label == "5 Pull-ups")

        Localization.apply(.system)
    }
}
