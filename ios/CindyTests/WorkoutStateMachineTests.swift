import Testing
@testable import Cindy

struct WorkoutStateMachineTests {
    private func runningMachine() -> WorkoutStateMachine {
        let machine = WorkoutStateMachine()
        machine.beginCountdown()
        machine.start()
        machine.activate()
        return machine
    }

    @Test func startsWithPullUpsInTransition() {
        let machine = WorkoutStateMachine()
        machine.beginCountdown()
        #expect(machine.phase == .countdown)
        #expect(machine.start() == [.started])
        #expect(machine.phase == .transition)
        #expect(machine.exercise == .pullUp)
        #expect(machine.activate() == [.exerciseStarted(.pullUp)])
        #expect(machine.phase == .active)
    }

    @Test func repsAreIgnoredWhileInTransition() {
        let machine = WorkoutStateMachine()
        machine.beginCountdown()
        machine.start()
        #expect(machine.registerRep().isEmpty)
        #expect(machine.repCount == 0)
    }

    @Test func fullRoundAdvancesExercisesAndRound() {
        let machine = runningMachine()
        for _ in 0..<4 { machine.registerRep() }
        #expect(machine.repCount == 4)
        let fifth = machine.registerRep()
        #expect(fifth.contains(.exerciseCompleted(.pullUp, next: .pushUp)))
        #expect(machine.phase == .transition)
        #expect(machine.exercise == .pushUp)
        #expect(machine.repCount == 0)
        #expect(machine.score == WorkoutScore(rounds: 0, reps: 5))

        machine.activate()
        for _ in 0..<10 { machine.registerRep() }
        #expect(machine.exercise == .squat)
        #expect(machine.score.reps == 15)

        machine.activate()
        var events: [WorkoutEvent] = []
        for _ in 0..<15 { events += machine.registerRep() }
        #expect(events.contains(.roundCompleted(1)))
        #expect(machine.exercise == .pullUp)
        #expect(machine.completedRounds == 1)
        #expect(machine.currentRound == 2)
        #expect(machine.score == WorkoutScore(rounds: 1, reps: 0))
    }

    @Test func scoreNotationFollowsCrossFitConvention() {
        #expect(WorkoutScore(rounds: 14, reps: 7).notation == "14 + 7")
        #expect(WorkoutScore(rounds: 14, reps: 0).notation == "14")
        #expect(WorkoutScore(rounds: 2, reps: 17).totalReps == 77)
    }

    @Test func manualPlusOneCompletesExercise() {
        let machine = runningMachine()
        for _ in 0..<4 { machine.registerRep() }
        let events = machine.adjust(by: 1)
        #expect(events.contains(.exerciseCompleted(.pullUp, next: .pushUp)))
        #expect(machine.phase == .transition)
    }

    @Test func manualMinusOneStepsBackIntoPreviousExercise() {
        let machine = runningMachine()
        for _ in 0..<5 { machine.registerRep() } // now push-ups, transition
        let events = machine.adjust(by: -1)
        #expect(events.contains(.exerciseReopened(.pullUp)))
        #expect(machine.exercise == .pullUp)
        #expect(machine.repCount == 4)
        #expect(machine.score == WorkoutScore(rounds: 0, reps: 4))
    }

    @Test func minusOneAcrossRoundBoundaryReopensRound() {
        let machine = runningMachine()
        for _ in 0..<5 { machine.registerRep() }
        machine.activate()
        for _ in 0..<10 { machine.registerRep() }
        machine.activate()
        for _ in 0..<15 { machine.registerRep() }
        #expect(machine.completedRounds == 1)
        let events = machine.adjust(by: -1)
        #expect(events.contains(.roundReopened(1)))
        #expect(machine.completedRounds == 0)
        #expect(machine.exercise == .squat)
        #expect(machine.repCount == 14)
    }

    @Test func minusOneAtVeryStartDoesNothing() {
        let machine = runningMachine()
        #expect(machine.adjust(by: -1).isEmpty)
        #expect(machine.repCount == 0)
        #expect(machine.exercise == .pullUp)
    }

