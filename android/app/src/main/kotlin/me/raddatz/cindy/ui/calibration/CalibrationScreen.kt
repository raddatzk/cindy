package me.raddatz.cindy.ui.calibration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FaceRetouchingOff
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SportsGymnastics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.raddatz.cindy.R
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.calibration.CalibrationStep
import me.raddatz.cindy.camera.CameraPreview
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.calibration.ExerciseCalibration
import me.raddatz.cindy.core.signal.RepDirection
import me.raddatz.cindy.ui.components.BigNumber
import me.raddatz.cindy.ui.components.BrandButton
import me.raddatz.cindy.ui.components.CameraPermissionExplanation
import me.raddatz.cindy.ui.components.IconBullet
import me.raddatz.cindy.ui.components.KeepScreenOn
import me.raddatz.cindy.ui.components.SecondaryButton
import me.raddatz.cindy.ui.components.openAppSettings
import me.raddatz.cindy.ui.components.rememberCameraPermission
import me.raddatz.cindy.ui.demo.ExerciseDemoButton
import me.raddatz.cindy.ui.text.FormattedNumber
import me.raddatz.cindy.ui.text.UiText
import me.raddatz.cindy.ui.text.calibrationInstructionRes
import me.raddatz.cindy.ui.text.singularName
import me.raddatz.cindy.ui.text.singularNameRes
import me.raddatz.cindy.ui.text.text
import me.raddatz.cindy.ui.theme.CindyTheme
import me.raddatz.cindy.workout.LiveSignal
import me.raddatz.cindy.workout.message
import java.util.Locale

