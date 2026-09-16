package me.raddatz.cindy.workout

import android.content.Context
import me.raddatz.cindy.R
import me.raddatz.cindy.calibration.CalibrationProblem
import me.raddatz.cindy.camera.message

/** User-facing text for a workout error. */
fun WorkoutError.message(context: Context): String = when (this) {
    WorkoutError.NoCalibration -> context.getString(R.string.workout_error_no_calibration)
    is WorkoutError.Camera -> failure.message(context, detail)
}

/** The banner shown while [WorkoutState.cameraInterrupted] is set. */
fun cameraInterruptionMessage(context: Context): String = context.getString(R.string.workout_camera_interrupted)

/** User-facing text for what ended a calibration early. */
fun CalibrationProblem.message(context: Context): String = when (this) {
    is CalibrationProblem.Camera -> failure.message(context, detail)
    is CalibrationProblem.SaveFailed -> context.getString(R.string.calibration_error_save_failed, detail ?: "")
}
