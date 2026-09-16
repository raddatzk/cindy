package me.raddatz.cindy.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.raddatz.cindy.AppContainer
import me.raddatz.cindy.CindyApplication
import me.raddatz.cindy.core.AppLanguage
import me.raddatz.cindy.core.AppTheme
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.calibration.CalibrationProfile
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.health.HealthAvailability
import me.raddatz.cindy.reminder.NotificationPermission
import me.raddatz.cindy.reminder.ReadinessSnapshot
import java.time.Instant

/** The switches of the settings screen, read back from `SettingsStore`. */
data class SettingsState(
    val syncToHealth: Boolean,
    val remindersEnabled: Boolean,
    val recordWorkouts: Boolean,
    val hasSeenIntro: Boolean,
    val theme: AppTheme,
    val language: AppLanguage,
)

/**
 * App-wide state over [AppContainer] (iOS: `AppModel`): the plan, the calibration profile, the
 * history, readiness and the settings with their side effects.
 *
 * Permission dialogs need an activity, so the screens launch them and hand the outcome in (see
 * [enableHealthSync] and [enableReminders]).
 */
class AppModel(val container: AppContainer, private val application: Application) : ViewModel() {
    private val settingsStore = container.settings

    private val _plan = MutableStateFlow(settingsStore.workoutPlan)

    /** Editable workout plan; persisted as JSON under the iOS key. */
    val plan: StateFlow<WorkoutPlan> = _plan.asStateFlow()

    private val _calibration = MutableStateFlow(container.calibrationStore.load())
    val calibration: StateFlow<CalibrationProfile?> = _calibration.asStateFlow()

    /** Newest first. */
    val history: StateFlow<List<WorkoutRecord>> = container.history.records

    val readiness: StateFlow<ReadinessSnapshot> = container.readiness.snapshot

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    /** A complete calibration for the current plan. */
    val isCalibrated: StateFlow<Boolean> = combine(_plan, _calibration) { plan, profile -> profile?.isComplete(plan) == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _calibration.value?.isComplete(_plan.value) == true)

    init {
        viewModelScope.launch {
            settingsStore.changes().collect { _settings.value = readSettings() }
        }
    }

    val lastCompletedRecord: WorkoutRecord? get() = container.history.lastCompletedRecord

    private fun readSettings() = SettingsState(
        syncToHealth = settingsStore.syncToHealth,
        remindersEnabled = settingsStore.remindersEnabled,
        recordWorkouts = settingsStore.recordWorkouts,
        hasSeenIntro = settingsStore.hasSeenIntro,
        theme = settingsStore.appTheme,
        language = settingsStore.appLanguage,
    )

    private fun updateSettings() {
        _settings.value = readSettings()
    }

    // Plan, calibration, history

    fun setPlan(plan: WorkoutPlan) {
        if (!plan.isValid) return
        settingsStore.workoutPlan = plan
        _plan.value = plan
    }

    fun markIntroSeen() {
        settingsStore.hasSeenIntro = true
        updateSettings()
    }

    fun reload() {
        _calibration.value = container.calibrationStore.load()
        container.history.reload()
    }

    fun save(record: WorkoutRecord) = container.history.save(record)

    fun delete(record: WorkoutRecord) = container.history.delete(record)

    /** Recomputes readiness and moves the reminder; health data is read only while the sync is on. */
    fun refreshReadiness() {
        viewModelScope.launch { container.readiness.refresh() }
    }

    // Display

    fun setRecordWorkouts(enabled: Boolean) {
        settingsStore.recordWorkouts = enabled
        updateSettings()
    }

    fun setTheme(theme: AppTheme) {
        settingsStore.appTheme = theme
        updateSettings()
        AppCompatDelegate.setDefaultNightMode(theme.nightMode)
    }

    /** Recreates the activity in the new language (AppCompat stores the choice below Android 13). */
    fun setLanguage(language: AppLanguage) {
        settingsStore.appLanguage = language
        updateSettings()
        AppCompatDelegate.setApplicationLocales(language.localeList)
    }

    // Health Connect

    fun healthAvailability(): HealthAvailability = container.health.availability()

    suspend fun healthPermissionsToRequest(): Set<String> = container.health.requestedPermissions()

    /**
     * Turns the sync on once the permission sheet is closed. Returns false when writing workouts
     * was refused (iOS `enableHealthSync`).
     */
    suspend fun enableHealthSync(): Boolean {
        if (!container.health.canWriteWorkouts()) return false
        settingsStore.syncToHealth = true
        updateSettings()
        container.readiness.refresh()
        return true
    }

    fun disableHealthSync() {
        settingsStore.syncToHealth = false
        updateSettings()
        refreshReadiness()
    }

    /** Workouts still missing from Health Connect (only meaningful while the sync is on). */
    fun workoutsPendingInHealth(): Int = container.health.pendingCount(history.value)

    /** Writes the workouts saved before the sync was switched on. @throws me.raddatz.cindy.health.HealthException */
    suspend fun exportHistoryToHealth(): Int = container.health.exportMissing(history.value)

    // Reminder

    /**
     * Turns reminders on once the notification permission was asked for. Returns false when
     * notifications cannot be posted (iOS `enableReminders`).
     */
    suspend fun enableReminders(): Boolean {
        if (!NotificationPermission.canPost(application)) return false
        settingsStore.remindersEnabled = true
        updateSettings()
        container.readiness.refreshReminder()
        return true
    }

    fun disableReminders() {
        settingsStore.remindersEnabled = false
        updateSettings()
        container.reminderScheduler.cancel()
    }

    /** When the pending reminder will fire, read back from WorkManager. */
    suspend fun pendingReminderDate(): Instant? = container.reminderScheduler.pendingDate()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CindyApplication
                AppModel(application.container, application)
            }
        }
    }
}

val AppTheme.nightMode: Int
    get() = when (this) {
        AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

val AppLanguage.localeList: LocaleListCompat
    get() = languageCode?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()

/** The language AppCompat currently applies; also picks up a change made in the system settings. */
fun currentAppLanguage(): AppLanguage {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return AppLanguage.SYSTEM
    return when (locales[0]?.language) {
        AppLanguage.GERMAN.languageCode -> AppLanguage.GERMAN
        AppLanguage.ENGLISH.languageCode -> AppLanguage.ENGLISH
        else -> AppLanguage.SYSTEM
    }
}
