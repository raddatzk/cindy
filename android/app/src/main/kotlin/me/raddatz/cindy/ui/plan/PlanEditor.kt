package me.raddatz.cindy.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.cindy.R
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.ExerciseSet
import me.raddatz.cindy.core.WorkoutPlan
import me.raddatz.cindy.ui.components.BackTopBar
import me.raddatz.cindy.ui.components.SectionFooter
import me.raddatz.cindy.ui.components.SectionHeader
import me.raddatz.cindy.ui.components.Stepper
import me.raddatz.cindy.ui.demo.DemoButtonStyle
import me.raddatz.cindy.ui.demo.ExerciseDemoButton
import me.raddatz.cindy.ui.text.pluralNameRes
import me.raddatz.cindy.ui.text.unitRes
import me.raddatz.cindy.ui.theme.CindyTheme

/** Edit the round from the start screen (iOS `PlanEditorView`). */
@Composable
fun PlanEditorScreen(model: AppModel, onBack: () -> Unit) {
    val plan by model.plan.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.plan_edit_title), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            PlanEditorList(plan = plan, onPlanChange = model::setPlan)
            TextButton(
                onClick = { model.setPlan(WorkoutPlan.cindy) },
                enabled = plan != WorkoutPlan.cindy,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            ) {
                Text(stringResource(R.string.plan_reset))
            }
        }
    }
}

/**
 * The editing list, shared by the start screen's editor and the workout's pause screen (iOS
 * `PlanEditorList`): duration, the exercises of a round with steppers, reorder handles and remove
 * buttons, and the exercises that can still be added.
 *
 * Rows drag by their handle, or anywhere after a long press; TalkBack gets "Move up"/"Move down"
 * actions instead.
 *
 * @param addable exercises on offer to add; during a workout only the calibrated ones
 * @param minimumMinutes shortest duration on offer; during a workout the clock may be past the shorter ones
 */
@Composable
fun PlanEditorList(
    plan: WorkoutPlan,
    onPlanChange: (WorkoutPlan) -> Unit,
    modifier: Modifier = Modifier,
    addable: List<Exercise> = Exercise.entries,
    minimumMinutes: Int = WorkoutPlan.durationChoices.first(),
) {
    val currentPlan by rememberUpdatedState(plan)
    val currentOnPlanChange by rememberUpdatedState(onPlanChange)
    Column(modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.common_duration))
        val maximum = maxOf(minimumMinutes, WorkoutPlan.durationChoices.last())
        val durationLabel = stringResource(R.string.plan_amrap_minutes, plan.durationMinutes)
        Stepper(
            value = plan.durationMinutes,
            range = minimumMinutes..maximum,
            step = 5,
            onValueChange = { onPlanChange(plan.copy(durationMinutes = it)) },
            subject = durationLabel,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text(durationLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }

        SectionHeader(stringResource(R.string.plan_section_exercises))
        var dragged by remember { mutableStateOf<Exercise?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val heights = remember { mutableStateMapOf<Exercise, Int>() }

        fun onDrag(change: PointerInputChange, deltaY: Float) {
            change.consume()
            val exercise = dragged ?: return
            dragOffset += deltaY
            val sets = currentPlan.sets
            val index = sets.indexOfFirst { it.exercise == exercise }
            val step = PlanReorder.step(dragOffset, index, sets.map { heights[it.exercise] ?: 0 }) ?: return
            dragOffset = step.remainingOffset
            currentOnPlanChange(currentPlan.moving(exercise, step.by))
        }

        fun endDrag() {
            dragged = null
            dragOffset = 0f
        }

        plan.sets.forEach { set ->
            key(set.exercise) {
                val isDragged = dragged == set.exercise
                PlanRow(
                    set = set,
                    plan = plan,
                    onPlanChange = onPlanChange,
                    handleGestures = {
                        detectDragGestures(
                            onDragStart = { dragged = set.exercise },
                            onDragEnd = ::endDrag,
                            onDragCancel = ::endDrag,
                            onDrag = { change, amount -> onDrag(change, amount.y) },
                        )
                    },
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                        .then(if (isDragged) Modifier.shadow(6.dp) else Modifier)
                        .onSizeChanged { heights[set.exercise] = it.height }
                        .pointerInput(set.exercise) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragged = set.exercise },
                                onDragEnd = ::endDrag,
                                onDragCancel = ::endDrag,
                                onDrag = { change, amount -> onDrag(change, amount.y) },
                            )
                        },
                )
            }
        }
        SectionFooter(stringResource(R.string.plan_section_exercises_footer))

        val missing = addable.filter { !it.isHold && it !in plan }
        if (missing.isNotEmpty()) {
            SectionHeader(stringResource(R.string.plan_section_not_in_plan))
            for (exercise in missing) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onPlanChange(plan.withEnabled(exercise, true)) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.AddCircleOutline, contentDescription = null, tint = CindyTheme.colors.brand)
                    Text(
                        stringResource(R.string.plan_add_exercise, stringResource(exercise.pluralNameRes)),
                        color = CindyTheme.colors.brand,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }

        PlankSection(plan, onPlanChange)
    }
}

