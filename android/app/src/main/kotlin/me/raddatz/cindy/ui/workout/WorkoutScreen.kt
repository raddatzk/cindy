package me.raddatz.cindy.ui.workout

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FaceRetouchingOff
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.raddatz.cindy.R
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.camera.CameraPreview
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.demo.demo
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.core.workout.WorkoutPhase
import me.raddatz.cindy.ui.components.BigNumber
import me.raddatz.cindy.ui.components.BrandButton
import me.raddatz.cindy.ui.components.CameraPermissionExplanation
import me.raddatz.cindy.ui.components.FittingText
import me.raddatz.cindy.ui.components.KeepScreenOn
import me.raddatz.cindy.ui.components.SecondaryButton
import me.raddatz.cindy.ui.components.rememberCameraPermission
import me.raddatz.cindy.ui.components.rememberReduceMotion
import me.raddatz.cindy.ui.demo.ExerciseDemoView
import me.raddatz.cindy.ui.text.Formats
import me.raddatz.cindy.ui.text.pluralNameRes
import me.raddatz.cindy.ui.text.textRes
import me.raddatz.cindy.ui.theme.CindyTheme
import me.raddatz.cindy.workout.LiveSignal
import me.raddatz.cindy.workout.WorkoutEngine
import me.raddatz.cindy.workout.WorkoutState
import me.raddatz.cindy.workout.cameraInterruptionMessage
import me.raddatz.cindy.workout.message

/** The workout (iOS `WorkoutView`): timer, reps, round, controls, and the pause and error overlays. */
@Composable
fun WorkoutScreen(model: AppModel, onFinish: (WorkoutRecord) -> Unit, onClose: () -> Unit) {
    val viewModel: WorkoutViewModel = viewModel(factory = WorkoutViewModel.factory(model))
    val engine = viewModel.engine
    if (engine == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val state by engine.state.collectAsStateWithLifecycle()
    val keepScreenOn by engine.keepScreenOn.collectAsStateWithLifecycle()
    KeepScreenOn(keepScreenOn)

    val permission = rememberCameraPermission(onGranted = viewModel::start)
    LaunchedEffect(permission.isGranted) {
        if (permission.isGranted) viewModel.start() else if (!permission.wasDenied) permission.request()
    }
    LaunchedEffect(state.result) { state.result?.let(onFinish) }

    var confirmAbort by rememberSaveable { mutableStateOf(false) }
    BackHandler {
        if (state.phase == WorkoutPhase.IDLE || state.error != null) onClose() else confirmAbort = true
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!permission.isGranted) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                if (permission.wasDenied) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CameraPermissionExplanation(permission)
                        TextButton(onClick = onClose) { Text(stringResource(R.string.common_back)) }
                    }
                }
            }
            return@Surface
        }
        WorkoutContent(model, engine, state, onRequestAbort = { confirmAbort = true }, onClose = onClose)
    }

    if (confirmAbort) {
        AlertDialog(
            onDismissRequest = { confirmAbort = false },
            title = { Text(stringResource(R.string.workout_stop_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAbort = false
                    engine.abort()
                }) {
                    Text(stringResource(R.string.workout_stop_confirm), color = CindyTheme.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAbort = false }) { Text(stringResource(R.string.workout_keep_going)) }
            },
        )
    }
}

@Composable
private fun WorkoutContent(
    model: AppModel,
    engine: WorkoutEngine,
    state: WorkoutState,
    onRequestAbort: () -> Unit,
    onClose: () -> Unit,
) {
    val live by engine.live.collectAsStateWithLifecycle()
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var editingPlan by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val overlayVisible = state.phase == WorkoutPhase.COUNTDOWN || state.isPaused || state.error != null

    Box(Modifier.fillMaxSize()) {
        // The big numbers shrink to fit on their own; the controls cannot. At the largest text
        // sizes the screen scrolls rather than pushing Pause and Stop off the bottom.
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                // Hidden from TalkBack while an overlay covers it.
                .then(if (overlayVisible) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopBar(state, showPreview, onTogglePreview = { showPreview = !showPreview })
                Spacer(Modifier.weight(1f))
                Timer(state)
                ExerciseBlock(state, reduceMotion)
                Spacer(Modifier.weight(1f))
                StatusLine(state, live)
                if (showPreview) {
                    CameraPreview(engine.frameSource, Modifier.size(120.dp, 160.dp).clip(RoundedCornerShape(12.dp)))
                }
                Controls(engine, state, onRequestAbort)
            }
        }

        Overlay(visible = state.phase == WorkoutPhase.COUNTDOWN, reduceMotion, alpha = 0.92f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.workout_get_ready),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BigNumber(state.countdownValue, maxSize = 180.sp)
                Text(
                    stringResource(R.string.workout_starting_with, stringResource(state.plan.first.pluralNameRes)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Overlay(visible = state.isPaused, reduceMotion, alpha = 0.97f) {
            PauseContent(engine, state, onEditPlan = { editingPlan = true }, onRequestAbort = onRequestAbort)
        }

        val error = state.error
        Overlay(visible = error != null, reduceMotion, alpha = 0.95f) {
            val context = LocalContext.current
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(60.dp))
                Text(error?.message(context).orEmpty(), textAlign = TextAlign.Center)
                BrandButton(stringResource(R.string.common_back), onClick = onClose)
            }
        }
    }

    if (editingPlan) {
        WorkoutPlanSheet(
            engine = engine,
            plan = state.plan,
            onApply = { plan ->
                // Kept for the next workout as well: it is what turned out to be doable.
                if (engine.updatePlan(plan)) model.setPlan(plan)
            },
            onDismiss = { editingPlan = false },
        )
    }
}

