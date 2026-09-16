package me.raddatz.cindy.ui

import me.raddatz.cindy.R
import me.raddatz.cindy.core.AppLanguage
import me.raddatz.cindy.core.AppTheme
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.calibration.CalibrationFailure
import me.raddatz.cindy.core.demo.DemoCue
import me.raddatz.cindy.core.demo.demo
import me.raddatz.cindy.core.health.Readiness
import me.raddatz.cindy.core.health.ReadinessDetail
import me.raddatz.cindy.core.reminder.NextSessionReason
import me.raddatz.cindy.core.workout.ProgressionReason
import me.raddatz.cindy.reminder.ReminderNotifications
import me.raddatz.cindy.ui.text.FormattedNumber
import me.raddatz.cindy.ui.text.UiText
import me.raddatz.cindy.ui.text.adviceRes
import me.raddatz.cindy.ui.text.calibrationInstructionRes
import me.raddatz.cindy.ui.text.nameRes
import me.raddatz.cindy.ui.text.pluralNameRes
import me.raddatz.cindy.ui.text.singularNameRes
import me.raddatz.cindy.ui.text.summaryText
import me.raddatz.cindy.ui.text.text
import me.raddatz.cindy.ui.text.textRes
import me.raddatz.cindy.ui.text.title
import me.raddatz.cindy.ui.text.titleRes
import me.raddatz.cindy.ui.text.unitRes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every typed text of `:core` has to resolve to a string resource. The ids are checked against
 * the generated `R` classes, so a case mapped to something that is not a string (or a plural
 * where a string is expected) fails here rather than on screen.
 */
class TextMappingTests {
    private val strings: Set<Int> = R.string::class.java.fields.map { it.getInt(null) }.toSet()
    private val plurals: Set<Int> = R.plurals::class.java.fields.map { it.getInt(null) }.toSet()

    /** The cases of a sealed interface, all declared inside it (no kotlin-reflect on the test classpath). */
    private fun caseCount(type: Class<*>): Int = type.declaredClasses.count { type.isAssignableFrom(it) }

    private fun assertString(id: Int, what: String) = assertTrue(id in strings, "$what is not a string resource")

    /** Walks a text and all nested arguments. */
    private fun assertResolvable(text: UiText, what: String) {
        when (text) {
            is UiText.Res -> {
                assertString(text.id, what)
                text.args.forEach { arg ->
                    if (arg is UiText) assertResolvable(arg, "$what argument")
                    else assertTrue(arg is Int || arg is FormattedNumber || arg is String, "$what has an unformattable argument $arg")
                }
            }
            is UiText.Plural -> assertTrue(text.id in plurals, "$what is not a plurals resource")
            is UiText.Verbatim -> assertTrue(text.text.isNotBlank(), "$what is blank")
            is UiText.Joined -> text.parts.forEach { assertResolvable(it, what) }
            is UiText.NaturalList -> text.parts.forEach { assertResolvable(it, what) }
        }
    }

    @Test
    fun exercisesHaveNamesUnitsAndInstructions() {
        for (exercise in Exercise.entries) {
            assertString(exercise.pluralNameRes, "$exercise plural")
            assertString(exercise.singularNameRes, "$exercise singular")
            assertString(exercise.unitRes, "$exercise unit")
            assertString(exercise.calibrationInstructionRes, "$exercise instruction")
        }
        // Names must not collide between exercises (a copy-paste slip in the mapping).
        assertEquals(Exercise.entries.size, Exercise.entries.map { it.pluralNameRes }.toSet().size)
        assertEquals(Exercise.entries.size, Exercise.entries.map { it.calibrationInstructionRes }.toSet().size)
    }

    @Test
    fun everyDemoCueHasItsOwnString() {
        for (cue in DemoCue.entries) assertString(cue.textRes, "$cue")
        assertEquals(DemoCue.entries.size, DemoCue.entries.map { it.textRes }.toSet().size)
        for (exercise in Exercise.entries) {
            val demo = exercise.demo
            (demo.cues + demo.placement + demo.keyCue).forEach { assertString(it.textRes, "$exercise cue $it") }
        }
    }

    @Test
    fun everyCalibrationFailureResolves() {
        val failures = listOf(
            CalibrationFailure.NoFace,
            CalibrationFailure.NoPerson,
            CalibrationFailure.TooWeak,
            CalibrationFailure.LowContrast,
            CalibrationFailure.NoReturn,
            CalibrationFailure.ImplausibleDuration(7.3),
        )
        // Guards the list above against a new case that is not mapped here.
        assertEquals(caseCount(CalibrationFailure::class.java), failures.size)
        failures.forEach { assertResolvable(it.text, "$it") }
        assertEquals(failures.size, failures.map { (it.text as UiText.Res).id }.toSet().size)
    }

    @Test
    fun everyProgressionReasonResolves() {
        val reasons = listOf(
            ProgressionReason.FinishFullDuration(20),
            ProgressionReason.RoundsDropped(12, 8),
            ProgressionReason.TooFewRounds,
            ProgressionReason.PaceFading(0.7),
            ProgressionReason.RaiseDuration(0.9, 15),
            ProgressionReason.RaiseReps(0.95, Exercise.PULL_UP, 4),
            ProgressionReason.RaisePlank(35),
            ProgressionReason.RaiseDensity(13),
        )
        assertEquals(caseCount(ProgressionReason::class.java), reasons.size)
        reasons.forEach { assertResolvable(it.text, "$it") }
    }

    @Test
    fun everyReadinessDetailResolves() {
        val details = listOf(
            ReadinessDetail.SinceLastWorkout(20, 48),
            ReadinessDetail.TrainingLoad(1.2),
            ReadinessDetail.Sleep(7.5),
            ReadinessDetail.HeartRateVariability(45, 50),
            ReadinessDetail.RestingHeartRate(55, 52),
        )
        assertEquals(caseCount(ReadinessDetail::class.java), details.size)
        details.forEach { assertResolvable(it.text, "$it") }
    }

    @Test
    fun enumsWithTitlesResolve() {
        for (band in Readiness.Band.entries) {
            assertString(band.titleRes, "$band title")
            assertString(band.adviceRes, "$band advice")
        }
        for (theme in AppTheme.entries) assertString(theme.titleRes, "$theme")
        for (language in AppLanguage.entries) assertResolvable(language.title, "$language")
        for (source in SignalSource.entries) assertString(source.nameRes, "$source")
        for (reason in NextSessionReason.entries) assertString(ReminderNotifications.bodyRes(reason), "$reason")
    }

    @Test
    fun planSummaryUsesSingularForOneRep() {
        val plan = WorkoutPlan.cindy.withTarget(1, Exercise.PULL_UP).withEnabled(Exercise.PLANK, true)
        val summary = plan.summaryText as UiText.Joined
        assertEquals(plan.sets.size, summary.parts.size)
        summary.parts.forEach { assertResolvable(it, "summary") }
        val pullUps = summary.parts.first() as UiText.Res
        assertEquals(R.string.plan_set_reps, pullUps.id)
        assertEquals(UiText.Res(R.string.exercise_pull_up_singular), pullUps.args[1])
        val plank = summary.parts.last() as UiText.Res
        assertEquals(R.string.plan_set_hold, plank.id)
    }
}
