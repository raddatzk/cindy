package me.raddatz.cindy.workout

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
import me.raddatz.cindy.calibration.CalibrationEngine
import me.raddatz.cindy.camera.CameraAvailability
import me.raddatz.cindy.camera.CameraException
import me.raddatz.cindy.camera.CameraFailure
import me.raddatz.cindy.camera.FrameSource
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.calibration.CalibrationProfile
import me.raddatz.cindy.core.debug.FrameLogger
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.core.signal.FrameObservation
import me.raddatz.cindy.core.signal.PipelineOutput
import me.raddatz.cindy.core.signal.RepDetectorEvent
import me.raddatz.cindy.core.signal.SignalPipeline
import me.raddatz.cindy.core.workout.WorkoutEvent
import me.raddatz.cindy.core.workout.WorkoutPhase
import me.raddatz.cindy.core.workout.WorkoutScore
import me.raddatz.cindy.core.workout.WorkoutStateMachine
import java.io.File
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/** Per-frame values, kept apart from [WorkoutState] so a frame does not recompose the whole screen. */
data class LiveSignal(
    /** Whether the tracked subject (face or body, see `trackedSource`) is in the current frame. */
    val subjectDetected: Boolean = false,
    /** Smoothed signal of the current frame. */
    val value: Float? = null,
)

/** Why a workout could not start or stopped. User-facing texts are quoted per case. */
sealed interface WorkoutError {
    /** "No complete calibration available." */
    data object NoCalibration : WorkoutError

    /** See [CameraFailure]. */
    data class Camera(val failure: CameraFailure, val detail: String? = null) : WorkoutError
}

data class WorkoutState(
    val phase: WorkoutPhase,
    val exercise: Exercise,
    /** The exercise after the current one (for the on-screen hint). */
    val nextExercise: Exercise,
    val repCount: Int,
    val currentRound: Int,
    val score: WorkoutScore,
    /** Active seconds on the AMRAP clock. */
    val elapsed: Double,
    /** Can change during a pause, see [WorkoutEngine.updatePlan]. */
    val plan: WorkoutPlan,
    val countdownValue: Int = 0,
    val isSignalArmed: Boolean = false,
    val trackedSource: SignalSource = SignalSource.FACE,
    /** Accumulated plank time of the current hold (null for rep exercises). */
    val heldSeconds: Double? = null,
    val error: WorkoutError? = null,
    /**
     * Set while the camera is away ("The camera was interrupted. Cindy counts itself back in as
     * soon as it is back."). The workout is paused and stays paused until the frames are back.
     */
    val cameraInterrupted: Boolean = false,
    /** Counts down out loud before the workout picks itself back up after an interruption. */
    val resumeCountdown: Int? = null,
    /** Set once the workout is finished or aborted. */
    val result: WorkoutRecord? = null,
    /** The CSV being written while recording is on. */
    val logFile: File? = null,
) {
    val remaining: Double get() = maxOf(plan.duration - elapsed, 0.0)
    val isPaused: Boolean get() = phase == WorkoutPhase.PAUSED
    val isLogging: Boolean get() = logFile != null
}

/**
 * Runs a Cindy workout: frames → signal chain → state machine, plus the AMRAP clock and audio
 * feedback (iOS: `WorkoutEngine`). All state lives on [dispatcher] (the main thread in the app).
 *
 * Single use: create one per workout, [start] it, and [close] it when the screen goes away.
 *
 * @param frameLoggerFactory creates the per-frame CSV logger when recording is on (label
 *   "workout"); `null` records nothing.
 * @param monotonicMillis clock for the AMRAP time (tests pass the virtual time).
 * @param clock stamps the finished record.
 */
