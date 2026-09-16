import Foundation
import Observation
import UIKit

/// Runs a Cindy workout: camera → signal chain → state machine, plus the
/// 20-minute clock and audio feedback. All published state is main-actor.
@MainActor
@Observable
final class WorkoutEngine {
    private(set) var phase: WorkoutPhase = .idle
    private(set) var exercise: Exercise = .pullUp
    private(set) var repCount = 0
    private(set) var currentRound = 1
    private(set) var score = WorkoutScore(rounds: 0, reps: 0)
    private(set) var elapsed: TimeInterval = 0
    private(set) var countdownValue = 0
    private(set) var isSignalArmed = false
    /// Whether the tracked subject (face or body, see `trackedSource`) is in the current frame.
    private(set) var subjectDetected = false
    private(set) var trackedSource: SignalSource = .face
    private(set) var liveValue: Float?
    private(set) var errorMessage: String?
    /// Set once the workout is finished or aborted.
    private(set) var result: WorkoutRecord?

    var remaining: TimeInterval { max(plan.duration - elapsed, 0) }
    /// Accumulated plank time of the current hold (nil for rep exercises).
    private(set) var heldSeconds: TimeInterval?
    /// The exercise after the current one (for the on-screen hint).
    var nextExercise: Exercise { plan.next(after: exercise) }
    var isPaused: Bool { phase == .paused }

    let camera: CameraSession
    private let processor: FrameProcessor
    private let machine: WorkoutStateMachine
    /// Can change during a pause, see `updatePlan(_:)`.
    private(set) var plan: WorkoutPlan
    private let profile: CalibrationProfile
    private let config: SignalConfig
    private let audio: AudioFeedback

    private var timer: Timer?
    private var accumulated: TimeInterval = 0
    private var segmentStart: Date?
    private var countdownTask: Task<Void, Never>?
    private var cameraRunning = false
    private var logger: FrameLogger?
    private let logToCSV: Bool
    /// State string for the CSV, written on the main actor and read on the camera queue.
    private let logState = LockedValue("")
    private var roundTimestamps: [TimeInterval] = []

