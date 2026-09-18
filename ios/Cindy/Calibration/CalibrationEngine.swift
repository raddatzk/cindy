import Foundation
import Observation
import UIKit

/// Drives the calibration flow: for each exercise a countdown, then capture
/// until one full cycle is seen or the timeout hits. Saves the profile at the end.
@MainActor
@Observable
final class CalibrationEngine {
    enum Step: Equatable {
        case intro
        /// Shows the exercise instructions and waits for the athlete to tap start.
        case ready(Exercise)
        case countdown(Exercise, Int)
        case capturing(Exercise)
        case succeeded(Exercise, ExerciseCalibration)
        case failed(Exercise, CalibrationFailure)
        case done
        case cameraError(String)
    }

    private(set) var step: Step = .intro
    private(set) var liveValue: Float?
    /// Whether the tracked subject (face or body, see `trackedSource`) is in the current frame.
    private(set) var subjectDetected = false
    private(set) var trackedSource: SignalSource = .face
    private(set) var captureProgress: Double = 0
    private(set) var profile: CalibrationProfile

    let camera: CameraSession
    private let processor: FrameProcessor
    private let store: CalibrationStore
    private let config: SignalConfig
    private let audio: AudioFeedback
    private let exercises: [Exercise]
    private var exerciseIndex = 0

    private var trace: [CalibrationSample] = []
    /// Frames with a face or pose during capture; brightness needs a body for `BodyEvidence`.
    private var bodyFrames = 0
    private var captureStart: TimeInterval?
    private var countdownTask: Task<Void, Never>?
    private var timeoutTask: Task<Void, Never>?
    private var cameraRunning = false

