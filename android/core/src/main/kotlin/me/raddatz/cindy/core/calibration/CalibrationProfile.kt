package me.raddatz.cindy.core.calibration

import kotlinx.serialization.Serializable
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.InstantIso8601Serializer
import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.persistence.JSONFileStore
import me.raddatz.cindy.core.signal.RepDirection
import me.raddatz.cindy.core.signal.RepThresholds
import java.io.File
import java.time.Instant

/** Calibration result for one exercise. [repDuration] in seconds. */
@Serializable
data class ExerciseCalibration(
    val source: SignalSource,
    val minValue: Float,
    val maxValue: Float,
    val baseline: Float,
    val low: Float,
    val high: Float,
    val direction: RepDirection,
    val repDuration: Double,
    @Serializable(with = InstantIso8601Serializer::class)
    val calibratedAt: Instant,
) {
    /**
     * Relative to the calibrated baseline; [me.raddatz.cindy.core.signal.SignalPipeline] drops
     * the baseline for signals that do not scale with distance.
     */
    val thresholds: RepThresholds get() = RepThresholds(low, high, direction, baseline)

    val range: Float get() = maxValue - minValue
}

/** Calibrations of all exercises; stored as JSON. Keys are [Exercise.rawValue]. */
@Serializable
data class CalibrationProfile(
    val version: Int = CURRENT_VERSION,
    @Serializable(with = InstantIso8601Serializer::class)
    val createdAt: Instant = Instant.now(),
    val exercises: Map<String, ExerciseCalibration> = emptyMap(),
) {
    fun calibration(exercise: Exercise): ExerciseCalibration? = exercises[exercise.rawValue]

    /** Swift `set(_:for:)`, returning the changed copy. */
    fun withCalibration(calibration: ExerciseCalibration, exercise: Exercise): CalibrationProfile =
        copy(exercises = exercises + (exercise.rawValue to calibration))

    /** Only a profile covering every exercise of the plan can start a workout. */
    fun isComplete(plan: WorkoutPlan): Boolean = plan.exercises.all { exercises[it.rawValue] != null }

    val isComplete: Boolean get() = isComplete(WorkoutPlan.cindy)

    fun missingExercises(plan: WorkoutPlan): List<Exercise> = plan.exercises.filter { exercises[it.rawValue] == null }

    /**
     * Drops calibrations measured on a different signal source than the config uses now (squats
     * moved from the face to body pose); those exercises need a new calibration.
     */
    fun removingOutdated(config: SignalConfig): CalibrationProfile =
        copy(
            exercises = exercises.filter { (key, calibration) ->
                val exercise = Exercise.fromRawValue(key) ?: return@filter false
                calibration.source == config.source(exercise)
            },
        )

    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

/** Loads and saves the calibration profile (iOS: `calibration.json` in Documents). */
class CalibrationStore(file: File, private val config: SignalConfig = SignalConfig.default) {
    private val store = JSONFileStore(file, CalibrationProfile.serializer())

    /** The stored profile without calibrations for a signal source the config no longer uses. */
    fun load(): CalibrationProfile? {
        val profile = store.load() ?: return null
        if (profile.version != CalibrationProfile.CURRENT_VERSION) return null
        return profile.removingOutdated(config)
    }

    /** @throws java.io.IOException when the file cannot be written. */
    fun save(profile: CalibrationProfile) {
        store.save(profile)
    }

    fun delete() {
        store.delete()
    }
}