/** The calibration flow (iOS `CalibrationView`): one rep of each exercise, with a demo per step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(model: AppModel, onClose: () -> Unit) {
    val viewModel: CalibrationViewModel = viewModel(factory = CalibrationViewModel.factory(model))
    val engine = viewModel.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val live by engine.live.collectAsStateWithLifecycle()
    val keepScreenOn by engine.keepScreenOn.collectAsStateWithLifecycle()
    KeepScreenOn(keepScreenOn)

    val permission = rememberCameraPermission(onGranted = viewModel::begin)

    fun cancel() {
        engine.cancel()
        onClose()
    }
    BackHandler(onBack = ::cancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calibration_title)) },
                navigationIcon = {
                    TextButton(onClick = ::cancel) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        },
    ) { padding ->
        // At the largest text sizes a step outgrows the screen; it scrolls then, and the spacers
        // pin the step's button to the bottom everywhere else.
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (val step = state.step) {
                    CalibrationStep.Intro -> Intro(
                        exercises = state.exercises,
                        permissionDenied = permission.wasDenied && !permission.isGranted,
                        permissionContent = { CameraPermissionExplanation(permission) },
                        onContinue = { if (permission.isGranted) viewModel.begin() else permission.request() },
                    )
                    is CalibrationStep.Ready -> {
                        StepHeader(step.exercise, state.stepNumber, state.stepCount)
                        CameraPreview(
                            engine.frameSource,
                            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        Text(
                            stringResource(step.exercise.calibrationInstructionRes),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        ExerciseDemoButton(step.exercise)
                        SubjectIndicator(state.trackedSource, live)
                        Spacer(Modifier.weight(1f))
                        BrandButton(
                            stringResource(R.string.calibration_start, state.stepNumber, state.stepCount),
                            onClick = engine::startExercise,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is CalibrationStep.Countdown -> {
                        StepHeader(step.exercise, state.stepNumber, state.stepCount)
                        Spacer(Modifier.weight(1f))
                        BigNumber(step.remaining)
                        Text(
                            stringResource(R.string.calibration_hold_start),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    is CalibrationStep.Capturing -> {
                        StepHeader(step.exercise, state.stepNumber, state.stepCount)
                        Text(
                            if (step.exercise.isHold) {
                                stringResource(R.string.calibration_now_plank)
                            } else {
                                stringResource(R.string.calibration_now_one, stringResource(step.exercise.singularNameRes))
                            },
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        LinearProgressIndicator(
                            progress = { state.captureProgress.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            color = CindyTheme.colors.brand,
                        )
                        SubjectIndicator(state.trackedSource, live)
                        Text(
                            "${stringResource(R.string.common_signal)}: ${String.format(Locale.ROOT, "%.4f", live.value ?: 0f)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    is CalibrationStep.Succeeded -> {
                        StepHeader(step.exercise, state.stepNumber, state.stepCount)
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = CindyTheme.colors.success,
                            modifier = Modifier.size(80.dp),
                        )
                        Text(
                            stringResource(R.string.calibration_detected),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        CalibrationDetails(step.calibration)
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SecondaryButton(stringResource(R.string.calibration_repeat), onClick = engine::retry)
                            BrandButton(
                                stringResource(
                                    if (state.stepNumber == state.stepCount) R.string.common_done else R.string.common_continue,
                                ),
                                onClick = engine::continueToNext,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    is CalibrationStep.Failed -> {
                        StepHeader(step.exercise, state.stepNumber, state.stepCount)
                        Icon(
                            Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = CindyTheme.colors.danger,
                            modifier = Modifier.size(80.dp),
                        )
                        Text(
                            stringResource(R.string.calibration_not_detected),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            step.failure.text.text(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        BrandButton(
                            stringResource(R.string.calibration_repeat),
                            onClick = engine::retry,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CalibrationStep.Done -> {
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Filled.Verified,
                            contentDescription = null,
                            tint = CindyTheme.colors.success,
                            modifier = Modifier.size(80.dp),
                        )
                        Text(
                            stringResource(R.string.calibration_saved),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        BrandButton(
                            stringResource(R.string.calibration_back_to_start),
                            onClick = {
                                model.reload()
                                onClose()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is CalibrationStep.Error -> {
                        val context = LocalContext.current
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(60.dp))
                        Text(step.problem.message(context), textAlign = TextAlign.Center)
                        TextButton(onClick = { context.openAppSettings() }) {
                            Text(stringResource(R.string.common_open_settings))
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Intro(
    exercises: List<Exercise>,
    permissionDenied: Boolean,
    permissionContent: @Composable () -> Unit,
    onContinue: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.calibration_how_it_works),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        IconBullet(Icons.Outlined.Smartphone, stringResource(R.string.common_phone_under_bar))
        IconBullet(Icons.Outlined.Checkroom, stringResource(R.string.calibration_intro_clothes))
        IconBullet(
            Icons.Outlined.SportsGymnastics,
            stringResource(
                R.string.calibration_intro_reps,
                UiText.NaturalList(exercises.map { it.singularName }).text(),
            ),
        )
        IconBullet(Icons.Outlined.Security, stringResource(R.string.calibration_intro_privacy))
    }
    Spacer(Modifier.weight(1f))
    if (permissionDenied) {
        permissionContent()
    } else {
        BrandButton(stringResource(R.string.common_continue), onClick = onContinue, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StepHeader(exercise: Exercise, stepNumber: Int, stepCount: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.calibration_step, stepNumber, stepCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(exercise.singularNameRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SubjectIndicator(source: SignalSource, live: LiveSignal) {
    val detected = live.subjectDetected
    val isFace = source == SignalSource.FACE
    val color = if (detected) CindyTheme.colors.success else CindyTheme.colors.brand
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            when {
                isFace && detected -> Icons.Outlined.Face
                isFace -> Icons.Outlined.FaceRetouchingOff
                detected -> Icons.Filled.Accessibility
                else -> Icons.Outlined.PersonOff
            },
            contentDescription = null,
            tint = color,
        )
        Text(
            stringResource(
                when {
                    isFace && detected -> R.string.calibration_face_detected
                    isFace -> R.string.calibration_no_face_in_frame
                    detected -> R.string.calibration_person_detected
                    else -> R.string.calibration_no_person_in_frame
                },
            ),
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CalibrationDetails(calibration: ExerciseCalibration) {
    val rows = listOf(
        stringResource(R.string.common_duration) to UiText.Res(
            R.string.calibration_seconds,
            FormattedNumber.Decimal(calibration.repDuration, 1),
        ).text(),
        stringResource(R.string.common_signal) to
            String.format(Locale.ROOT, "%.4f – %.4f", calibration.minValue, calibration.maxValue),
        stringResource(R.string.calibration_thresholds) to
            String.format(Locale.ROOT, "%.4f / %.4f", calibration.low, calibration.high),
        stringResource(R.string.calibration_direction) to stringResource(
            if (calibration.direction == RepDirection.PEAK) R.string.calibration_direction_up else R.string.calibration_direction_down,
        ),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((label, value) in rows) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