/** The plank is not part of the round: it follows once, after the AMRAP clock has run out. */
@Composable
private fun PlankSection(plan: WorkoutPlan, onPlanChange: (WorkoutPlan) -> Unit) {
    val exercise = Exercise.PLANK
    val name = stringResource(exercise.pluralNameRes)
    SectionHeader(stringResource(R.string.plan_section_after_amrap))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(plan.hasPlank, role = Role.Switch) { onPlanChange(plan.withEnabled(exercise, it)) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = plan.hasPlank, onCheckedChange = null)
    }
    val seconds = plan.plankSeconds
    if (seconds != null) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Stepper(
                value = seconds,
                range = WorkoutPlan.targetRange(exercise),
                step = 5,
                onValueChange = { onPlanChange(plan.withTarget(it, exercise)) },
                subject = name,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    "$seconds ${stringResource(exercise.unitRes)}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            ExerciseDemoButton(exercise, style = DemoButtonStyle.ICON)
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Stepper(
                value = plan.plankSets,
                range = WorkoutPlan.plankSetRange,
                step = 1,
                onValueChange = { onPlanChange(plan.withPlankSets(it)) },
                subject = stringResource(R.string.plan_plank_sets_subject),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(R.string.plan_plank_sets, plan.plankSets),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    SectionFooter(stringResource(R.string.plan_section_after_amrap_footer))
}

@Composable
private fun PlanRow(
    set: ExerciseSet,
    plan: WorkoutPlan,
    onPlanChange: (WorkoutPlan) -> Unit,
    handleGestures: suspend PointerInputScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val exercise = set.exercise
    val name = stringResource(exercise.pluralNameRes)
    val moveUp = stringResource(R.string.plan_move_up)
    val moveDown = stringResource(R.string.plan_move_down)
    val canRemove = plan.sets.size > 1
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                    .semantics {
                        customActions = buildList {
                            if (!plan.isFirst(exercise)) {
                                add(CustomAccessibilityAction(moveUp) { onPlanChange(plan.moving(exercise, -1)); true })
                            }
                            if (!plan.isLast(exercise)) {
                                add(CustomAccessibilityAction(moveDown) { onPlanChange(plan.moving(exercise, 1)); true })
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stepper(
                    value = set.target,
                    range = WorkoutPlan.targetRange(exercise),
                    step = 1,
                    onValueChange = { onPlanChange(plan.withTarget(it, exercise)) },
                    subject = name,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${set.target} ${stringResource(exercise.unitRes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ExerciseDemoButton(exercise, style = DemoButtonStyle.ICON)
                IconButton(onClick = { onPlanChange(plan.withEnabled(exercise, false)) }, enabled = canRemove) {
                    Icon(
                        Icons.Outlined.RemoveCircleOutline,
                        contentDescription = stringResource(R.string.plan_remove_exercise, name),
                        tint = if (canRemove) CindyTheme.colors.danger else MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .pointerInput(exercise) { handleGestures() }
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.size(4.dp))
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
