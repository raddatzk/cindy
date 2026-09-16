package me.raddatz.cindy.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.raddatz.cindy.core.signal.RepDirection

/**
 * The movements of the "Cindy" WOD, in the order they are performed, plus the plank.
 *
 * User-facing names live in the app layer. iOS keys: `exercise.<rawValue>.plural` and
 * `exercise.<rawValue>.singular`; the unit next to a target is `unit.seconds` for holds and
 * `unit.reps` otherwise.
 */
@Serializable
enum class Exercise(val rawValue: String) {
    /** "Pull-ups" / "Pull-up". */
    @SerialName("pullUp")
    PULL_UP("pullUp"),

    /** "Push-ups" / "Push-up". */
    @SerialName("pushUp")
    PUSH_UP("pushUp"),

    /** "Squats" / "Squat". */
    @SerialName("squat")
    SQUAT("squat"),

    /** Time-based hold: the face stays at a constant distance, the clock runs. "Plank". */
    @SerialName("plank")
    PLANK("plank");

    val id: String get() = rawValue

    /** true for exercises measured in seconds instead of reps. */
    val isHold: Boolean get() = this == PLANK

    /** Default target per round: reps for movements, seconds for holds. */
    val defaultTarget: Int
        get() = when (this) {
            PULL_UP -> 5
            PUSH_UP -> 10
            SQUAT -> 15
            PLANK -> 30
        }

    /**
     * Whether the singular name matches `count` — "1 pull-up" vs "5 pull-ups" (iOS `name(for:)`).
     * German and English both get by with these two forms.
     */
    fun usesSingularName(count: Int): Boolean = count == 1

    /**
     * Expected direction of the primary (face) signal relative to the rest position.
     * Used when no calibration is available (debug mode with hard-coded thresholds).
     * Calibration measures the real direction and overrides this.
     */
    val defaultRepDirection: RepDirection
        get() = when (this) {
            PULL_UP -> RepDirection.TROUGH // hanging = face closest → largest area; chin over bar = smaller
            PUSH_UP -> RepDirection.PEAK // top = face far → small area; bottom = close → large
            SQUAT -> RepDirection.PEAK // standing = far; bottom of squat = closer
            PLANK -> RepDirection.PEAK // unused: holds use a band, not a cycle
        }

    companion object {
        fun fromRawValue(rawValue: String): Exercise? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
