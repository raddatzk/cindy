package me.raddatz.cindy.ui.calibration

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.calibration.CalibrationEngine
import me.raddatz.cindy.core.Exercise

/**
 * Holds the calibration engine for as long as the screen is on the back stack. The camera is bound
 * to the process lifecycle, so recreating the activity (a language switch) does not unbind it,
 * while leaving the app still interrupts the capture.
 */
class CalibrationViewModel(model: AppModel) : ViewModel() {
    val engine: CalibrationEngine

    init {
        val profile = model.calibration.value
        val plan = model.plan.value
        // Only the gaps when a profile exists, e.g. squats after they moved to the brightness signal.
        val missing = profile?.missingExercises(plan).orEmpty()
        // A plank-only plan has nothing to calibrate in it; offer every rep exercise then.
        val planned = plan.exercises.filter { it.needsCalibration }
        engine = model.container.calibrationEngine(
            lifecycleOwner = ProcessLifecycleOwner.get(),
            exercises = missing.ifEmpty { planned.ifEmpty { Exercise.entries } },
        )
    }

    /** Starts the camera; the camera permission has to be granted. */
    fun begin() {
        viewModelScope.launch { engine.begin() }
    }

    override fun onCleared() {
        engine.close()
    }

    companion object {
        fun factory(model: AppModel): ViewModelProvider.Factory = viewModelFactory {
            initializer { CalibrationViewModel(model) }
        }
    }
}