    @Test func pauseAndResumeGoThroughTransition() {
        let machine = runningMachine()
        machine.registerRep()
        machine.pause()
        #expect(machine.phase == .paused)
        #expect(machine.registerRep().isEmpty)
        #expect(machine.adjust(by: 1).contains(.repCounted(.pullUp, count: 2)))
        machine.resume()
        #expect(machine.phase == .transition)
        machine.activate()
        #expect(machine.phase == .active)
        #expect(machine.repCount == 2)
    }

    @Test func finishStopsCounting() {
        let machine = runningMachine()
        machine.registerRep()
        #expect(machine.finish() == [.finished])
        #expect(machine.phase == .finished)
        #expect(machine.registerRep().isEmpty)
        #expect(machine.finish().isEmpty)
        #expect(machine.score == WorkoutScore(rounds: 0, reps: 1))
    }

    // MARK: - Changing the plan mid-workout

    /// Pull-ups done, `pushUps` push-ups counted.
    private func machineInPushUps(_ pushUps: Int) -> WorkoutStateMachine {
        let machine = runningMachine()
        for _ in 0..<5 { machine.registerRep() }
        machine.activate()
        for _ in 0..<pushUps { machine.registerRep() }
        return machine
    }

    @Test func changedTargetKeepsTheRepsOfTheCurrentExercise() {
        let machine = machineInPushUps(4)
        var plan = WorkoutPlan.cindy
        plan.setTarget(8, for: .pushUp)
        #expect(machine.replacePlan(plan).isEmpty)
        #expect(machine.exercise == .pushUp)
        #expect(machine.repCount == 4)
        #expect(machine.phase == .active)
        #expect(machine.score == WorkoutScore(rounds: 0, reps: 9, repsPerRound: 28))
    }

    @Test func targetBelowTheRepsDoneFinishesTheExercise() {
        let machine = machineInPushUps(7)
        machine.pause()
        var plan = WorkoutPlan.cindy
        plan.setTarget(5, for: .pushUp)
        #expect(machine.replacePlan(plan) == [.exerciseCompleted(.pushUp, next: .squat)])
        #expect(machine.exercise == .squat)
        #expect(machine.repCount == 0)
        #expect(machine.phase == .paused)
    }

    @Test func removingTheCurrentExerciseMovesOnToTheNextOne() {
        let machine = machineInPushUps(3)
        var plan = WorkoutPlan.cindy
        plan.setEnabled(.pushUp, false)
        #expect(machine.replacePlan(plan).isEmpty)
        #expect(machine.exercise == .squat)
        #expect(machine.repCount == 0)
        #expect(machine.phase == .transition)
        #expect(machine.score == WorkoutScore(rounds: 0, reps: 5, repsPerRound: 20))
    }

    @Test func removingTheRestOfTheRoundCompletesIt() {
        let machine = machineInPushUps(10)
        machine.activate()
        for _ in 0..<3 { machine.registerRep() }
        var plan = WorkoutPlan.cindy
        plan.setEnabled(.squat, false)
        #expect(machine.replacePlan(plan) == [.roundCompleted(1)])
        #expect(machine.exercise == .pullUp)
        #expect(machine.currentRound == 2)
        #expect(machine.score == WorkoutScore(rounds: 1, reps: 0, repsPerRound: 15))
    }

    @Test func addedExerciseJoinsTheCurrentRound() {
        let machine = machineInPushUps(10)
        var plan = WorkoutPlan.cindy
        plan.setEnabled(.plank, true)
        machine.replacePlan(plan)
        machine.activate()
        // The plank waits for the end of the AMRAP: the squats still complete the round.
        let events = (0..<15).flatMap { _ in machine.registerRep() }
        #expect(events.contains(.roundCompleted(1)))
        #expect(machine.exercise == .pullUp)
    }

    @Test func thePlankOnlyFollowsARunningWorkoutWithAPlank() {
        let machine = machineInPushUps(0)
        machine.beginPlank()
        #expect(machine.phase != .plank) // Cindy has no plank
        var plan = WorkoutPlan.cindy
        plan.setEnabled(.plank, true)
        machine.replacePlan(plan)
        machine.beginPlank()
        #expect(machine.phase == .plank)
        #expect(machine.isRunning == false)
    }

    @Test func finishedWorkoutKeepsItsPlan() {
        let machine = runningMachine()
        machine.finish()
        #expect(machine.replacePlan(.withoutPullUps).isEmpty)
        #expect(machine.plan == .cindy)
    }
}
