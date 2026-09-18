package me.raddatz.cindy.calibration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.cindy.EngineFixtures
import me.raddatz.cindy.FakeFrameSource
import me.raddatz.cindy.RecordingAudio
import me.raddatz.cindy.camera.CameraAvailability
import me.raddatz.cindy.camera.CameraException
import me.raddatz.cindy.camera.CameraFailure
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.calibration.CalibrationFailure
import me.raddatz.cindy.core.calibration.CalibrationStore
import me.raddatz.cindy.core.signal.FrameObservation
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationEngineTests {
    private val frames = FakeFrameSource()
    private val audio = RecordingAudio()
    private val directory: File = Files.createTempDirectory("cindy-calibration").toFile()
    private val store = CalibrationStore(File(directory, "calibration.json"))

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun TestScope.engine(exercises: List<Exercise>, store: CalibrationStore = this@CalibrationEngineTests.store) =
        CalibrationEngine(
            store = store,
            existing = null,
            exercises = exercises,
            frameSource = frames,
            audio = audio,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = Clock.fixed(EngineFixtures.epoch, ZoneOffset.UTC),
        )

    private suspend fun TestScope.capturing(engine: CalibrationEngine) {
        engine.begin()
        engine.startExercise()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
    }

    private fun TestScope.emitAll(observations: List<FrameObservation>) {
        for (observation in observations) {
            frames.emit(observation)
            runCurrent()
        }
    }

    @Test
    fun holdsAreSkipped() = runTest {
        val engine = engine(listOf(Exercise.PUSH_UP, Exercise.PLANK, Exercise.SQUAT))
        assertEquals(listOf(Exercise.PUSH_UP, Exercise.SQUAT), engine.state.value.exercises)
        assertEquals(2, engine.state.value.stepCount)
        engine.close()
    }

    @Test
    fun oneCyclePerExerciseCalibratesAndSavesTheProfile() = runTest {
        val engine = engine(listOf(Exercise.PUSH_UP))
        assertEquals(CalibrationStep.Intro, engine.state.value.step)
        engine.begin()
        assertEquals(CalibrationStep.Ready(Exercise.PUSH_UP), engine.state.value.step)
        assertEquals(SignalSource.FACE, engine.state.value.trackedSource)
        assertEquals(1, frames.relocks)
        assertTrue(engine.keepScreenOn.value)
        assertEquals(1, engine.state.value.stepNumber)
        assertEquals(1, engine.state.value.stepCount)

        engine.startExercise()
        runCurrent()
        assertEquals(CalibrationStep.Countdown(Exercise.PUSH_UP, 3), engine.state.value.step)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(CalibrationStep.Capturing(Exercise.PUSH_UP), engine.state.value.step)
        assertEquals(listOf("beep", "beep", "beep", "go"), audio.events)
        assertEquals(Exercise.PUSH_UP, frames.pipeline?.exercise)

        emitAll(EngineFixtures.pushUpFrames(reps = 1, start = 100.0))
        val succeeded = assertIs<CalibrationStep.Succeeded>(engine.state.value.step)
        assertEquals(SignalSource.FACE, succeeded.calibration.source)
        assertEquals(0.04f, succeeded.calibration.baseline, 0.001f)
        assertNull(frames.pipeline)
        assertEquals("beep", audio.events.last())
        assertTrue(engine.state.value.captureProgress > 0)

        engine.continueToNext()
        assertEquals(CalibrationStep.Done, engine.state.value.step)
        assertEquals("end", audio.events.last())
        assertFalse(engine.keepScreenOn.value)
        assertFalse(frames.isRunning)
        val saved = assertNotNull(store.load())
        assertEquals(succeeded.calibration, saved.calibration(Exercise.PUSH_UP))
        assertEquals(EngineFixtures.epoch, saved.createdAt)
        engine.close()
    }

    @Test
    fun theTimeoutExplainsWhatWasMissing() = runTest {
        val engine = engine(listOf(Exercise.PUSH_UP))
        capturing(engine)
        advanceTimeBy(16_000)
        runCurrent()
        assertEquals(CalibrationStep.Failed(Exercise.PUSH_UP, CalibrationFailure.NoFace), engine.state.value.step)
        assertNull(frames.pipeline)

        engine.retry()
        assertEquals(CalibrationStep.Ready(Exercise.PUSH_UP), engine.state.value.step)
        engine.close()
    }

    @Test
    fun aBrightnessDipWithoutABodyIsNoSquat() = runTest {
        val engine = engine(listOf(Exercise.SQUAT))
        engine.begin()
        assertEquals(SignalSource.BRIGHTNESS, engine.state.value.trackedSource)
        assertTrue(frames.detectsFace)
        assertTrue(frames.detectsPose)
        assertTrue(frames.measuresMetrics)
        engine.startExercise()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        emitAll(EngineFixtures.squatBrightnessFrames(start = 50.0))
        assertEquals(CalibrationStep.Failed(Exercise.SQUAT, CalibrationFailure.NoPerson), engine.state.value.step)
        engine.close()
    }

    @Test
    fun anInterruptedCaptureStartsOver() = runTest {
        val engine = engine(listOf(Exercise.PUSH_UP, Exercise.SQUAT))
        capturing(engine)
        emitAll(EngineFixtures.pushUpFrames(reps = 0, start = 0.0))
        frames.report(CameraAvailability.Interrupted)
        runCurrent()
        assertEquals(CalibrationStep.Ready(Exercise.PUSH_UP), engine.state.value.step)
        assertNull(frames.pipeline)
        // The timeout of the abandoned capture does not fire later.
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(CalibrationStep.Ready(Exercise.PUSH_UP), engine.state.value.step)

        frames.report(CameraAvailability.Failed(CameraFailure.DID_NOT_COME_BACK))
        runCurrent()
        assertEquals(
            CalibrationStep.Error(CalibrationProblem.Camera(CameraFailure.DID_NOT_COME_BACK)),
            engine.state.value.step,
        )
        engine.close()
        assertTrue(frames.released)
    }

    @Test
    fun cameraAndSaveFailuresEndTheFlow() = runTest {
        frames.startFailure = CameraException(CameraFailure.NO_FRONT_CAMERA)
        val noCamera = engine(listOf(Exercise.PUSH_UP))
        noCamera.begin()
        assertEquals(
            CalibrationStep.Error(CalibrationProblem.Camera(CameraFailure.NO_FRONT_CAMERA)),
            noCamera.state.value.step,
        )
        noCamera.close()

        frames.startFailure = null
        val unwritable = CalibrationStore(File(directory, "missing/calibration.json"))
        val engine = engine(listOf(Exercise.PUSH_UP), unwritable)
        capturing(engine)
        emitAll(EngineFixtures.pushUpFrames(reps = 1))
        engine.continueToNext()
        assertIs<CalibrationProblem.SaveFailed>(assertIs<CalibrationStep.Error>(engine.state.value.step).problem)
        assertFalse(engine.keepScreenOn.value)
        engine.close()
    }
}
