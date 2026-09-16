package me.raddatz.cindy.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import me.raddatz.cindy.core.CindyJson
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.ui.calibration.CalibrationScreen
import me.raddatz.cindy.ui.debug.DebugRecorderScreen
import me.raddatz.cindy.ui.history.HistoryScreen
import me.raddatz.cindy.ui.history.WorkoutDetailScreen
import me.raddatz.cindy.ui.onboarding.OnboardingScreen
import me.raddatz.cindy.ui.plan.PlanEditorScreen
import me.raddatz.cindy.ui.result.ResultScreen
import me.raddatz.cindy.ui.settings.RecordingsScreen
import me.raddatz.cindy.ui.settings.SettingsScreen
import me.raddatz.cindy.ui.start.StartScreen
import me.raddatz.cindy.ui.theme.CindyTheme
import me.raddatz.cindy.ui.workout.WorkoutScreen

// Destinations (iOS `Route`)

@Serializable
data object StartRoute

@Serializable
data object CalibrationRoute

@Serializable
data object WorkoutRoute

/** The finished workout travels as JSON, so the unsaved result survives process death. */
@Serializable
data class ResultRoute(val recordJson: String) {
    constructor(record: WorkoutRecord) : this(CindyJson.encodeToString(WorkoutRecord.serializer(), record))

    val record: WorkoutRecord get() = CindyJson.decodeFromString(WorkoutRecord.serializer(), recordJson)
}

@Serializable
data object HistoryRoute

@Serializable
data class WorkoutDetailRoute(val recordId: String)

@Serializable
data object PlanRoute

@Serializable
data object SettingsRoute

@Serializable
data object RecordingsRoute

@Serializable
data object DebugRoute

/** "How Cindy works" from the settings: the intro without the calibration hand-off. */
@Serializable
data object IntroRoute

/**
 * The root of the UI (iOS `RootView`): one navigation stack from the start screen, with the
 * first-run intro in front of it until it has been seen.
 */
@Composable
fun CindyApp(model: AppModel) {
    val settings by model.settings.collectAsStateWithLifecycle()
    CindyTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val navController = rememberNavController()
            var calibrateAfterIntro by rememberSaveable { mutableStateOf(false) }
            if (!settings.hasSeenIntro) {
                // Driven by the setting rather than shown once, so dismissing the intro and
                // marking it seen stay the same event.
                OnboardingScreen(
                    model = model,
                    onFinish = { model.markIntroSeen() },
                    onCalibrate = {
                        calibrateAfterIntro = true
                        model.markIntroSeen()
                    },
                )
            } else {
                CindyNavHost(model, navController)
                LaunchedEffect(calibrateAfterIntro) {
                    if (calibrateAfterIntro) {
                        calibrateAfterIntro = false
                        navController.navigate(CalibrationRoute)
                    }
                }
            }
        }
    }
}

@Composable
private fun CindyNavHost(model: AppModel, navController: NavHostController) {
    fun back() {
        navController.popBackStack()
    }
    NavHost(navController, startDestination = StartRoute) {
        composable<StartRoute> {
            StartScreen(model = model, onNavigate = { navController.navigate(it) })
        }
        composable<CalibrationRoute> {
            CalibrationScreen(model = model, onClose = ::back)
        }
        composable<WorkoutRoute> {
            WorkoutScreen(
                model = model,
                onFinish = { record ->
                    navController.navigate(ResultRoute(record)) {
                        popUpTo<WorkoutRoute> { inclusive = true }
                    }
                },
                onClose = ::back,
            )
        }
        composable<ResultRoute> { entry ->
            val route = entry.toRoute<ResultRoute>()
            ResultScreen(
                model = model,
                record = route.record,
                onClose = { navController.popBackStack(StartRoute, inclusive = false) },
            )
        }
        composable<HistoryRoute> {
            HistoryScreen(
                model = model,
                onBack = ::back,
                onOpen = { navController.navigate(WorkoutDetailRoute(it.id.toString())) },
            )
        }
        composable<WorkoutDetailRoute> { entry ->
            WorkoutDetailScreen(model = model, recordId = entry.toRoute<WorkoutDetailRoute>().recordId, onBack = ::back)
        }
        composable<PlanRoute> {
            PlanEditorScreen(model = model, onBack = ::back)
        }
        composable<SettingsRoute> {
            SettingsScreen(
                model = model,
                onBack = ::back,
                onShowIntro = { navController.navigate(IntroRoute) },
                onShowRecordings = { navController.navigate(RecordingsRoute) },
            )
        }
        composable<RecordingsRoute> {
            RecordingsScreen(model = model, onBack = ::back)
        }
        composable<DebugRoute> {
            DebugRecorderScreen(model = model, onBack = ::back)
        }
        composable<IntroRoute> {
            OnboardingScreen(model = model, onFinish = ::back, onCalibrate = null)
        }
    }
}
