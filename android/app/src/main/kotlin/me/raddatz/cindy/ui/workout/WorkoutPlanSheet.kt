package me.raddatz.cindy.ui.workout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.raddatz.cindy.R
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.ui.components.SectionFooter
import me.raddatz.cindy.ui.plan.PlanEditorList
import me.raddatz.cindy.workout.WorkoutEngine

/**
 * The plan editor during a pause (iOS `WorkoutPlanSheet`), for when the plan turns out to be too
 * much (or too little) halfway through. Edits a copy and hands it over on "Apply".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutPlanSheet(engine: WorkoutEngine, plan: WorkoutPlan, onApply: (WorkoutPlan) -> Unit, onDismiss: () -> Unit) {
    // The sheet can only be dragged by its handle, so dragging a row reorders instead of closing it.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(plan) }
    val calibrated = remember { engine.calibratedExercises }
    val minimumMinutes = remember { engine.shortestDurationAhead }

    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            then()
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, sheetGesturesEnabled = false) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { close() }) { Text(stringResource(R.string.common_cancel)) }
                Text(
                    stringResource(R.string.plan_edit_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = { close { onApply(draft) } }, enabled = draft != plan) {
                    Text(stringResource(R.string.workout_apply))
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                PlanEditorList(
                    plan = draft,
                    onPlanChange = { draft = it },
                    addable = calibrated,
                    minimumMinutes = minimumMinutes,
                )
                SectionFooter(stringResource(R.string.workout_plan_footer))
            }
        }
    }
}
