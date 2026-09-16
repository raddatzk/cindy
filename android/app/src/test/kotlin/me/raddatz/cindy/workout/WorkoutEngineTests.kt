package me.raddatz.cindy.workout

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.cindy.EngineFixtures
import me.raddatz.cindy.EngineFixtures.output
import me.raddatz.cindy.FakeFrameSource
import me.raddatz.cindy.RecordingAudio
import me.raddatz.cindy.camera.CameraAvailability
import me.raddatz.cindy.camera.CameraException
import me.raddatz.cindy.camera.CameraFailure
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.ExerciseSet
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.calibration.CalibrationProfile
import me.raddatz.cindy.core.debug.FrameLogger
import me.raddatz.cindy.core.signal.RepDetectorEvent
import me.raddatz.cindy.core.workout.WorkoutPhase
import java.nio.file.Files
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutEngineTests {
    private val frames = FakeFrameSource()
    private val audio = RecordingAudio()

    private fun TestScope.engine(
        plan: WorkoutPlan = WorkoutPlan.cindy,
        profile: CalibrationProfile = EngineFixtures.completeProfile,
        loggerFactory: ((String) -> FrameLogger)? = null,
    ) = WorkoutEngine(
        profile = profile,
        plan = plan,
        frameSource = frames,
        audio = audio,
        frameLoggerFactory = loggerFactory,
        dispatcher = StandardTestDispatcher(testScheduler),
        monotonicMillis = { testScheduler.currentTime },
        clock = Clock.fixed(EngineFixtures.epoch, ZoneOffset.UTC),
    )

    /** Starts the engine and runs through the five-second countdown. */
    private suspend fun TestScope.started(engine: WorkoutEngine) {
        engine.start()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
    }

    private fun TestScope.emit(exercise: Exercise, event: RepDetectorEvent?, heldSeconds: Double? = null) {
        frames.emit(EngineFixtures.face(0.05f, 0.0), output(exercise, event, heldSeconds = heldSeconds))
        runCurrent()
    }

    @Test
    fun countdownBeepsFiveTimesThenWaitsForTheSignal() = runTest {
        val engine = engine()
        engine.start()
        runCurrent()
        assertEquals(WorkoutPhase.COUNTDOWN, engine.state.value.phase)
        assertEquals(5, engine.state.value.countdownValue)
        assertTrue(engine.keepScreenOn.value)
        assertTrue(frames.isRunning)
        assertEquals(1, audio.prepared)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(WorkoutPhase.TRANSITION, engine.state.value.phase)
        assertEquals(0, engine.state.value.countdownValue)
        assertEquals(listOf("beep", "beep", "beep", "beep", "beep", "go"), audio.events)
        assertEquals(Exercise.PULL_UP, frames.pipeline?.exercise)
        assertEquals(SignalSource.FACE, engine.state.value.trackedSource)
        assertTrue(frames.detectsFace)
        assertFalse(frames.detectsPose)
        engine.close()
    }

    @Test
    fun armingActivatesAndTheLastRepMovesOnWithAHighTone() = runTest {
        val engine = engine()
        started(engine)
        audio.events.clear()

        emit(Exercise.PULL_UP, RepDetectorEvent.Armed)
        assertEquals(WorkoutPhase.ACTIVE, engine.state.value.phase)
        assertTrue(engine.state.value.isSignalArmed)

        repeat(4) { emit(Exercise.PULL_UP, RepDetectorEvent.RepCompleted(1.0)) }
        assertEquals(4, engine.state.value.repCount)
        emit(Exercise.PULL_UP, RepDetectorEvent.RepCompleted(1.0))

        assertEquals(Exercise.PUSH_UP, engine.state.value.exercise)
        assertEquals(Exercise.SQUAT, engine.state.value.nextExercise)
        assertEquals(WorkoutPhase.TRANSITION, engine.state.value.phase)
        assertEquals(listOf("beep", "beep", "beep", "beep", "go"), audio.events)
        // Squats count on the brightness, push-ups on the face: the push-up pipeline is installed now.
        assertEquals(Exercise.PUSH_UP, frames.pipeline?.exercise)
        assertEquals(5, engine.state.value.score.reps)

        // A late frame of the replaced pull-up pipeline is ignored.
        emit(Exercise.PULL_UP, RepDetectorEvent.RepCompleted(1.0))
        assertEquals(0, engine.state.value.repCount)
        engine.close()
    }

    @Test
    fun theRealPipelineCountsPushUps() = runTest {
        val plan = WorkoutPlan(listOf(ExerciseSet(Exercise.PUSH_UP, 10)), durationMinutes = 5)
        val engine = engine(plan)
        started(engine)
        for (frame in EngineFixtures.pushUpFrames(reps = 3)) {
            frames.emit(frame)
            runCurrent()
        }
        assertEquals(WorkoutPhase.ACTIVE, engine.state.value.phase)
        assertEquals(3, engine.state.value.repCount)
        assertTrue(engine.live.value.subjectDetected)
        assertNotNull(engine.live.value.value)
        engine.close()
    }

    @Test
    fun roundsAreTimedAndTheWorkoutEndsWithTheClock() = runTest {
        val plan = WorkoutPlan(listOf(ExerciseSet(Exercise.PUSH_UP, 1), ExerciseSet(Exercise.PULL_UP, 1)), durationMinutes = 5)
        val engine = engine(plan)
        started(engine)
        emit(Exercise.PUSH_UP, RepDetectorEvent.Armed)
        advanceTimeBy(10_000)
        emit(Exercise.PUSH_UP, RepDetectorEvent.RepCompleted(1.0))
        emit(Exercise.PULL_UP, RepDetectorEvent.Armed)
        advanceTimeBy(20_000)
        emit(Exercise.PULL_UP, RepDetectorEvent.RepCompleted(1.0))
        assertEquals(2, engine.state.value.currentRound)
        assertEquals(1, engine.state.value.score.rounds)

        advanceTimeBy(300_000)
        runCurrent()
        val result = assertNotNull(engine.state.value.result)
        assertEquals(WorkoutPhase.FINISHED, engine.state.value.phase)
        assertTrue(result.completed)
        assertEquals(1, result.rounds)
        assertEquals(0, result.extraReps)
        assertEquals(300.0, result.durationSeconds)
        assertEquals(1, result.roundTimestamps!!.size)
        assertEquals(30.0, result.roundTimestamps!![0], 0.001)
        assertEquals(plan, result.plan)
        assertEquals(EngineFixtures.epoch, result.date)
        assertEquals("end", audio.events.last())
        assertFalse(engine.keepScreenOn.value)
        assertFalse(frames.isRunning)
        assertNull(frames.pipeline)
        assertEquals(1, audio.released)
        engine.close()
    }

    @Test
    fun pauseStopsTheClockAndResumeComesBackThroughATransition() = runTest {
        val engine = engine()
        started(engine)
        emit(Exercise.PULL_UP, RepDetectorEvent.Armed)
        advanceTimeBy(3_000)
        runCurrent()
        engine.pause()
        val elapsed = engine.state.value.elapsed
        assertEquals(3.0, elapsed, 0.11)
        assertTrue(engine.state.value.isPaused)
        assertNull(frames.pipeline)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(elapsed, engine.state.value.elapsed)
        assertEquals(5, engine.shortestDurationAhead) // the clock has not passed 5 minutes yet

        engine.resume()
        assertEquals(WorkoutPhase.TRANSITION, engine.state.value.phase)
        assertEquals(Exercise.PULL_UP, frames.pipeline?.exercise)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(4.0, engine.state.value.elapsed, 0.11)
        engine.close()
    }

    @Test
    fun anInterruptionPausesAndCountsItselfBackIn() = runTest {
        val engine = engine()
        started(engine)
        emit(Exercise.PULL_UP, RepDetectorEvent.Armed)
        audio.events.clear()

        frames.report(CameraAvailability.Interrupted)
        runCurrent()
        assertTrue(engine.state.value.isPaused)
        assertTrue(engine.state.value.cameraInterrupted)
        assertNull(frames.pipeline)
        engine.resume() // refused while the camera is away
        assertTrue(engine.state.value.isPaused)

        frames.report(CameraAvailability.Running)
        runCurrent()
        assertFalse(engine.state.value.cameraInterrupted)
        assertEquals(5, engine.state.value.resumeCountdown)
        val prepared = audio.prepared

        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(1, engine.state.value.resumeCountdown)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(engine.state.value.resumeCountdown)
        assertEquals(WorkoutPhase.TRANSITION, engine.state.value.phase)
        assertEquals(listOf("beep", "beep", "beep", "beep", "beep", "go"), audio.events)
        assertTrue(audio.prepared > prepared)
        assertEquals(Exercise.PULL_UP, frames.pipeline?.exercise)
        engine.close()
    }

    @Test
    fun aPauseTheAthleteStartedStaysTheirs() = runTest {
        val engine = engine()
        started(engine)
        emit(Exercise.PULL_UP, RepDetectorEvent.Armed)
        engine.pause()
        frames.report(CameraAvailability.Interrupted)
        runCurrent()
        frames.report(CameraAvailability.Running)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(engine.state.value.resumeCountdown)
        assertTrue(engine.state.value.isPaused)
        engine.close()
    }

    @Test
    fun losingTheCameraDuringTheCountdownStartsPaused() = runTest {
        val engine = engine()
        engine.start()
        runCurrent()
        frames.report(CameraAvailability.Interrupted)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(engine.state.value.isPaused)

        frames.report(CameraAvailability.Running)
        runCurrent()
        assertEquals(5, engine.state.value.resumeCountdown)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(WorkoutPhase.TRANSITION, engine.state.value.phase)
        engine.close()
    }

    @Test
    fun aFailedCameraPausesWithAnError() = runTest {
        val engine = engine()
        started(engine)
        emit(Exercise.PULL_UP, RepDetectorEvent.Armed)
        frames.report(CameraAvailability.Failed(CameraFailure.STOPPED_UNEXPECTEDLY))
        runCurrent()
        assertTrue(engine.state.value.isPaused)
        assertEquals(WorkoutError.Camera(CameraFailure.STOPPED_UNEXPECTEDLY), engine.state.value.error)
        assertFalse(engine.state.value.cameraInterrupted)
        engine.close()
    }

    @Test
    fun startFailures() = runTest {
        val incomplete = CalibrationProfile(createdAt = EngineFixtures.epoch)
        val noCalibration = engine(profile = incomplete)
        noCalibration.start()
        assertEquals(WorkoutError.NoCalibration, noCalibration.state.value.error)
        assertEquals(0, frames.started)
        noCalibration.close()

        frames.startFailure = CameraException(CameraFailure.NOT_AUTHORIZED)
        val noCamera = engine()
        noCamera.start()
        assertEquals(WorkoutError.Camera(CameraFailure.NOT_AUTHORIZED), noCamera.state.value.error)
        assertEquals(WorkoutPhase.IDLE, noCamera.state.value.phase)
        assertFalse(noCamera.keepScreenOn.value)
        noCamera.close()
    }

    @Test
    fun planChangesOnlyWhilePausedAndCalibratedAndAhead() = runTest {
        val partial = CalibrationProfile(createdAt = EngineFixtures.epoch)
            .withCalibration(EngineFixtures.faceCalibration(), Exercise.PULL_UP)
            .withCalibration(EngineFixtures.faceCalibration(), Exercise.PUSH_UP)
            .withCalibration(EngineFixtures.brightnessCalibration(), Exercise.SQUAT)
        val engine = engine(profile = partial)
        started(engine)
        emit(Exercise.PULL_UP, RepDetectorEvent.Armed)
        advanceTimeBy(6 * 60_000L)
        runCurrent()

        val shorter = WorkoutPlan.cindy.copy(durationMinutes = 10)
        assertFalse(engine.updatePlan(shorter)) // not paused
        engine.pause()
        assertEquals(10, engine.shortestDurationAhead)
        assertFalse(engine.updatePlan(WorkoutPlan.cindy.copy(durationMinutes = 5))) // clock already past
        assertFalse(engine.updatePlan(WorkoutPlan.cindy.withEnabled(Exercise.PLANK, true))) // plank not calibrated
        assertTrue(engine.updatePlan(shorter))
        assertEquals(10, engine.state.value.plan.durationMinutes)
        assertEquals(240.0, engine.state.value.remaining, 0.11)
        engine.close()
    }

    @Test
    fun correctionsReopenARoundAndAbortKeepsTheScore() = runTest {
        val plan = WorkoutPlan(listOf(ExerciseSet(Exercise.PUSH_UP, 2)), durationMinutes = 5)
        val engine = engine(plan)
        started(engine)
        emit(Exercise.PUSH_UP, RepDetectorEvent.Armed)
        engine.adjust(1)
        engine.adjust(1)
        assertEquals(1, engine.state.value.score.rounds)
        engine.adjust(-1)
        assertEquals(0, engine.state.value.score.rounds)
        assertEquals(1, engine.state.value.repCount)

        advanceTimeBy(2_000)
        runCurrent()
        engine.abort()
        val result = assertNotNull(engine.state.value.result)
        assertFalse(result.completed)
        assertEquals(0, result.rounds)
        assertEquals(1, result.extraReps)
        assertEquals(emptyList(), result.roundTimestamps)
        assertFalse(audio.events.contains("end"))
        assertFalse(frames.isRunning)
        engine.close()
    }

    @Test
    fun plankBeepsEveryTenSecondsUntilTheTarget() = runTest {
        val plan = WorkoutPlan(listOf(ExerciseSet(Exercise.PLANK, 30)), durationMinutes = 5)
        val engine = engine(plan)
        started(engine)
        assertEquals(0.0, engine.state.value.heldSeconds)
        emit(Exercise.PLANK, RepDetectorEvent.Armed, heldSeconds = 0.0)
        audio.events.clear()
        for (held in listOf(5.0, 9.9, 10.0, 15.0, 20.2, 29.9)) emit(Exercise.PLANK, null, heldSeconds = held)
        assertEquals(2, audio.count("beep"))
        assertEquals(29.9, engine.state.value.heldSeconds)
        emit(Exercise.PLANK, RepDetectorEvent.RepCompleted(30.0), heldSeconds = 30.0)
        assertEquals(2, audio.count("beep"))
        assertEquals(1, audio.count("go"))
        assertEquals(1, engine.state.value.score.rounds)
        engine.close()
    }

    @Test
    fun recordingLogsTheStateMachineState() = runTest {
        val directory = Files.createTempDirectory("cindy-logs").toFile()
        val engine = engine(loggerFactory = { label -> FrameLogger(directory, label) })
        started(engine)
        val logger = assertNotNull(frames.logger)
        assertEquals(logger.file, engine.state.value.logFile)
        assertTrue(logger.file.name.endsWith("_workout.csv"))
        assertEquals("transition|r1|pullUp|0", frames.stateProvider?.invoke())

        frames.emit(EngineFixtures.face(0.04f, 1.0))
        runCurrent()
        engine.abort()
        assertNull(frames.logger)
        val lines = logger.file.readLines()
        assertEquals(FrameLogger.HEADER, lines.first())
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("transition|r1|pullUp|0"))
        engine.close()
        directory.deleteRecursively()
    }

    @Test
    fun closeReleasesTheCamera() = runTest {
        val engine = engine()
        started(engine)
        engine.close()
        assertTrue(frames.released)
        assertFalse(engine.keepScreenOn.value)
        assertNull(frames.onFrame)
    }
}
