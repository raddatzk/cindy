package me.raddatz.cindy.ui.start

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withTimeoutOrNull
import me.raddatz.cindy.R
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.app.CalibrationRoute
import me.raddatz.cindy.app.DebugRoute
import me.raddatz.cindy.app.HistoryRoute
import me.raddatz.cindy.app.PlanRoute
import me.raddatz.cindy.app.SettingsRoute
import me.raddatz.cindy.app.WorkoutRoute
import me.raddatz.cindy.ui.components.BrandButton
import me.raddatz.cindy.ui.components.EqualHeightColumn
import me.raddatz.cindy.ui.components.OnResume
import me.raddatz.cindy.ui.readiness.ReadinessCard
import me.raddatz.cindy.ui.text.Formats
import me.raddatz.cindy.ui.text.UiText
import me.raddatz.cindy.ui.text.appLocale
import me.raddatz.cindy.ui.text.displayName
import me.raddatz.cindy.ui.text.summaryText
import me.raddatz.cindy.ui.text.text
import me.raddatz.cindy.ui.theme.CindyTheme

/** The start screen (iOS `StartView`): the plan, readiness and the ways into everything else. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(model: AppModel, onNavigate: (Any) -> Unit) {
    val plan by model.plan.collectAsStateWithLifecycle()
    val calibration by model.calibration.collectAsStateWithLifecycle()
    val isCalibrated by model.isCalibrated.collectAsStateWithLifecycle()
    val readiness by model.readiness.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val locale = appLocale

    OnResume { model.reload() }
    LaunchedEffect(Unit) { model.refreshReadiness() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(onClick = { onNavigate(SettingsRoute) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Header
            Column(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.app_name),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .semantics { heading() }
                        .pointerInput(Unit) {
                            // Hidden debug screen: a long press of a second and a half.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val released = withTimeoutOrNull(1_500) { waitForUpOrCancellation() }
                                if (released == null) onNavigate(DebugRoute)
                            }
                        },
                )
                Text(
                    stringResource(R.string.plan_amrap_minutes, plan.durationMinutes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    plan.summaryText.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            readiness.readiness?.let { ReadinessCard(it, readiness.nextSession) }

            // Actions
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandButton(
                        text = stringResource(R.string.start_workout),
                        onClick = { onNavigate(WorkoutRoute) },
                        enabled = isCalibrated,
                        icon = Icons.Filled.PlayArrow,
                        modifier = Modifier.weight(1f),
                    )
                    // Editing the plan sits next to starting it; the header already says what the plan is.
                    OutlinedButton(
                        onClick = { onNavigate(PlanRoute) },
                        modifier = Modifier.heightIn(min = 52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CindyTheme.colors.brand),
                    ) {
                        Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.plan_edit_title))
                    }
                }

                val calibrationNote = when {
                    calibration == null -> stringResource(R.string.start_not_calibrated)
                    calibration!!.missingExercises(plan).isNotEmpty() -> stringResource(
                        R.string.start_missing,
                        UiText.NaturalList(calibration!!.missingExercises(plan).map { it.displayName }).text(),
                    )
                    else -> Formats.dateTime(calibration!!.createdAt, locale)
                }
                val lastCompleted = history.firstOrNull { it.completed }
                // The two rows read as a pair, so they share the taller height.
                EqualHeightColumn(spacing = 12.dp) {
                    SecondaryAction(
                        title = stringResource(if (isCalibrated) R.string.start_recalibrate else R.string.start_calibrate),
                        icon = Icons.Filled.GpsFixed,
                        note = calibrationNote,
                        needsAttention = !isCalibrated,
                        onClick = { onNavigate(CalibrationRoute) },
                    )
                    SecondaryAction(
                        title = stringResource(R.string.history_title),
                        icon = Icons.Filled.History,
                        note = lastCompleted?.let { stringResource(R.string.start_last_score, it.score.notation) },
                        onClick = { onNavigate(HistoryRoute) },
                    )
                }
            }
        }
    }
}

/** Icon and title on one line, an optional status note underneath (iOS `secondaryLabel`). */
@Composable
private fun SecondaryAction(
    title: String,
    icon: ImageVector,
    note: String?,
    onClick: () -> Unit,
    needsAttention: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = 56.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CindyTheme.colors.brand),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (needsAttention) CindyTheme.colors.brand else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (needsAttention) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = CindyTheme.colors.brand)
            } else {
                Spacer(Modifier.size(0.dp))
            }
        }
    }
}
