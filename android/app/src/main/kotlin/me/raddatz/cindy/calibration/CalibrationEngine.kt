package me.raddatz.cindy.calibration

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.cindy.audio.AudioCues
import me.raddatz.cindy.camera.CameraAvailability
import me.raddatz.cindy.camera.CameraException
import me.raddatz.cindy.camera.CameraFailure
import me.raddatz.cindy.camera.FrameSource
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.calibration.CalibrationAnalyzer
import me.raddatz.cindy.core.calibration.CalibrationFailure
import me.raddatz.cindy.core.calibration.CalibrationProfile
import me.raddatz.cindy.core.calibration.CalibrationSample
import me.raddatz.cindy.core.calibration.CalibrationStore
import me.raddatz.cindy.core.calibration.ExerciseCalibration
import me.raddatz.cindy.core.signal.FrameObservation
import me.raddatz.cindy.core.signal.PipelineOutput
import me.raddatz.cindy.core.signal.RepThresholds
import me.raddatz.cindy.core.signal.SignalPipeline
import me.raddatz.cindy.workout.LiveSignal
import java.time.Clock
import kotlin.math.roundToLong

/** Where the calibration flow stands (iOS `CalibrationEngine.Step`). */
sealed interface CalibrationStep {
    data object Intro : CalibrationStep

    /** Shows the exercise instructions and waits for the athlete to tap start. */
    data class Ready(val exercise: Exercise) : CalibrationStep
    data class Countdown(val exercise: Exercise, val remaining: Int) : CalibrationStep
    data class Capturing(val exercise: Exercise) : CalibrationStep
    data class Succeeded(val exercise: Exercise, val calibration: ExerciseCalibration) : CalibrationStep
    data class Failed(val exercise: Exercise, val failure: CalibrationFailure) : CalibrationStep
    data object Done : CalibrationStep

    /** iOS `cameraError(String)`. */
    data class Error(val problem: CalibrationProblem) : CalibrationStep
}

/** What ended the calibration flow early. */
sealed interface CalibrationProblem {
    /** See [CameraFailure]. */
    data class Camera(val failure: CameraFailure, val detail: String? = null) : CalibrationProblem

    /** "The calibration could not be saved: %@" */
    data class SaveFailed(val detail: String?) : CalibrationProblem
}

data class CalibrationState(
    val step: CalibrationStep = CalibrationStep.Intro,
    /** The exercises calibrated in this flow, in order (the intro lists their singular names). */
    val exercises: List<Exercise>,
    /** 1-based number of the current exercise, capped at [stepCount]. */
    val stepNumber: Int = 1,
    val currentExercise: Exercise?,
    val trackedSource: SignalSource = SignalSource.FACE,
    /** 0…1 of the capture timeout. */
    val captureProgress: Double = 0.0,
    val profile: CalibrationProfile,
) {
    val stepCount: Int get() = exercises.size
}

/**
 * Drives the calibration flow: for each exercise a countdown, then capture until one full cycle is
 * seen or the timeout hits. Saves the profile at the end (iOS: `CalibrationEngine`).
 *
 * Single use: create one per flow, [begin] it, and [close] it when the screen goes away.
 */