    init(profile: CalibrationProfile, plan: WorkoutPlan = .cindy, config: SignalConfig = .default,
         audio: AudioFeedback? = nil, logToCSV: Bool = false) {
        self.profile = profile
        self.plan = plan
        self.machine = WorkoutStateMachine(plan: plan)
        self.exercise = plan.first
        self.config = config
        self.audio = audio ?? .shared
        self.camera = CameraSession(config: config)
        self.processor = FrameProcessor(camera: camera)
        self.logToCSV = logToCSV
        processor.onFrame = { [weak self] observation, output in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.handle(observation: observation, output: output) }
            }
        }
    }

    var logFileURL: URL? { logger?.url }
    var isLogging: Bool { logger != nil }

    /// Exercises that can join the plan mid-workout: only calibrated ones can be counted.
    var calibratedExercises: [Exercise] {
        Exercise.allCases.filter { profile.calibration(for: $0) != nil }
    }

    /// Shortest AMRAP length the clock has not passed yet.
    var shortestDurationAhead: Int {
        WorkoutPlan.durationChoices.first { TimeInterval($0 * 60) > currentElapsed() } ?? plan.durationMinutes
    }

    // MARK: - Lifecycle

    func start() async {
        guard phase == .idle else { return }
        guard profile.isComplete(for: plan) else {
            errorMessage = L("No complete calibration available.")
            return
        }
        guard await CameraSession.requestAccess() else {
            errorMessage = CameraError.notAuthorized.localizedDescription
            return
        }
        do {
            try camera.configure()
        } catch {
            errorMessage = error.localizedDescription
            return
        }
        audio.prepare()
        // Created only once the workout really starts, so aborted setups leave no empty files.
        if logToCSV, let logger = try? FrameLogger(label: "workout") {
            self.logger = logger
            let state = logState
            processor.setLogger(logger, stateProvider: { state.get() })
        }
        camera.start()
        cameraRunning = true
        UIApplication.shared.isIdleTimerDisabled = true

        machine.beginCountdown()
        sync()
        countdownTask = Task { [weak self] in
            guard let self else { return }
            for remaining in stride(from: self.config.workoutCountdownSeconds, through: 1, by: -1) {
                self.countdownValue = remaining
                self.audio.beep()
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
            }
            self.countdownValue = 0
            self.beginWorkout()
        }
    }

    func pause() {
        guard machine.isRunning else { return }
        machine.pause()
        stopClock()
        processor.setPipeline(nil)
        sync()
    }

    func resume() {
        guard machine.phase == .paused else { return }
        machine.resume()
        startClock()
        installPipeline(for: machine.exercise)
        sync()
    }

    /// Stops early; the partial score is kept as an incomplete record.
    func abort() {
        guard phase != .finished, phase != .idle else { return }
        countdownTask?.cancel()
        finishWorkout(completed: false)
    }

    func adjust(by delta: Int) {
        let before = machine.exercise
        let events = machine.adjust(by: delta)
        apply(events)
        if machine.exercise != before, machine.phase != .paused {
            installPipeline(for: machine.exercise)
        }
        sync()
    }

    /// Takes over a plan changed on the pause screen. The clock and the rounds done so far
    /// carry on; the new pipeline is installed on resume. Refused for exercises without a
    /// calibration and for a duration the clock has already passed.
    @discardableResult
    func updatePlan(_ newPlan: WorkoutPlan) -> Bool {
        guard machine.phase == .paused, newPlan.isValid, profile.isComplete(for: newPlan),
              newPlan.duration > currentElapsed() else { return false }
        apply(machine.replacePlan(newPlan))
        plan = newPlan
        sync()
        return true
    }

    // MARK: - Private

    private func beginWorkout() {
        guard machine.phase == .countdown else { return } // aborted during the countdown
        let events = machine.start()
        audio.goSignal()
        apply(events)
        accumulated = 0
        startClock()
        installPipeline(for: machine.exercise)
        sync()
    }

    private func installPipeline(for exercise: Exercise) {
        guard let calibration = profile.calibration(for: exercise) else { return }
        let pipeline = SignalPipeline(exercise: exercise, thresholds: calibration.thresholds,
                                      source: calibration.source, config: config,
                                      holdSeconds: exercise.isHold ? TimeInterval(plan.target(for: exercise)) : nil)
        trackedSource = calibration.source
        processor.setDetection(for: calibration.source)
        processor.setPipeline(pipeline)
        isSignalArmed = false
        heldSeconds = exercise.isHold ? 0 : nil
    }

    /// A workout cannot count what it cannot see. Losing the camera pauses it
    /// instead of silently dropping every rep until it comes back — a call, the
    /// app going to the background or another app taking the camera all look
    /// like a perfectly still athlete from here.
    ///
    private func handle(observation: FrameObservation, output: PipelineOutput?) {
        subjectDetected = CalibrationEngine.subjectDetected(in: observation, source: trackedSource)
        liveValue = output?.smoothed
        guard let output, machine.isRunning else { return }
        // Ignore stale frames from a pipeline that has already been replaced.
        guard output.exercise == machine.exercise else { return }
        isSignalArmed = output.isArmed
        if let held = output.heldSeconds {
            let previous = Int(heldSeconds ?? 0)
            heldSeconds = held
            if Int(held) / 10 > previous / 10, Int(held) < plan.target(for: output.exercise) {
                audio.beep() // every 10 s of plank
            }
        }
        switch output.event {
        case .armed:
            if machine.phase == .transition {
                apply(machine.activate())
            }
        case .repCompleted:
            let events = machine.registerRep()
            apply(events)
            if machine.exercise != output.exercise {
                installPipeline(for: machine.exercise)
            }
        case .disarmed, .repRejected, nil:
            break
        }
        sync()
    }

    private func apply(_ events: [WorkoutEvent]) {
        // The rep that finishes an exercise sounds higher, so the change is audible
        // with the phone on the floor.
        let finishesExercise = events.contains {
            if case .exerciseCompleted = $0 { return true }
            return false
        }
        for event in events {
            switch event {
            case .repCounted:
                if finishesExercise { audio.goSignal() } else { audio.beep() }
            case .roundCompleted:
                roundTimestamps.append(currentElapsed())
            case .roundReopened:
                if !roundTimestamps.isEmpty { roundTimestamps.removeLast() }
            case .started, .exerciseStarted, .exerciseCompleted, .exerciseReopened, .repRemoved, .finished:
                break
            }
        }
    }

    private func sync() {
        phase = machine.phase
        exercise = machine.exercise
        repCount = machine.repCount
        currentRound = machine.currentRound
        score = machine.score
        if logger != nil {
            logState.set("\(machine.phase)|r\(machine.currentRound)|\(machine.exercise.rawValue)|\(machine.repCount)")
        }
    }

    // MARK: Clock

    private func startClock() {
        segmentStart = Date()
        timer?.invalidate()
        // Added to the main run loop below, so the block already fires on the
        // main thread — the hop through `DispatchQueue.main` this used to do
        // only delayed the tick by one turn and captured `self` a second time.
        let timer = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func currentElapsed() -> TimeInterval {
        guard let segmentStart else { return accumulated }
        return accumulated + Date().timeIntervalSince(segmentStart)
    }

    private func stopClock() {
        if let segmentStart {
            accumulated += Date().timeIntervalSince(segmentStart)
        }
        segmentStart = nil
        timer?.invalidate()
        timer = nil
    }

    private func tick() {
        guard let segmentStart else { return }
        elapsed = accumulated + Date().timeIntervalSince(segmentStart)
        if remaining <= 0 {
            finishWorkout(completed: true)
        }
    }

    private func finishWorkout(completed: Bool) {
        stopClock()
        let events = machine.finish()
        guard !events.isEmpty || result == nil else { return }
        processor.setPipeline(nil)
        teardown()
        let score = machine.score
        // A plan changed mid-workout is recorded as it stood at the end.
        result = WorkoutRecord(date: Date(), rounds: score.rounds, extraReps: score.reps,
                               durationSeconds: min(elapsed, plan.duration), completed: completed,
                               repsPerRound: plan.repsPerRound, roundTimestamps: roundTimestamps, plan: plan)
        if completed {
            audio.endSignal()
        }
        sync()
    }

    private func teardown() {
        guard cameraRunning else { return }
        camera.stop()
        cameraRunning = false
        processor.setLogger(nil)
        logger?.close()
        UIApplication.shared.isIdleTimerDisabled = false
    }
}
