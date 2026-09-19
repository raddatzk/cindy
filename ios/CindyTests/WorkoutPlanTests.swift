import Foundation
import Testing
@testable import Cindy

struct WorkoutPlanTests {
    /// `summary` is localized, so the expectations below only hold in one
    /// language. Pin it rather than inheriting the host's: a developer Mac
    /// running in German passes either way, a CI runner in English does not.
    /// Swift Testing makes a fresh instance per test, so this runs before each.
    init() { Localization.apply(.german) }

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

    @Test func thePlankFollowsTheAmrapOutsideTheRound() {
        var plan = WorkoutPlan.withoutPullUps
        plan.setTarget(3, for: .pushUp)
        plan.setEnabled(.plank, true)
        plan.setTarget(20, for: .plank)
        #expect(plan.exercises == [.pushUp, .squat])
        #expect(plan.contains(.plank))
        #expect(plan.target(for: .plank) == 20)
        #expect(plan.repsPerRound == 3 + 15)
        #expect(plan.summary == "3 Liegestütze · 15 Kniebeugen")
        #expect(plan.summaryWithPlank == "3 Liegestütze · 15 Kniebeugen, danach 20 s Plank")

        let machine = WorkoutStateMachine(plan: plan)
        machine.beginCountdown(); machine.start(); machine.activate()
        for _ in 0..<3 { machine.registerRep() }
        machine.activate()
        let events = (0..<15).flatMap { _ in machine.registerRep() }
        #expect(events.contains(.roundCompleted(1)))
        #expect(machine.exercise == .pushUp)
        machine.activate()
        machine.registerRep()
        machine.beginPlank()
        #expect(machine.phase == .plank)
        #expect(machine.adjust(by: 1).isEmpty) // the score is final
        #expect(machine.score.totalReps == 19)
        #expect(machine.finish() == [.finished])
    }

    @Test func thePlankCanHaveSeveralSets() throws {
        var plan = WorkoutPlan.withoutPullUps
        plan.setEnabled(.plank, true)
        #expect(plan.plankSets == 1)
        plan.setPlankSets(3)
        #expect(plan.summaryWithPlank == "10 Liegestütze · 15 Kniebeugen, danach 3 × 30 s Plank")
        plan.setPlankSets(0)
        #expect(plan.plankSets == 1)
        plan.setPlankSets(50)
        #expect(plan.plankSets == WorkoutPlan.plankSetRange.upperBound)
        plan.setPlankSets(3)
        #expect(plan.repsPerRound == 25) // sets stay outside the score
        let decoded = try JSONDecoder().decode(WorkoutPlan.self, from: JSONEncoder().encode(plan))
        #expect(decoded.plankSets == 3)
        let older = #"{"sets":[{"exercise":"squat","target":15}],"durationMinutes":20,"plankSeconds":30}"#
        #expect(try JSONDecoder().decode(WorkoutPlan.self, from: Data(older.utf8)).plankSets == 1)
    }

    @Test func plansWithThePlankInTheRoundMoveItBehindTheAmrap() throws {
        let saved = #"{"sets":[{"exercise":"pushUp","target":10},{"exercise":"plank","target":45},{"exercise":"squat","target":15}],"durationMinutes":20}"#
        let plan = try JSONDecoder().decode(WorkoutPlan.self, from: Data(saved.utf8))
        #expect(plan.exercises == [.pushUp, .squat])
        #expect(plan.plankSeconds == 45)
        let roundTripped = try JSONDecoder().decode(WorkoutPlan.self, from: JSONEncoder().encode(plan))
        #expect(roundTripped == plan)
        let withoutPlank = try JSONDecoder().decode(WorkoutPlan.self, from: JSONEncoder().encode(WorkoutPlan.cindy))
        #expect(withoutPlank.plankSeconds == nil)
    }

    @Test func targetsStayWithinTheCindyPrescription() {
        var plan = WorkoutPlan.cindy
        plan.setTarget(25, for: .pullUp)
        plan.setTarget(0, for: .squat)
        #expect(plan.target(for: .pullUp) == 5)
        #expect(plan.target(for: .squat) == 1)
        plan.setEnabled(.plank, true)
        plan.setTarget(2, for: .plank)
        #expect(plan.target(for: .plank) == 5)
    }

    @Test func oldPlansAreNormalizedToTheAllowedValues() {
        var plan = WorkoutPlan.cindy
        plan.durationMinutes = 12
        plan.sets[0].target = 25 // saved before the limits existed
        let normalized = plan.normalized()
        #expect(normalized.durationMinutes == 10)
        #expect(normalized.target(for: .pullUp) == 5)
        plan.durationMinutes = 45
        #expect(plan.normalized().durationMinutes == 20)
    }

    @Test func exercisesMoveOnePlaceAtATime() {
        var plan = WorkoutPlan.cindy
        plan.move(.squat, by: -1)
        #expect(plan.exercises == [.pullUp, .squat, .pushUp])
        plan.move(.pullUp, by: -1)
        #expect(plan.exercises == [.pullUp, .squat, .pushUp])
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
