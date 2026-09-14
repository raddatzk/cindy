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

    @Test func announcementsUseTheSelectedLanguage() {
        Localization.apply(.english)
        #expect(WorkoutEngine.announcement(forRemaining: 600) == "10 minutes left")
        #expect(WorkoutEngine.announcement(forRemaining: 60) == "one minute left")
        #expect(WorkoutEngine.announcement(forRemaining: 30) == "30 seconds left")

        Localization.apply(.german)
        #expect(WorkoutEngine.announcement(forRemaining: 600) == "Noch 10 Minuten")
        #expect(WorkoutEngine.announcement(forRemaining: 60) == "Noch eine Minute")
        #expect(WorkoutEngine.announcement(forRemaining: 30) == "Noch 30 Sekunden")

        Localization.apply(.system)
    }
}
