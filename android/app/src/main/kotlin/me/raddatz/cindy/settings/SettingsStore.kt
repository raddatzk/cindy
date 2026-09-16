package me.raddatz.cindy.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import me.raddatz.cindy.core.AppLanguage
import me.raddatz.cindy.core.AppTheme
import me.raddatz.cindy.core.CindyJson
import me.raddatz.cindy.core.WorkoutPlan
import java.util.UUID

/**
 * App settings in `SharedPreferences`, under the keys iOS uses in `UserDefaults` (see
 * `AppModel.swift`), so the two platforms read the same way and debugging notes carry over.
 */
class SettingsStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    /** Editable workout plan; JSON like the iOS `Data` value. Invalid or missing → Cindy. */
    var workoutPlan: WorkoutPlan
        get() {
            val json = preferences.getString(Keys.WORKOUT_PLAN, null) ?: return WorkoutPlan.cindy
            val plan = try {
                CindyJson.decodeFromString(WorkoutPlan.serializer(), json)
            } catch (_: Exception) {
                return WorkoutPlan.cindy
            }
            return if (plan.isValid) plan.normalized() else WorkoutPlan.cindy
        }
        set(value) = preferences.edit { putString(Keys.WORKOUT_PLAN, CindyJson.encodeToString(WorkoutPlan.serializer(), value)) }

    /** Write every saved workout to Health Connect (and read it for readiness). */
    var syncToHealth: Boolean
        get() = preferences.getBoolean(Keys.SYNC_TO_HEALTH, false)
        set(value) = preferences.edit { putBoolean(Keys.SYNC_TO_HEALTH, value) }

    /** Nudge towards the next session with a notification. */
    var remindersEnabled: Boolean
        get() = preferences.getBoolean(Keys.REMINDERS_ENABLED, false)
        set(value) = preferences.edit { putBoolean(Keys.REMINDERS_ENABLED, value) }

    /** Debug: write a per-frame CSV for every workout (`filesDir/DebugLogs`). */
    var recordWorkouts: Boolean
        get() = preferences.getBoolean(Keys.RECORD_WORKOUTS, false)
        set(value) = preferences.edit { putBoolean(Keys.RECORD_WORKOUTS, value) }

    /** Whether the first-run intro has been shown; only ever set, never cleared. */
    var hasSeenIntro: Boolean
        get() = preferences.getBoolean(Keys.HAS_SEEN_INTRO, false)
        set(value) = preferences.edit { putBoolean(Keys.HAS_SEEN_INTRO, value) }

    var appTheme: AppTheme
        get() = preferences.getString(Keys.APP_THEME, null)?.let(AppTheme::fromRawValue) ?: AppTheme.SYSTEM
        set(value) = preferences.edit { putString(Keys.APP_THEME, value.rawValue) }

    var appLanguage: AppLanguage
        get() = preferences.getString(Keys.APP_LANGUAGE, null)?.let(AppLanguage::fromRawValue) ?: AppLanguage.SYSTEM
        set(value) = preferences.edit { putString(Keys.APP_LANGUAGE, value.rawValue) }

    /**
     * Workouts already written to Health Connect (upper-case UUID strings, like iOS). Drives the
     * backfill count; the export itself is idempotent through the client record id.
     */
    val healthExportedWorkoutIds: Set<String>
        get() = preferences.getStringSet(Keys.HEALTH_EXPORTED_WORKOUT_IDS, null).orEmpty().toSet()

    fun markExportedToHealth(id: UUID) {
        synchronized(this) {
            val ids = healthExportedWorkoutIds + id.toString().uppercase()
            preferences.edit { putStringSet(Keys.HEALTH_EXPORTED_WORKOUT_IDS, ids) }
        }
    }

    /** Emits the key of every changed setting. */
    fun changes(): Flow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null) trySend(key) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    object Keys {
        const val WORKOUT_PLAN = "workoutPlan"
        const val SYNC_TO_HEALTH = "syncToHealth"
        const val REMINDERS_ENABLED = "remindersEnabled"
        const val RECORD_WORKOUTS = "recordWorkouts"
        const val HAS_SEEN_INTRO = "hasSeenIntro"
        const val APP_THEME = "appTheme"
        const val APP_LANGUAGE = "appLanguage"
        const val HEALTH_EXPORTED_WORKOUT_IDS = "healthExportedWorkoutIDs"
    }

    companion object {
        const val FILE_NAME = "settings"
    }
}