class CalibrationEngine(
    private val store: CalibrationStore,
    existing: CalibrationProfile?,
    private val config: SignalConfig = SignalConfig.default,
    exercises: List<Exercise> = Exercise.entries,
    val frameSource: FrameSource,
    private val audio: AudioCues,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var exerciseIndex = 0

    /** Holds in the requested exercises are skipped: they run on a timer. */
    private val exercises: List<Exercise> = exercises.filter { it.needsCalibration }

    private val _state = MutableStateFlow(
        CalibrationState(
            exercises = this.exercises,
            currentExercise = this.exercises.firstOrNull(),
            profile = existing ?: CalibrationProfile(createdAt = clock.instant()),
        ),
    )
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private val _live = MutableStateFlow(LiveSignal())
    val live: StateFlow<LiveSignal> = _live.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(false)

    /** iOS `isIdleTimerDisabled`: true while the camera runs; the UI keeps the screen on. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val trace = ArrayList<CalibrationSample>()

    /** Frames with a face or pose during capture; brightness needs a body for `BodyEvidence`. */
    private var bodyFrames = 0
    private var captureStart: Double? = null
    private var countdownJob: Job? = null
    private var timeoutJob: Job? = null
    private var cameraRunning = false
    private var isClosed = false

    init {
        frameSource.onFrame = { observation, output -> scope.launch { handle(observation, output) } }
        frameSource.onAvailabilityChange = { availability -> scope.launch { handle(availability) } }
    }

    private val step: CalibrationStep get() = _state.value.step

    private val currentExercise: Exercise? get() = exercises.getOrNull(exerciseIndex)

    // Flow

    /** Starts the camera and moves on to the first exercise. Call on the engine's thread. */
    suspend fun begin() {
        if (cameraRunning || isClosed) return
        try {
            frameSource.start()
        } catch (e: CameraException) {
            setStep(CalibrationStep.Error(CalibrationProblem.Camera(e.failure, e.detail)))
            return
        }
        if (isClosed) {
            frameSource.stop()
            return
        }
        audio.prepare()
        cameraRunning = true
        _keepScreenOn.value = true
        exerciseIndex = 0
        goToReady()
    }

    /** Athlete tapped "Start": countdown, then capture. */
    fun startExercise() {
        val exercise = currentExercise ?: return
        if (step !is CalibrationStep.Ready) return
        audio.prepare()
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (remaining in config.calibrationCountdownSeconds downTo 1) {
                setStep(CalibrationStep.Countdown(exercise, remaining))
                audio.beep()
                delay(1_000)
            }
            audio.goSignal()
            beginCapture(exercise)
        }
    }

    fun retry() {
        cancelTasks()
        goToReady()
    }

    fun continueToNext() {
        val succeeded = step as? CalibrationStep.Succeeded ?: return
        _state.update { it.copy(profile = it.profile.withCalibration(succeeded.calibration, succeeded.exercise)) }
        exerciseIndex += 1
        if (exerciseIndex >= exercises.size) finish() else goToReady()
    }

    fun cancel() {
        cancelTasks()
        teardown()
    }

    /** Stops everything and releases the camera; the engine cannot be used afterwards. */
    fun close() {
        if (isClosed) return
        isClosed = true
        cancel()
        frameSource.onFrame = null
        frameSource.onAvailabilityChange = null
        frameSource.release()
        scope.cancel()
    }

    // Private

    /**
     * A trace with a hole in it is not a calibration, and the thresholds drawn from it would be
     * wrong for every workout afterwards. A countdown or a capture that loses the camera is
     * therefore thrown away and offered again from the start.
     */
    private fun handle(availability: CameraAvailability) {
        if (isClosed) return
        when (availability) {
            CameraAvailability.Running -> Unit
            CameraAvailability.Interrupted -> when (step) {
                is CalibrationStep.Countdown, is CalibrationStep.Capturing -> {
                    cancelTasks()
                    goToReady()
                }
                else -> Unit
            }
            is CameraAvailability.Failed -> {
                cancelTasks()
                setStep(CalibrationStep.Error(CalibrationProblem.Camera(availability.failure, availability.detail)))
            }
        }
    }

    private fun goToReady() {
        val exercise = currentExercise ?: return
        frameSource.setPipeline(null)
        val source = config.source(exercise)
        frameSource.setDetection(source)
        _state.update {
            it.copy(
                step = CalibrationStep.Ready(exercise),
                trackedSource = source,
                currentExercise = exercise,
                stepNumber = minOf(exerciseIndex + 1, exercises.size),
            )
        }
        frameSource.relockExposure()
    }

    private fun beginCapture(exercise: Exercise) {
        trace.clear()
        bodyFrames = 0
        captureStart = null
        // A fresh pipeline resets the EMA; its detector output is ignored here.
        val pipeline = SignalPipeline(exercise = exercise, thresholds = RepThresholds.hardcoded(exercise), config = config)
        frameSource.setPipeline(pipeline)
        _state.update { it.copy(step = CalibrationStep.Capturing(exercise), captureProgress = 0.0) }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(((config.calibrationTimeout + 1) * 1_000).roundToLong())
            fail(exercise)
        }
    }

    private fun handle(observation: FrameObservation, output: PipelineOutput?) {
        if (isClosed) return
        _live.value = LiveSignal(subjectDetected(observation, _state.value.trackedSource), output?.smoothed)
        val capturing = step as? CalibrationStep.Capturing ?: return
        if (output == null) return
        val exercise = capturing.exercise
        val start = captureStart ?: observation.timestamp.also { captureStart = it }
        val elapsed = observation.timestamp - start
        _state.update { it.copy(captureProgress = minOf(elapsed / config.calibrationTimeout, 1.0)) }

        val value = output.smoothed
        if (value != null && output.confidence >= config.minConfidence) {
            trace += CalibrationSample(observation.timestamp, value)
        }
        if (observation.face != null || observation.pose != null) bodyFrames += 1
        val analyzer = CalibrationAnalyzer(config = config, source = output.source, clock = clock)
        val calibration = analyzer.evaluate(trace)
        if (calibration != null) {
            timeoutJob?.cancel()
            frameSource.setPipeline(null)
            if (output.source == SignalSource.BRIGHTNESS && bodyFrames == 0) {
                setStep(CalibrationStep.Failed(exercise, CalibrationFailure.NoPerson))
                return
            }
            audio.beep()
            setStep(CalibrationStep.Succeeded(exercise, calibration))
        } else if (elapsed >= config.calibrationTimeout) {
            fail(exercise)
        }
    }

    private fun fail(exercise: Exercise) {
        if (step !is CalibrationStep.Capturing) return
        timeoutJob?.cancel()
        frameSource.setPipeline(null)
        val analyzer = CalibrationAnalyzer(config = config, source = config.source(exercise), clock = clock)
        val failure = analyzer.diagnose(trace)
        setStep(CalibrationStep.Failed(exercise, failure))
    }

    private fun finish() {
        val profile = _state.value.profile.copy(createdAt = clock.instant())
        _state.update { it.copy(profile = profile) }
        try {
            store.save(profile)
            setStep(CalibrationStep.Done)
            audio.endSignal()
        } catch (e: Exception) {
            setStep(CalibrationStep.Error(CalibrationProblem.SaveFailed(e.message)))
        }
        teardown()
    }

    private fun setStep(step: CalibrationStep) {
        _state.update { it.copy(step = step) }
    }

    private fun cancelTasks() {
        countdownJob?.cancel()
        timeoutJob?.cancel()
        countdownJob = null
        timeoutJob = null
    }

    private fun teardown() {
        if (!cameraRunning) return
        frameSource.setPipeline(null)
        frameSource.stop()
        cameraRunning = false
        _keepScreenOn.value = false
        audio.release()
    }

    companion object {
        /** Face for the face signal; otherwise any body (pose, or the face next to the brightness). */
        fun subjectDetected(observation: FrameObservation, source: SignalSource): Boolean = when (source) {
            SignalSource.FACE -> observation.face != null
            SignalSource.POSE -> observation.pose != null
            SignalSource.BRIGHTNESS -> observation.face != null || observation.pose != null
        }
    }
}
