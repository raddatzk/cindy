package me.raddatz.cindy.ui.workout

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.workout.WorkoutEngine

/**
 * Holds the workout engine for as long as the workout screen is on the back stack. Bound to the
 * process lifecycle: an activity recreation keeps the camera, leaving the app interrupts and
 * pauses the workout, and coming back counts it back in.
 */
class WorkoutViewModel(model: AppModel) : ViewModel() {
    /** Null without a calibration; the start screen does not offer the workout then. */
    val engine: WorkoutEngine? = model.calibration.value?.let { profile ->
        model.container.workoutEngine(ProcessLifecycleOwner.get(), profile, model.plan.value)
    }

    private var started = false

    /** Opens the camera and starts the countdown, once; the camera permission has to be granted. */
    fun start() {
        val engine = engine ?: return
        if (started) return
        started = true
        viewModelScope.launch { engine.start() }
    }

    override fun onCleared() {
        engine?.close()
    }

    companion object {
        fun factory(model: AppModel): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorkoutViewModel(model) }
        }
    }
}
