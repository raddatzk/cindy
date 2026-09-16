package me.raddatz.cindy.ui.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.raddatz.cindy.R
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.demo.demo
import me.raddatz.cindy.ui.components.rememberReduceMotion
import me.raddatz.cindy.ui.text.appLocale
import me.raddatz.cindy.ui.text.Formats
import me.raddatz.cindy.ui.text.singularNameRes
import me.raddatz.cindy.ui.text.textRes
import me.raddatz.cindy.ui.theme.CindyTheme

/**
 * One exercise as a looping stick figure (iOS `ExerciseDemoView`). With "Remove animations" on,
 * the demo waits to be started and carries a play/pause button instead of looping right away.
 */
@Composable
fun ExerciseDemoView(exercise: Exercise, modifier: Modifier = Modifier, isPaused: Boolean = false) {
    val reduceMotion = rememberReduceMotion()
    var playRequested by rememberSaveable(exercise) { mutableStateOf(false) }
    Box(modifier) {
        StickFigureDemo(
            demo = exercise.demo,
            isPaused = isPaused || (reduceMotion && !playRequested),
            modifier = Modifier.align(Alignment.Center),
        )
        if (reduceMotion) {
            IconButton(
                onClick = { playRequested = !playRequested },
                modifier = Modifier.align(Alignment.BottomEnd).size(56.dp),
            ) {
                Icon(
                    if (playRequested) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                    contentDescription = stringResource(
                        if (playRequested) R.string.demo_pause_movement else R.string.demo_play_movement,
                    ),
                    tint = CindyTheme.colors.brand,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

/** The demo plus the numbered form cues, as a bottom sheet (iOS `ExerciseDemoSheet`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDemoSheet(exercise: Exercise, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val locale = appLocale
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(exercise.singularNameRes),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }) {
                    Text(stringResource(R.string.common_done))
                }
            }
            ExerciseDemoView(
                exercise,
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(CindyTheme.colors.panel, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                exercise.demo.cues.forEachIndexed { index, cue ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(24.dp).background(CindyTheme.colors.brand, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                Formats.integer(index + 1, locale),
                                style = MaterialTheme.typography.labelMedium,
                                color = CindyTheme.colors.onBrand,
                            )
                        }
                        Text(stringResource(cue.textRes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

enum class DemoButtonStyle {
    /** A button with a title. */
    PROMINENT,

    /** A bare icon, for list rows that are already busy. */
    ICON,
}

/** Opens [ExerciseDemoSheet] wherever an exercise is named (iOS `ExerciseDemoButton`). */
@Composable
fun ExerciseDemoButton(exercise: Exercise, modifier: Modifier = Modifier, style: DemoButtonStyle = DemoButtonStyle.PROMINENT) {
    var showing by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.demo_show_exercise_movement, stringResource(exercise.singularNameRes))
    when (style) {
        DemoButtonStyle.PROMINENT -> TextButton(
            onClick = { showing = true },
            modifier = modifier.semantics { contentDescription = description },
        ) {
            Icon(Icons.Filled.FitnessCenter, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.demo_show_movement))
        }
        DemoButtonStyle.ICON -> IconButton(onClick = { showing = true }, modifier = modifier) {
            Icon(
                Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showing) ExerciseDemoSheet(exercise, onDismiss = { showing = false })
}
