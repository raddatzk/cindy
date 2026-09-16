package me.raddatz.cindy.ui.debug

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.cindy.AppContainer
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.camera.CameraException
import me.raddatz.cindy.camera.CameraFrameSource
import me.raddatz.cindy.camera.FrameSource
import me.raddatz.cindy.camera.VisionStats
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.calibration.CalibrationProfile
import me.raddatz.cindy.core.debug.FrameLogger
import me.raddatz.cindy.core.signal.FrameObservation
import me.raddatz.cindy.core.signal.PipelineOutput
import me.raddatz.cindy.core.signal.RepDetectorEvent
import me.raddatz.cindy.core.signal.RepThresholds
import me.raddatz.cindy.core.signal.SignalPipeline
import me.raddatz.cindy.ui.settings.csvFiles
import java.io.File
import java.io.IOException

data class DebugState(
    val exercise: Exercise = Exercise.PUSH_UP,
    val source: SignalSource = SignalSource.FACE,
    /** Runs body pose next to the face even when the face is the signal (CSV comparisons). */
    val bodyPose: Boolean = false,
    val latest: PipelineOutput? = null,
    val latestObservation: FrameObservation? = null,
    val history: List<Float> = emptyList(),
    val repCount: Int = 0,
    val isRecording: Boolean = false,
    val recordedRows: Int = 0,
    val logFiles: List<File> = emptyList(),
    /** Raw error text; the debug screen is not localized. */
    val errorMessage: String? = null,
    val running: Boolean = false,
    val usesCalibration: Boolean = false,
    val thresholds: RepThresholds = RepThresholds.hardcoded(Exercise.PUSH_UP),
    /** Frame rate and per-stage timing, so a slow phone shows where its time goes. */
    val stats: VisionStats? = null,
) {
    /** What the running detector compares against (relative thresholds are rescaled once armed). */
    val activeThresholds: RepThresholds get() = latest?.thresholds ?: thresholds
}

/** The debug recorder's camera and pipeline (iOS `DebugEngine`). */
class DebugViewModel(private val container: AppContainer, private val profile: CalibrationProfile?) : ViewModel() {
    val frameSource: CameraFrameSource = container.frameSource(ProcessLifecycleOwner.get())
    private val audio = container.audioFeedback()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var logger: FrameLogger? = null
    private var starting = false

    private val _state = MutableStateFlow(DebugState(logFiles = csvFiles(container.debugLogsDirectory)))
    val state: StateFlow<DebugState> = _state.asStateFlow()

    init {
        frameSource.setMetricsEnabled(true)
        frameSource.onFrame = { observation, output -> mainHandler.post { handle(observation, output) } }
        refreshThresholds()
    }

    fun start() {
        if (_state.value.running || starting) return
        starting = true
        viewModelScope.launch {
            try {
                frameSource.start()
                audio.prepare()
                _state.update { it.copy(running = true, errorMessage = null) }
                rebuildPipeline()
            } catch (e: CameraException) {
                _state.update { it.copy(errorMessage = e.message) }
            } finally {
                starting = false
            }
        }
    }

    fun setExercise(exercise: Exercise) {
        _state.update { it.copy(exercise = exercise) }
        rebuildPipeline()
    }

    fun setSource(source: SignalSource) {
        _state.update { it.copy(source = source) }
        rebuildPipeline()
    }

    fun setBodyPose(enabled: Boolean) {
        _state.update { it.copy(bodyPose = enabled) }
        updateDetection()
    }

    fun resetPipeline() = rebuildPipeline()

    fun startRecording() {
        if (_state.value.isRecording) return
        val current = _state.value
        try {
            val logger = FrameLogger(container.debugLogsDirectory, "${current.exercise.rawValue}_${current.source.rawValue}")
            this.logger = logger
            frameSource.setLogger(logger) { "debug" }
            _state.update { it.copy(isRecording = true, recordedRows = 0) }
        } catch (e: IOException) {
            _state.update { it.copy(errorMessage = "CSV konnte nicht angelegt werden: ${e.message}") }
        }
    }

    fun stopRecording() {
        if (!_state.value.isRecording) return
        frameSource.setLogger(null)
        logger?.close()
        logger = null
        _state.update { it.copy(isRecording = false, logFiles = csvFiles(container.debugLogsDirectory)) }
    }

    fun deleteLog(file: File) {
        file.delete()
        _state.update { it.copy(logFiles = csvFiles(container.debugLogsDirectory)) }
    }

    private fun refreshThresholds() {
        val exercise = _state.value.exercise
        val calibration = profile?.calibration(exercise)
        _state.update {
            it.copy(
                usesCalibration = calibration != null,
                thresholds = calibration?.thresholds ?: RepThresholds.hardcoded(exercise),
            )
        }
    }

    private fun rebuildPipeline() {
        refreshThresholds()
        updateDetection()
        val current = _state.value
        frameSource.setPipeline(
            SignalPipeline(
                exercise = current.exercise,
                thresholds = current.thresholds,
                source = current.source,
                config = container.config,
            ),
        )
        _state.update { it.copy(history = emptyList(), repCount = 0, latest = null) }
    }

    /**
     * The face always runs in the debug mode, so its drop-outs stay visible in recordings.
     * Detectors that do not produce the signal run at the workout's evidence interval, so the
     * frame rate shown here is the one a workout gets.
     */
    private fun updateDetection() {
        val current = _state.value
        frameSource.setDetection(face = true, bodyPose = current.bodyPose || current.source != SignalSource.FACE)
        frameSource.setDetectionIntervals(
            face = if (current.source == SignalSource.FACE) 0.0 else FrameSource.EVIDENCE_INTERVAL,
            bodyPose = if (current.source == SignalSource.POSE) 0.0 else FrameSource.EVIDENCE_INTERVAL,
        )
    }

    private fun handle(observation: FrameObservation, output: PipelineOutput?) {
        _state.update { current ->
            var history = current.history
            var repCount = current.repCount
            if (output != null) {
                repCount = output.repCount
                output.smoothed?.let { history = (history + it).takeLast(HISTORY_LENGTH) }
                if (output.event is RepDetectorEvent.RepCompleted) audio.beep()
            }
            current.copy(
                latest = output,
                latestObservation = observation,
                history = history,
                repCount = repCount,
                recordedRows = if (current.isRecording) current.recordedRows + 1 else current.recordedRows,
                stats = frameSource.stats,
            )
        }
    }

    override fun onCleared() {
        stopRecording()
        frameSource.onFrame = null
        mainHandler.removeCallbacksAndMessages(null)
        frameSource.stop()
        frameSource.release()
        audio.release()
    }

    companion object {
        private const val HISTORY_LENGTH = 300

        fun factory(model: AppModel): ViewModelProvider.Factory = viewModelFactory {
            initializer { DebugViewModel(model.container, model.calibration.value) }
        }
    }
}
