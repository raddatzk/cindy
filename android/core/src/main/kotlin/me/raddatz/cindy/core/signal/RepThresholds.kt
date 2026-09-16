package me.raddatz.cindy.core.signal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.SignalConfig

/** Which way the signal moves during a rep, relative to the rest position. */
@Serializable
enum class RepDirection(val rawValue: String) {
    /** Rest at the low end; a rep rises above `high` and returns below `low`. */
    @SerialName("peak")
    PEAK("peak"),

    /** Rest at the high end; a rep falls below `low` and returns above `high`. */
    @SerialName("trough")
    TROUGH("trough"),
}

/** How relative thresholds follow the rest level measured when the detector arms. */
@Serializable
enum class RestAdaptation(val rawValue: String) {
    /** Multiply by rest / baseline: sizes in the image (face area, shoulder width), which scale with the distance to the phone. */
    @SerialName("scale")
    SCALE("scale"),

    /** Add rest − baseline: image brightness, which shifts with light and position. */
    @SerialName("shift")
    SHIFT("shift"),
}

/** Schmitt-trigger thresholds for one exercise. */
@Serializable
data class RepThresholds(
    val low: Float,
    val high: Float,
    val direction: RepDirection,
    /**
     * Rest value `low` and `high` were derived from. When set, the thresholds are relative: the
     * detector adapts them to the rest level it measures when arming, so the signal still works
     * when the athlete stands elsewhere than during calibration. `null` = absolute thresholds.
     */
    val baseline: Float? = null,
    val adaptation: RestAdaptation = RestAdaptation.SCALE,
) {
    val isRelative: Boolean
        get() {
            val baseline = baseline ?: return false
            return adaptation == RestAdaptation.SHIFT || baseline > 0
        }

    /** The same thresholds for a measured rest level; `baseline` becomes that level. */
    fun adapted(toRest: Float): RepThresholds {
        val baseline = baseline
        if (baseline == null || !isRelative) return this
        return when (adaptation) {
            RestAdaptation.SCALE -> {
                val scale = toRest / baseline
                RepThresholds(low * scale, high * scale, direction, toRest, adaptation)
            }
            RestAdaptation.SHIFT -> {
                val offset = toRest - baseline
                RepThresholds(low + offset, high + offset, direction, toRest, adaptation)
            }
        }
    }

    companion object {
        /**
         * Thresholds measured from the rest level instead of the cycle extremes: the rep leaves
         * rest after `leave` of the swing towards `extreme` and peaks after `peak` of it. Used
         * where a rep overshoots past rest on the way back (brightness), which would otherwise
         * put the rest-side threshold beyond the rest level itself.
         */
        fun fromRest(baseline: Float, extreme: Float, direction: RepDirection, leave: Float, peak: Float): RepThresholds {
            val swing = extreme - baseline
            val leaveValue = baseline + leave * swing
            val peakValue = baseline + peak * swing
            return RepThresholds(
                low = minOf(leaveValue, peakValue),
                high = maxOf(leaveValue, peakValue),
                direction = direction,
                baseline = baseline,
                adaptation = RestAdaptation.SHIFT,
            )
        }

        /** Derives thresholds from the observed signal extremes: `low = min + margin·range`, `high = max − margin·range`. */
        fun from(
            min: Float,
            max: Float,
            direction: RepDirection,
            margin: Float = SignalConfig.default.thresholdMargin,
        ): RepThresholds {
            val range = max - min
            return RepThresholds(low = min + margin * range, high = max - margin * range, direction = direction)
        }

        /**
         * Rough starting values for the debug mode when no calibration exists.
         * Face area is normalised (0…1); these assume a flat phone on the floor.
         */
        fun hardcoded(exercise: Exercise): RepThresholds = when (exercise) {
            Exercise.PUSH_UP -> RepThresholds(0.06f, 0.16f, RepDirection.PEAK)
            Exercise.SQUAT -> RepThresholds(0.006f, 0.012f, RepDirection.PEAK)
            Exercise.PULL_UP -> RepThresholds(0.0015f, 0.003f, RepDirection.TROUGH)
            Exercise.PLANK -> RepThresholds(0.04f, 0.10f, RepDirection.PEAK) // band, not a cycle
        }
    }
}