    /// Holds in `exercises` are skipped: they run on a timer.
    init(store: CalibrationStore, existing: CalibrationProfile?, config: SignalConfig = .default,
         exercises: [Exercise] = Exercise.allCases, audio: AudioFeedback? = nil) {
        self.store = store
        self.config = config
        self.exercises = exercises.filter(\.needsCalibration)
        self.audio = audio ?? .shared
        self.profile = existing ?? CalibrationProfile()
        self.camera = CameraSession(config: config)
        self.processor = FrameProcessor(camera: camera)
        processor.onFrame = { [weak self] observation, output in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.handle(observation: observation, output: output) }
            }
        }
        // Already on the main queue, see `CameraSession.onAvailabilityChange`.
        camera.onAvailabilityChange = { [weak self] availability in
            MainActor.assumeIsolated { self?.handle(availability) }
        }
    }

    var currentExercise: Exercise? {
        exercises.indices.contains(exerciseIndex) ? exercises[exerciseIndex] : nil
    }

    var stepNumber: Int { min(exerciseIndex + 1, exercises.count) }
    var exerciseList: String { exercises.map(\.singularName).joined(separator: ", ") }
    var stepCount: Int { exercises.count }

    // MARK: - Flow

    /// Requests camera access and starts the preview. Moves on to the first exercise.
    func begin() async {
        guard await CameraSession.requestAccess() else {
            step = .cameraError(CameraError.notAuthorized.localizedDescription)
            return
        }
        do {
            try camera.configure()
        } catch {
            step = .cameraError(error.localizedDescription)
            return
        }
        audio.prepare()
        processor.prepareDepth(for: exercises.map { config.source(for: $0) })
        camera.start()
        cameraRunning = true
        UIApplication.shared.isIdleTimerDisabled = true
        exerciseIndex = 0
        goToReady()
    }

    /// Athlete tapped "Start": countdown, then capture.
    func startExercise() {
        guard let exercise = currentExercise, case .ready = step else { return }
        audio.prepare()
        countdownTask?.cancel()
        countdownTask = Task { [weak self] in
            guard let self else { return }
            for remaining in stride(from: self.config.calibrationCountdownSeconds, through: 1, by: -1) {
                self.step = .countdown(exercise, remaining)
                self.audio.beep()
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
            }
            self.audio.goSignal()
            self.beginCapture(exercise)
        }
    }

    func retry() {
        cancelTasks()
        goToReady()
    }

    func continueToNext() {
        guard case .succeeded(let exercise, let calibration) = step else { return }
        profile.set(calibration, for: exercise)
        exerciseIndex += 1
        if exerciseIndex >= exercises.count {
            finish()
        } else {
            goToReady()
        }
    }

    func cancel() {
        cancelTasks()
        teardown()
    }

    // MARK: - Private

    /// A trace with a hole in it is not a calibration, and the thresholds drawn
    /// from it would be wrong for every workout afterwards. A countdown or a
    /// capture that loses the camera is therefore thrown away and offered again
    /// from the start.
    private func handle(_ availability: CameraAvailability) {
        switch availability {
        case .running:
            break
        case .interrupted:
            switch step {
            case .countdown, .capturing:
                cancelTasks()
                goToReady()
            default:
                break
            }
        case .failed(let reason):
            cancelTasks()
            step = .cameraError(reason)
        }
    }

    private func goToReady() {
        guard let exercise = currentExercise else { return }
        processor.setPipeline(nil)
        trackedSource = config.source(for: exercise)
        processor.setDetection(for: trackedSource)
        step = .ready(exercise)
        camera.relockExposure()
    }

    private func beginCapture(_ exercise: Exercise) {
        trace.removeAll(keepingCapacity: true)
        bodyFrames = 0
        captureStart = nil
        captureProgress = 0
        // A fresh pipeline resets the EMA; its detector output is ignored here.
        let pipeline = SignalPipeline(exercise: exercise, thresholds: .hardcoded(for: exercise), config: config)
        processor.setPipeline(pipeline)
        step = .capturing(exercise)
        timeoutTask?.cancel()
        timeoutTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(for: .seconds(self.config.calibrationTimeout + 1))
            if Task.isCancelled { return }
            self.fail(exercise)
        }
    }

    private func handle(observation: FrameObservation, output: PipelineOutput?) {
        subjectDetected = Self.subjectDetected(in: observation, source: trackedSource, config: config)
        liveValue = output?.smoothed
        guard case .capturing(let exercise) = step, let output else { return }
        if captureStart == nil { captureStart = observation.timestamp }
        let elapsed = observation.timestamp - (captureStart ?? observation.timestamp)
        captureProgress = min(elapsed / config.calibrationTimeout, 1)

        if let value = output.smoothed, output.confidence >= config.minConfidence {
            trace.append(CalibrationSample(timestamp: observation.timestamp, value: value))
        }
        if observation.face != nil || observation.pose != nil { bodyFrames += 1 }
        let analyzer = CalibrationAnalyzer(config: config, source: output.source)
        if let calibration = analyzer.evaluate(trace) {
            timeoutTask?.cancel()
            processor.setPipeline(nil)
            if output.source == .brightness, bodyFrames == 0 {
                step = .failed(exercise, .noPerson)
                return
            }
            audio.beep()
            step = .succeeded(exercise, calibration)
        } else if elapsed >= config.calibrationTimeout {
            fail(exercise)
        }
    }

    /// Face for the face signal; otherwise any body (pose, or the face next to the brightness, or
    /// the face or something close to the phone in the depth map).
    static func subjectDetected(in observation: FrameObservation, source: SignalSource,
                                config: SignalConfig = .default) -> Bool {
        switch source {
        case .face: return observation.face != nil
        case .pose: return observation.pose != nil
        case .brightness: return observation.face != nil || observation.pose != nil
        case .depth:
            guard observation.face == nil else { return true }
            return observation.depth?.p05.map { $0 <= config.depthPresenceDistance } ?? false
        }
    }

    private func fail(_ exercise: Exercise) {
        guard case .capturing = step else { return }
        timeoutTask?.cancel()
        processor.setPipeline(nil)
        let source = config.source(for: exercise)
        let analyzer = CalibrationAnalyzer(config: config, source: source)
        let failure = analyzer.diagnose(trace)
        step = .failed(exercise, failure)
    }

    private func finish() {
        profile.createdAt = Date()
        do {
            try store.save(profile)
            step = .done
            audio.endSignal()
        } catch {
            step = .cameraError(L("The calibration could not be saved: \(error.localizedDescription)"))
        }
        teardown()
    }

    private func cancelTasks() {
        countdownTask?.cancel()
        timeoutTask?.cancel()
        countdownTask = nil
        timeoutTask = nil
    }

    private func teardown() {
        guard cameraRunning else { return }
        processor.setPipeline(nil)
        camera.stop()
        cameraRunning = false
        UIApplication.shared.isIdleTimerDisabled = false
    }
}
