package me.raddatz.cindy.ui.text

import androidx.annotation.StringRes
import me.raddatz.cindy.R
import me.raddatz.cindy.core.AppLanguage
import me.raddatz.cindy.core.AppTheme
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.ExerciseSetLabel
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.calibration.CalibrationFailure
import me.raddatz.cindy.core.demo.DemoCue
import me.raddatz.cindy.core.health.Readiness
import me.raddatz.cindy.core.health.ReadinessDetail
import me.raddatz.cindy.core.workout.ProgressionReason

/*
 * Every typed text of `:core` mapped to its string resource. `:core` never localizes; the English
 * iOS key of each case is quoted in its KDoc.
 */

/** iOS `exercise.<rawValue>.plural` — the display name. */
@get:StringRes
val Exercise.pluralNameRes: Int
    get() = when (this) {
        Exercise.PULL_UP -> R.string.exercise_pull_up_plural
        Exercise.PUSH_UP -> R.string.exercise_push_up_plural
        Exercise.SQUAT -> R.string.exercise_squat_plural
        Exercise.PLANK -> R.string.exercise_plank_plural
    }

/** iOS `exercise.<rawValue>.singular`. */
@get:StringRes
val Exercise.singularNameRes: Int
    get() = when (this) {
        Exercise.PULL_UP -> R.string.exercise_pull_up_singular
        Exercise.PUSH_UP -> R.string.exercise_push_up_singular
        Exercise.SQUAT -> R.string.exercise_squat_singular
        Exercise.PLANK -> R.string.exercise_plank_singular
    }

/** iOS `unit.seconds` for holds, `unit.reps` otherwise. */
@get:StringRes
val Exercise.unitRes: Int
    get() = if (isHold) R.string.unit_seconds else R.string.unit_reps

val Exercise.displayName: UiText get() = UiText.Res(pluralNameRes)
val Exercise.singularName: UiText get() = UiText.Res(singularNameRes)

/** iOS `calibration instruction(for:)`. */
@get:StringRes
val Exercise.calibrationInstructionRes: Int
    get() = when (this) {
        Exercise.PULL_UP -> R.string.calibration_instruction_pull_up
        Exercise.PUSH_UP -> R.string.calibration_instruction_push_up
        Exercise.SQUAT -> R.string.calibration_instruction_squat
        Exercise.PLANK -> R.string.calibration_instruction_plank
    }

val ExerciseSetLabel.text: UiText
    get() = when (this) {
        is ExerciseSetLabel.Hold -> UiText.Res(R.string.plan_set_hold, seconds, exercise.displayName)
        is ExerciseSetLabel.Reps -> UiText.Res(
            R.string.plan_set_reps,
            count,
            if (singular) exercise.singularName else exercise.displayName,
        )
    }

/** "5 Pull-ups · 10 Push-ups · 15 Squats". */
val WorkoutPlan.summaryText: UiText get() = UiText.Joined(summary.map { it.text })

@get:StringRes
val DemoCue.textRes: Int
    get() = when (this) {
        DemoCue.PULL_UP_START_HANGING -> R.string.demo_cue_pull_up_start_hanging
        DemoCue.PULL_UP_CHIN_ABOVE_BAR -> R.string.demo_cue_pull_up_chin_above_bar
        DemoCue.PULL_UP_PHONE_UNDER_BAR -> R.string.demo_cue_pull_up_phone_under_bar
        DemoCue.PULL_UP_PLACEMENT -> R.string.demo_cue_pull_up_placement
        DemoCue.PUSH_UP_START_AT_TOP -> R.string.demo_cue_push_up_start_at_top
        DemoCue.PUSH_UP_CHEST_OFF_FLOOR -> R.string.demo_cue_push_up_chest_off_floor
        DemoCue.PUSH_UP_STRAIGHT_LINE -> R.string.demo_cue_push_up_straight_line
        DemoCue.PUSH_UP_PLACEMENT -> R.string.demo_cue_push_up_placement
        DemoCue.SQUAT_TOES_BEHIND_PHONE -> R.string.demo_cue_squat_toes_behind_phone
        DemoCue.SQUAT_DEPTH -> R.string.demo_cue_squat_depth
        DemoCue.SQUAT_LOOK_ANYWHERE -> R.string.demo_cue_squat_look_anywhere
        DemoCue.SQUAT_PLACEMENT -> R.string.demo_cue_squat_placement
        DemoCue.PLANK_FOREARMS -> R.string.demo_cue_plank_forearms
        DemoCue.PLANK_HOLD_STILL -> R.string.demo_cue_plank_hold_still
        DemoCue.PLANK_ONE_LINE -> R.string.demo_cue_plank_one_line
        DemoCue.PLANK_PLACEMENT -> R.string.demo_cue_plank_placement
    }

