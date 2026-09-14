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
    private(set) var faceDetected = false
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
    let plan: WorkoutPlan
    private let profile: CalibrationProfile
    private let config: SignalConfig
    private let audio: AudioFeedback

    private var timer: Timer?
    private var accumulated: TimeInterval = 0
    private var segmentStart: Date?
    private var firedMarks: Set<Int> = []
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
        audio.speak(L("Pause"))
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
        apply(events, spoken: false)
        if machine.exercise != before, machine.phase != .paused {
            installPipeline(for: machine.exercise)
        }
        sync()
    }

    // MARK: - Private

    private func beginWorkout() {
        guard machine.phase == .countdown else { return } // aborted during the countdown
        let events = machine.start()
        audio.speak(L("Go"))
        apply(events, spoken: true)
        accumulated = 0
        firedMarks = []
        startClock()
        installPipeline(for: machine.exercise)
        sync()
    }

    private func installPipeline(for exercise: Exercise) {
        guard let calibration = profile.calibration(for: exercise) else { return }
        let pipeline = SignalPipeline(exercise: exercise, thresholds: calibration.thresholds,
                                      source: calibration.source, config: config,
                                      holdSeconds: exercise.isHold ? TimeInterval(plan.target(for: exercise)) : nil)
        processor.setPipeline(pipeline)
        isSignalArmed = false
        heldSeconds = exercise.isHold ? 0 : nil
    }

    private func handle(observation: FrameObservation, output: PipelineOutput?) {
        faceDetected = observation.face != nil
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
                apply(machine.activate(), spoken: true)
            }
        case .repCompleted:
            let events = machine.registerRep()
            apply(events, spoken: true)
            if machine.exercise != output.exercise {
                installPipeline(for: machine.exercise)
            }
        case .disarmed, .repRejected, nil:
            break
        }
        sync()
    }

    private func apply(_ events: [WorkoutEvent], spoken: Bool) {
        for event in events {
            switch event {
            case .repCounted:
                audio.beep()
            case .roundCompleted(let round):
                roundTimestamps.append(currentElapsed())
                if spoken { audio.speak(L("Round \(round). \(plan.first.displayName)")) }
            case .roundReopened:
                if !roundTimestamps.isEmpty { roundTimestamps.removeLast() }
            case .exerciseCompleted(_, let next):
                // After a round the announcement above already names the first exercise.
                if spoken, !plan.isFirst(next) { audio.speak(next.displayName) }
            case .exerciseReopened(let exercise):
                if spoken { audio.speak(exercise.displayName) }
            case .started, .exerciseStarted, .repRemoved, .finished:
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
        let timer = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.tick() }
            }
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
        let remaining = self.remaining
        for mark in config.announcementMarks {
            let key = Int(mark)
            if remaining <= mark, !firedMarks.contains(key), remaining > 0 {
                firedMarks.insert(key)
                audio.speak(WorkoutEngine.announcement(forRemaining: mark))
            }
        }
        if remaining <= 0 {
            finishWorkout(completed: true)
        }
    }

    nonisolated static func announcement(forRemaining seconds: TimeInterval) -> String {
        let minutes = Int(seconds / 60)
        if minutes >= 1 { return L("\(minutes) minutes left") }
        return L("\(Int(seconds)) seconds left")
    }

    private func finishWorkout(completed: Bool) {
        stopClock()
        let events = machine.finish()
        guard !events.isEmpty || result == nil else { return }
        processor.setPipeline(nil)
        teardown()
        let score = machine.score
        result = WorkoutRecord(date: Date(), rounds: score.rounds, extraReps: score.reps,
                               durationSeconds: min(elapsed, plan.duration), completed: completed,
                               repsPerRound: plan.repsPerRound, roundTimestamps: roundTimestamps, plan: plan)
        if completed {
            audio.endSignal()
            audio.speak(L("Time. \(score.rounds) rounds plus \(score.reps)"))
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
