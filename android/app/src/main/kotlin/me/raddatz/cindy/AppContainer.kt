package me.raddatz.cindy

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.raddatz.cindy.audio.AudioFeedback
import me.raddatz.cindy.calibration.CalibrationEngine
import me.raddatz.cindy.camera.CameraFrameSource
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.SignalConfig
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.core.calibration.CalibrationProfile
import me.raddatz.cindy.core.calibration.CalibrationStore
import me.raddatz.cindy.core.debug.FrameLogger
import me.raddatz.cindy.core.persistence.HistoryStore
import me.raddatz.cindy.health.HealthGateway
import me.raddatz.cindy.reminder.ReadinessService
import me.raddatz.cindy.reminder.ReminderScheduler
import me.raddatz.cindy.settings.SettingsStore
import me.raddatz.cindy.workout.WorkoutEngine
import me.raddatz.cindy.workout.WorkoutHistory
import java.io.File

/**
 * App-wide singletons, created lazily (manual dependency injection). Engines are made per screen
 * through the factory functions.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Outlives every screen: saving, exports and readiness refreshes started from the UI. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val config: SignalConfig = SignalConfig.default

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    /** `filesDir/history.json` (iOS: Documents/history.json). */
    val historyStore: HistoryStore by lazy { HistoryStore(File(appContext.filesDir, "history.json")) }

    /** `filesDir/calibration.json` (iOS: Documents/calibration.json). */
    val calibrationStore: CalibrationStore by lazy { CalibrationStore(File(appContext.filesDir, "calibration.json"), config) }

    /** `filesDir/DebugLogs`, shared through the `${applicationId}.files` FileProvider. */
    val debugLogsDirectory: File by lazy { FrameLogger.logsDirectory(appContext.filesDir) }

    val health: HealthGateway by lazy { HealthGateway(appContext, settings) }

    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(appContext) }

    /** Audio cues for one engine (each engine owns its focus request, see [AudioFeedback]). */
    fun audioFeedback(): AudioFeedback = AudioFeedback(appContext)

    val history: WorkoutHistory by lazy {
        WorkoutHistory(
            store = historyStore,
            exporter = health,
            syncToHealth = { settings.syncToHealth },
            refreshReadiness = { readiness.refresh() },
            scope = applicationScope,
        ).also { it.reload() }
    }

    val readiness: ReadinessService by lazy {
        ReadinessService(
            history = { historyStore.load() },
            health = health,
            reminder = reminderScheduler,
            syncToHealth = { settings.syncToHealth },
            remindersEnabled = { settings.remindersEnabled },
        )
    }

    /** A workout on the front camera, bound to [lifecycleOwner]; records a CSV when `recordWorkouts` is on. */
    fun workoutEngine(lifecycleOwner: LifecycleOwner, profile: CalibrationProfile, plan: WorkoutPlan): WorkoutEngine =
        WorkoutEngine(
            profile = profile,
            plan = plan,
            config = config,
            frameSource = CameraFrameSource(appContext, lifecycleOwner, config),
            audio = audioFeedback(),
            frameLoggerFactory = if (settings.recordWorkouts) {
                { label -> FrameLogger(debugLogsDirectory, label) }
            } else {
                null
            },
        )

    /** The calibration flow on the front camera, bound to [lifecycleOwner]. */
    fun calibrationEngine(
        lifecycleOwner: LifecycleOwner,
        exercises: List<Exercise> = Exercise.entries,
    ): CalibrationEngine = CalibrationEngine(
        store = calibrationStore,
        existing = calibrationStore.load(),
        config = config,
        exercises = exercises,
        frameSource = CameraFrameSource(appContext, lifecycleOwner, config),
        audio = audioFeedback(),
    )

    /** A bare frame source for the debug recorder screen (vision + optional pipeline + logger). */
    fun frameSource(lifecycleOwner: LifecycleOwner): CameraFrameSource =
        CameraFrameSource(appContext, lifecycleOwner, config)
}