val CalibrationFailure.text: UiText
    get() = when (this) {
        CalibrationFailure.NoFace -> UiText.Res(R.string.calibration_failure_no_face)
        CalibrationFailure.NoPerson -> UiText.Res(R.string.calibration_failure_no_person)
        CalibrationFailure.TooWeak -> UiText.Res(R.string.calibration_failure_too_weak)
        CalibrationFailure.LowContrast -> UiText.Res(R.string.calibration_failure_low_contrast)
        CalibrationFailure.NoReturn -> UiText.Res(R.string.calibration_failure_no_return)
        is CalibrationFailure.ImplausibleDuration -> UiText.Res(
            R.string.calibration_failure_implausible_duration,
            FormattedNumber.Decimal(duration, 1),
            FormattedNumber.Decimal(0.5, 1),
            FormattedNumber.Decimal(5.0, 0),
        )
    }

val ProgressionReason.text: UiText
    get() = when (this) {
        is ProgressionReason.FinishFullDuration -> UiText.Plural(R.plurals.progression_finish_full_duration, durationMinutes)
        is ProgressionReason.RoundsDropped -> UiText.Res(R.string.progression_rounds_dropped, previousRounds, rounds)
        ProgressionReason.TooFewRounds -> UiText.Res(R.string.progression_too_few_rounds)
        is ProgressionReason.PaceFading -> UiText.Res(R.string.progression_pace_fading, FormattedNumber.Percent(reserve))
        is ProgressionReason.RaiseDuration ->
            UiText.Res(R.string.progression_raise_duration, FormattedNumber.Percent(reserve), durationMinutes)
        is ProgressionReason.RaiseReps ->
            UiText.Res(R.string.progression_raise_reps, FormattedNumber.Percent(reserve), exercise.displayName, target)
        is ProgressionReason.RaisePlank -> UiText.Res(R.string.progression_raise_plank, seconds)
        is ProgressionReason.RaiseDensity -> UiText.Plural(R.plurals.progression_raise_density, targetRounds)
    }

val ReadinessDetail.text: UiText
    get() = when (this) {
        is ReadinessDetail.SinceLastWorkout -> UiText.Res(R.string.readiness_since_last_workout, hoursSince, hoursSuggested)
        is ReadinessDetail.TrainingLoad -> UiText.Res(R.string.readiness_training_load, FormattedNumber.Decimal(ratio, 1))
        is ReadinessDetail.Sleep -> UiText.Res(R.string.readiness_sleep, FormattedNumber.Decimal(hours, 1))
        is ReadinessDetail.HeartRateVariability -> UiText.Res(R.string.readiness_hrv, milliseconds, averageMilliseconds)
        is ReadinessDetail.RestingHeartRate ->
            UiText.Res(R.string.readiness_resting_heart_rate, beatsPerMinute, averageBeatsPerMinute)
    }

@get:StringRes
val Readiness.Band.titleRes: Int
    get() = when (this) {
        Readiness.Band.REST -> R.string.readiness_band_rest
        Readiness.Band.EASY -> R.string.readiness_band_easy
        Readiness.Band.READY -> R.string.readiness_band_ready
        Readiness.Band.PRIMED -> R.string.readiness_band_primed
    }

@get:StringRes
val Readiness.Band.adviceRes: Int
    get() = when (this) {
        Readiness.Band.REST -> R.string.readiness_advice_rest
        Readiness.Band.EASY -> R.string.readiness_advice_easy
        Readiness.Band.READY -> R.string.readiness_advice_ready
        Readiness.Band.PRIMED -> R.string.readiness_advice_primed
    }

/** Language names stay in their own language; only the system option is translated. */
val AppLanguage.title: UiText
    get() = when (this) {
        AppLanguage.SYSTEM -> UiText.Res(R.string.common_automatic)
        AppLanguage.ENGLISH -> UiText.Verbatim("English")
        AppLanguage.GERMAN -> UiText.Verbatim("Deutsch")
    }

@get:StringRes
val AppTheme.titleRes: Int
    get() = when (this) {
        AppTheme.SYSTEM -> R.string.common_automatic
        AppTheme.LIGHT -> R.string.settings_theme_light
        AppTheme.DARK -> R.string.settings_theme_dark
    }

@get:StringRes
val SignalSource.nameRes: Int
    get() = when (this) {
        SignalSource.FACE -> R.string.signal_source_face
        SignalSource.POSE -> R.string.signal_source_pose
        SignalSource.BRIGHTNESS -> R.string.signal_source_brightness
    }