@Composable
private fun Overlay(visible: Boolean, reduceMotion: Boolean, alpha: Float, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = alpha))
                // Swallows touches meant for the controls underneath.
                .pointerInput(Unit) { detectTapGestures {} }
                .safeDrawingPadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun TopBar(state: WorkoutState, showPreview: Boolean, onTogglePreview: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.workout_round, state.currentRound),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (state.isLogging) {
            Icon(
                Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = CindyTheme.colors.danger,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.workout_rec),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = CindyTheme.colors.danger,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            )
        }
        Text(
            state.score.notation,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onTogglePreview, modifier = Modifier.padding(start = 8.dp)) {
            Icon(
                if (showPreview) Icons.Filled.Videocam else Icons.Outlined.VideocamOff,
                contentDescription = stringResource(
                    if (showPreview) R.string.workout_hide_preview else R.string.workout_show_preview,
                ),
            )
        }
    }
}

@Composable
private fun Timer(state: WorkoutState) {
    val isLastMinute = state.remaining <= 60
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        FittingText(
            Formats.countdownClock(state.remaining),
            maxSize = 96.sp,
            minScale = 0.5f,
            weight = FontWeight.Bold,
            color = if (isLastMinute) CindyTheme.colors.danger else MaterialTheme.colorScheme.onBackground,
        )
        // Red alone says nothing to someone who cannot tell it apart.
        if (isLastMinute) {
            Text(
                stringResource(R.string.workout_last_minute),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ExerciseBlock(state: WorkoutState, reduceMotion: Boolean) {
    val exercise = state.exercise
    val target = state.plan.target(exercise)
    val name = stringResource(exercise.pluralNameRes)
    val current = if (exercise.isHold) (state.heldSeconds ?: 0.0).toInt() else state.repCount
    val progress = stringResource(R.string.workout_progress, current, target)
    val now = stringResource(R.string.workout_now, name)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FittingText(name, maxSize = 40.sp, minScale = 0.5f, weight = FontWeight.SemiBold, color = CindyTheme.colors.brand)
        // "7 / 10" read out as "seven slash ten" is noise; say what it is. Reps and exercise
        // changes only beep, so TalkBack users hear them through the live region.
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = if (state.phase == WorkoutPhase.TRANSITION) now else name
                stateDescription = progress
                if (!exercise.isHold) liveRegion = LiveRegionMode.Polite
            },
        ) {
            FittingText(current.toString(), maxSize = 140.sp, modifier = Modifier.weight(1f, fill = false))
            FittingText(
                "/ $target" + if (exercise.isHold) " " + stringResource(R.string.unit_seconds) else "",
                maxSize = 40.sp,
                minScale = 0.5f,
                weight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
        if (exercise.isHold) {
            LinearProgressIndicator(
                progress = { (minOf(state.heldSeconds ?: 0.0, target.toDouble()) / target).toFloat() },
                modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth(),
                color = CindyTheme.colors.brand,
            )
        }
        AnimatedVisibility(
            visible = state.phase == WorkoutPhase.TRANSITION,
            enter = if (reduceMotion) fadeIn() else scaleIn() + fadeIn(),
            exit = fadeOut(),
        ) {
            // Big visual cue while waiting for the next exercise's start position.
            Column(
                Modifier
                    .background(CindyTheme.colors.brand, CircleShape)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.workout_now_do),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = CindyTheme.colors.onBrand,
                )
                Text(
                    name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = CindyTheme.colors.onBrand,
                )
            }
        }
        if (state.phase != WorkoutPhase.TRANSITION && state.plan.sets.size > 1) {
            Text(
                stringResource(R.string.workout_up_next, stringResource(state.nextExercise.pluralNameRes)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The color sits on the icons only; the icon and the words already change with the state. */
@Composable
private fun StatusLine(state: WorkoutState, live: LiveSignal) {
    val detected = live.subjectDetected
    val isFace = state.trackedSource == SignalSource.FACE
    val status = when (state.phase) {
        WorkoutPhase.TRANSITION -> stringResource(R.string.workout_waiting_start)
        WorkoutPhase.ACTIVE -> stringResource(
            when {
                !state.isSignalArmed -> R.string.workout_signal_lost
                state.exercise.isHold -> R.string.workout_hold
                else -> R.string.workout_counting
            },
        )
        WorkoutPhase.PAUSED -> stringResource(R.string.workout_paused)
        else -> ""
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                when {
                    isFace && detected -> Icons.Outlined.Face
                    isFace -> Icons.Outlined.FaceRetouchingOff
                    detected -> Icons.Filled.Accessibility
                    else -> Icons.Outlined.PersonOff
                },
                contentDescription = null,
                tint = if (detected) CindyTheme.colors.success else CindyTheme.colors.brand,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(
                    when {
                        isFace && detected -> R.string.workout_face
                        isFace -> R.string.workout_no_face
                        detected -> R.string.workout_person
                        else -> R.string.workout_no_person
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                if (state.isSignalArmed) Icons.Filled.MonitorHeart else Icons.Filled.HourglassEmpty,
                contentDescription = null,
                tint = if (state.isSignalArmed) CindyTheme.colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Controls(engine: WorkoutEngine, state: WorkoutState, onRequestAbort: () -> Unit) {
    val finished = state.phase == WorkoutPhase.FINISHED
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // "−1" is a typographic minus, which TalkBack does not read as anything sayable.
            AdjustButton("−1", stringResource(R.string.workout_one_rep_less), !finished, Modifier.weight(1f)) {
                engine.adjust(-1)
            }
            AdjustButton("+1", stringResource(R.string.workout_one_rep_more), !finished, Modifier.weight(1f)) {
                engine.adjust(1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BrandButton(
                text = stringResource(if (state.isPaused) R.string.workout_resume else R.string.workout_pause),
                icon = if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                enabled = !finished && state.phase != WorkoutPhase.COUNTDOWN && !state.cameraInterrupted,
                onClick = { if (state.isPaused) engine.resume() else engine.pause() },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = stringResource(R.string.workout_stop),
                icon = Icons.Filled.Close,
                enabled = !finished,
                destructive = true,
                onClick = onRequestAbort,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AdjustButton(label: String, description: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp).semantics { contentDescription = description },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CindyTheme.colors.brand),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * A pause is when someone stops to wonder whether they are doing it right, so the pause screen
 * answers that: the current exercise, moving. It is also where the plan can be changed.
 */
@Composable
private fun PauseContent(engine: WorkoutEngine, state: WorkoutState, onEditPlan: () -> Unit, onRequestAbort: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.workout_paused),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val countdown = state.resumeCountdown
        if (countdown != null) {
            // Beeped as well: the phone is back on the floor by now and nobody is looking at this.
            Text(
                stringResource(R.string.workout_continuing_in, countdown),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CindyTheme.colors.brand,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else if (state.cameraInterrupted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.NoPhotography,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    cameraInterruptionMessage(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Text(
            stringResource(state.exercise.pluralNameRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = CindyTheme.colors.brand,
        )
        ExerciseDemoView(state.exercise, Modifier.fillMaxWidth().heightIn(max = 240.dp))
        for (cue in state.exercise.demo.cues.take(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(cue.textRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SecondaryButton(
            text = stringResource(R.string.plan_edit_title),
            icon = Icons.Filled.Tune,
            onClick = onEditPlan,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(
                text = stringResource(R.string.workout_stop),
                icon = Icons.Filled.Close,
                destructive = true,
                onClick = onRequestAbort,
                modifier = Modifier.weight(1f),
            )
            BrandButton(
                text = stringResource(R.string.workout_resume),
                icon = Icons.Filled.PlayArrow,
                // Resuming without frames would count nothing.
                enabled = !state.cameraInterrupted,
                onClick = engine::resume,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