class WorkoutEngine(
    private val profile: CalibrationProfile,
    plan: WorkoutPlan = WorkoutPlan.cindy,
    private val config: SignalConfig = SignalConfig.default,
    val frameSource: FrameSource,
    private val audio: AudioCues,
    private val frameLoggerFactory: ((label: String) -> FrameLogger)? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clock: Clock = Clock.systemUTC(),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val machine = WorkoutStateMachine(plan)
    private var plan: WorkoutPlan = plan

    private val _state = MutableStateFlow(
        WorkoutState(
            phase = machine.phase,
            exercise = machine.exercise,
            nextExercise = plan.next(machine.exercise),
            repCount = 0,
            currentRound = 1,
            score = machine.score,
            elapsed = 0.0,
            plan = plan,
        ),
    )
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private val _live = MutableStateFlow(LiveSignal())
    val live: StateFlow<LiveSignal> = _live.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(false)

    /** iOS `isIdleTimerDisabled`: true while the camera runs; the UI keeps the screen on. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private var timerJob: Job? = null
    private var accumulated = 0.0
    private var segmentStartMillis: Long? = null
    private var countdownJob: Job? = null
    private var resumeJob: Job? = null

    /**
     * Whether the current pause is one the interruption caused rather than one the athlete asked
     * for. Only the former picks itself back up.
     */
    private var pausedByInterruption = false
    private var cameraRunning = false
    private var isStarting = false
    private var isClosed = false
    private var logger: FrameLogger? = null

    /** State string for the CSV, written on the engine's thread and read on the frame thread. */
    private val logState = AtomicReference("")
    private val roundTimestamps = mutableListOf<Double>()

    init {
        frameSource.onFrame = { observation, output -> scope.launch { handle(observation, output) } }
        frameSource.onAvailabilityChange = { availability -> scope.launch { handle(availability) } }
    }

    /** Exercises that can join the plan mid-workout: only calibrated ones can be counted. */
    val calibratedExercises: List<Exercise>
        get() = Exercise.entries.filter { profile.calibration(it) != null }

    /** Shortest AMRAP length (minutes) the clock has not passed yet. */
    val shortestDurationAhead: Int
        get() = WorkoutPlan.durationChoices.firstOrNull { (it * 60).toDouble() > currentElapsed() } ?: plan.durationMinutes

    // Lifecycle

    /** Opens the camera and starts the countdown. Call on the engine's thread. */
    suspend fun start() {
        if (_state.value.phase != WorkoutPhase.IDLE || isStarting || isClosed || _state.value.result != null) return
        if (!profile.isComplete(plan)) {
            _state.update { it.copy(error = WorkoutError.NoCalibration) }
            return
        }
        isStarting = true
        try {
            frameSource.start()
        } catch (e: CameraException) {
            _state.update { it.copy(error = WorkoutError.Camera(e.failure, e.detail)) }
            return
        } finally {
            isStarting = false
        }
        if (isClosed) {
            frameSource.stop()
            return
        }
        audio.prepare()
        // Created only once the workout really starts, so aborted setups leave no empty files.
        frameLoggerFactory?.let { factory ->
            logger = try {
                factory("workout")
            } catch (_: Exception) {
                null
            }
            logger?.let { logger ->
                frameSource.setLogger(logger) { logState.get() }
                _state.update { it.copy(logFile = logger.file) }
            }
        }
        cameraRunning = true
        _keepScreenOn.value = true

        machine.beginCountdown()
        sync()
        countdownJob = scope.launch {
            for (remaining in config.workoutCountdownSeconds downTo 1) {
                _state.update { it.copy(countdownValue = remaining) }
                audio.beep()
                delay(1_000)
            }
            _state.update { it.copy(countdownValue = 0) }
            beginWorkout()
        }
    }

    fun pause() {
        if (!machine.isRunning) return
        machine.pause()
        stopClock()
        frameSource.setPipeline(null)
        sync()
    }

    fun resume() {
        if (machine.phase != WorkoutPhase.PAUSED || _state.value.cameraInterrupted) return
        cancelAutoResume()
        // Whatever took the audio away during the pause, this is the last moment before the
        // phone goes back on the floor and the beeps become the only feedback again.
        audio.prepare()
        machine.resume()
        startClock()
        installPipeline(machine.exercise)
        sync()
    }

    /** Stops early; the partial score is kept as an incomplete record. */
    fun abort() {
        val phase = _state.value.phase
        if (phase == WorkoutPhase.FINISHED || phase == WorkoutPhase.IDLE) return
        cancelAutoResume()
        countdownJob?.cancel()
        finishWorkout(completed = false)
    }

    /** Manual +1 / −1 correction. */
    fun adjust(delta: Int) {
        val before = machine.exercise
        apply(machine.adjust(delta))
        if (machine.exercise != before && machine.phase != WorkoutPhase.PAUSED) {
            installPipeline(machine.exercise)
        }
        sync()
    }

    /**
     * Takes over a plan changed on the pause screen. The clock and the rounds done so far carry on;
     * the new pipeline is installed on resume. Refused for exercises without a calibration and for
     * a duration the clock has already passed.
     */
    fun updatePlan(newPlan: WorkoutPlan): Boolean {
        if (machine.phase != WorkoutPhase.PAUSED || !newPlan.isValid || !profile.isComplete(newPlan) ||
            !(newPlan.duration > currentElapsed())
        ) {
            return false
        }
        apply(machine.replacePlan(newPlan))
        plan = newPlan
        sync()
        return true
    }

    /** Stops everything and releases the camera; the engine cannot be used afterwards. */
    fun close() {
        if (isClosed) return
        isClosed = true
        cancelAutoResume()
        countdownJob?.cancel()
        stopClock()
        teardown()
        audio.release()
        frameSource.onFrame = null
        frameSource.onAvailabilityChange = null
        frameSource.release()
        scope.cancel()
    }

    // Private

    private fun beginWorkout() {
        if (machine.phase != WorkoutPhase.COUNTDOWN) return // aborted during the countdown
        val events = machine.start()
        audio.goSignal()
        apply(events)
        accumulated = 0.0
        startClock()
        installPipeline(machine.exercise)
        // The camera can go away during the countdown, where there is nothing to pause yet. The
        // workout then starts paused rather than counting nothing for as long as it lasts.
        if (_state.value.cameraInterrupted) pause()
        sync()
    }

    private fun installPipeline(exercise: Exercise) {
        val calibration = profile.calibration(exercise) ?: return
        val pipeline = SignalPipeline(
            exercise = exercise,
            thresholds = calibration.thresholds,
            source = calibration.source,
            config = config,
            holdSeconds = if (exercise.isHold) plan.target(exercise).toDouble() else null,
        )
        frameSource.setDetection(calibration.source)
        frameSource.setPipeline(pipeline)
        _state.update {
            it.copy(
                trackedSource = calibration.source,
                isSignalArmed = false,
                heldSeconds = if (exercise.isHold) 0.0 else null,
            )
        }
    }

    /**
     * A workout cannot count what it cannot see. Losing the camera pauses it instead of silently
     * dropping every rep until it comes back — a call, the app going to the background or another
     * app taking the camera all look like a perfectly still athlete from here.
     *
     * Coming back counts itself back in out loud rather than waiting for a tap: the phone is on the
     * floor, and whoever just hung up should be able to hear when to start moving instead of
     * having to find the screen.
     */
    private fun handle(availability: CameraAvailability) {
        if (isClosed) return
        when (availability) {
            CameraAvailability.Running -> {
                _state.update { it.copy(cameraInterrupted = false) }
                if (pausedByInterruption && machine.phase == WorkoutPhase.PAUSED) scheduleAutoResume()
            }
            CameraAvailability.Interrupted -> {
                cancelAutoResume()
                // A pause the athlete started themselves stays theirs to end.
                if (machine.phase != WorkoutPhase.PAUSED) pausedByInterruption = true
                _state.update { it.copy(cameraInterrupted = true) }
                pause()
            }
            is CameraAvailability.Failed -> {
                cancelAutoResume()
                _state.update {
                    it.copy(cameraInterrupted = false, error = WorkoutError.Camera(availability.failure, availability.detail))
                }
                pause()
            }
        }
    }

    private fun scheduleAutoResume() {
        resumeJob?.cancel()
        // Before the first beep, not after: a call leaves the audio without focus, and a silent
        // countdown would defeat the point.
        audio.prepare()
        resumeJob = scope.launch {
            for (remaining in config.workoutCountdownSeconds downTo 1) {
                _state.update { it.copy(resumeCountdown = remaining) }
                audio.beep()
                delay(1_000)
            }
            _state.update { it.copy(resumeCountdown = null) }
            audio.goSignal()
            resume()
        }
    }

    private fun cancelAutoResume() {
        resumeJob?.cancel()
        resumeJob = null
        if (_state.value.resumeCountdown != null) _state.update { it.copy(resumeCountdown = null) }
        pausedByInterruption = false
    }

    private fun handle(observation: FrameObservation, output: PipelineOutput?) {
        if (isClosed) return
        val trackedSource = _state.value.trackedSource
        _live.value = LiveSignal(
            subjectDetected = CalibrationEngine.subjectDetected(observation, trackedSource),
            value = output?.smoothed,
        )
        if (output == null || !machine.isRunning) return
        // Ignore stale frames from a pipeline that has already been replaced.
        if (output.exercise != machine.exercise) return
        _state.update { it.copy(isSignalArmed = output.isArmed) }
        val held = output.heldSeconds
        if (held != null) {
            val previous = (_state.value.heldSeconds ?: 0.0).toInt()
            _state.update { it.copy(heldSeconds = held) }
            if (held.toInt() / 10 > previous / 10 && held.toInt() < plan.target(output.exercise)) {
                audio.beep() // every 10 s of plank
            }
        }
        when (output.event) {
            RepDetectorEvent.Armed -> if (machine.phase == WorkoutPhase.TRANSITION) apply(machine.activate())
            is RepDetectorEvent.RepCompleted -> {
                apply(machine.registerRep())
                if (machine.exercise != output.exercise) installPipeline(machine.exercise)
            }
            RepDetectorEvent.Disarmed, is RepDetectorEvent.RepRejected, null -> Unit
        }
        sync()
    }

    private fun apply(events: List<WorkoutEvent>) {
        // The rep that finishes an exercise sounds higher, so the change is audible with the phone
        // on the floor.
        val finishesExercise = events.any { it is WorkoutEvent.ExerciseCompleted }
        for (event in events) {
            when (event) {
                is WorkoutEvent.RepCounted -> if (finishesExercise) audio.goSignal() else audio.beep()
                is WorkoutEvent.RoundCompleted -> roundTimestamps += currentElapsed()
                is WorkoutEvent.RoundReopened -> if (roundTimestamps.isNotEmpty()) roundTimestamps.removeAt(roundTimestamps.lastIndex)
                else -> Unit
            }
        }
    }

    private fun sync() {
        _state.update {
            it.copy(
                phase = machine.phase,
                exercise = machine.exercise,
                nextExercise = plan.next(machine.exercise),
                repCount = machine.repCount,
                currentRound = machine.currentRound,
                score = machine.score,
                plan = plan,
            )
        }
        if (logger != null) {
            logState.set("${machine.phase.name.lowercase()}|r${machine.currentRound}|${machine.exercise.rawValue}|${machine.repCount}")
        }
    }

    // Clock

    private fun startClock() {
        segmentStartMillis = monotonicMillis()
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(TICK_MILLIS)
                tick()
            }
        }
    }

    private fun currentElapsed(): Double {
        val start = segmentStartMillis ?: return accumulated
        return accumulated + (monotonicMillis() - start) / 1_000.0
    }

    private fun stopClock() {
        segmentStartMillis?.let { accumulated += (monotonicMillis() - it) / 1_000.0 }
        segmentStartMillis = null
        timerJob?.cancel()
        timerJob = null
    }

    private fun tick() {
        if (segmentStartMillis == null) return
        val elapsed = currentElapsed()
        _state.update { it.copy(elapsed = elapsed) }
        if (plan.duration - elapsed <= 0) finishWorkout(completed = true)
    }

    private fun finishWorkout(completed: Boolean) {
        stopClock()
        val events = machine.finish()
        if (events.isEmpty() && _state.value.result != null) return
        frameSource.setPipeline(null)
        teardown()
        val score = machine.score
        // A plan changed mid-workout is recorded as it stood at the end.
        val record = WorkoutRecord(
            date = clock.instant(),
            rounds = score.rounds,
            extraReps = score.reps,
            durationSeconds = minOf(_state.value.elapsed, plan.duration),
            completed = completed,
            repsPerRound = plan.repsPerRound,
            roundTimestamps = roundTimestamps.toList(),
            plan = plan,
        )
        _state.update { it.copy(result = record) }
        if (completed) audio.endSignal()
        audio.release()
        sync()
    }

    private fun teardown() {
        if (!cameraRunning) return
        frameSource.stop()
        cameraRunning = false
        frameSource.setLogger(null)
        logger?.close()
        _keepScreenOn.value = false
    }

    private companion object {
        const val TICK_MILLIS = 100L
    }
}
