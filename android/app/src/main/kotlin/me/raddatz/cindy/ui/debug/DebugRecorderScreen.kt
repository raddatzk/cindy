package me.raddatz.cindy.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.raddatz.cindy.R
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.camera.CameraPreview
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.SignalSource
import me.raddatz.cindy.core.signal.RepDirection
import me.raddatz.cindy.ui.components.BackTopBar
import me.raddatz.cindy.ui.components.CameraPermissionExplanation
import me.raddatz.cindy.ui.components.KeepScreenOn
import me.raddatz.cindy.ui.components.SignalSparkline
import me.raddatz.cindy.ui.components.rememberCameraPermission
import me.raddatz.cindy.ui.settings.shareCsv
import me.raddatz.cindy.ui.text.nameRes
import me.raddatz.cindy.ui.text.pluralNameRes
import me.raddatz.cindy.ui.theme.CindyTheme
import java.util.Locale

/**
 * Hidden developer mode (iOS `DebugRecorderView`): live signal values, a sparkline with the
 * thresholds and CSV recording of every frame. Reached by a long press on the title; not
 * localized, like on iOS.
 */
@Composable
fun DebugRecorderScreen(model: AppModel, onBack: () -> Unit) {
    val viewModel: DebugViewModel = viewModel(factory = DebugViewModel.factory(model))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permission = rememberCameraPermission(onGranted = viewModel::start)
    LaunchedEffect(permission.isGranted) {
        if (permission.isGranted) viewModel.start() else if (!permission.wasDenied) permission.request()
    }
    KeepScreenOn(state.running)
    val context = LocalContext.current

    Scaffold(topBar = { BackTopBar(stringResource(R.string.debug_title), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!permission.isGranted) {
                if (permission.wasDenied) CameraPermissionExplanation(permission)
                return@Column
            }
            state.errorMessage?.let { Text(it, color = CindyTheme.colors.danger) }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Exercise.entries.forEachIndexed { index, exercise ->
                    SegmentedButton(
                        selected = state.exercise == exercise,
                        onClick = { viewModel.setExercise(exercise) },
                        shape = SegmentedButtonDefaults.itemShape(index, Exercise.entries.size),
                        icon = {},
                    ) { Text(stringResource(exercise.pluralNameRes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SignalSource.entries.forEachIndexed { index, source ->
                    SegmentedButton(
                        selected = state.source == source,
                        onClick = { viewModel.setSource(source) },
                        shape = SegmentedButtonDefaults.itemShape(index, SignalSource.entries.size),
                        icon = {},
                    ) { Text(stringResource(source.nameRes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(state.bodyPose, role = Role.Switch, onValueChange = viewModel::setBodyPose)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.debug_body_pose), modifier = Modifier.weight(1f))
                Switch(checked = state.bodyPose, onCheckedChange = null)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CameraPreview(viewModel.frameSource, Modifier.size(120.dp, 160.dp).clip(RoundedCornerShape(12.dp)))
                Values(state, Modifier.weight(1f))
            }
            SignalSparkline(state.history, state.activeThresholds, Modifier.fillMaxWidth().height(140.dp))
            ThresholdInfo(state)

            val recordColor = if (state.isRecording) CindyTheme.colors.danger else CindyTheme.colors.brand
            Button(
                onClick = { if (state.isRecording) viewModel.stopRecording() else viewModel.startRecording() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = recordColor, contentColor = CindyTheme.colors.onBrand),
            ) {
                Icon(
                    if (state.isRecording) Icons.Filled.StopCircle else Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(if (state.isRecording) R.string.debug_stop_recording else R.string.debug_start_recording))
            }
            if (state.isRecording) {
                Text(
                    stringResource(R.string.debug_frames, state.recordedRows),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = viewModel::resetPipeline) { Text(stringResource(R.string.debug_reset)) }

            Text(stringResource(R.string.debug_recordings), style = MaterialTheme.typography.titleMedium)
            if (state.logFiles.isEmpty()) {
                Text(
                    stringResource(R.string.debug_no_recordings),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (file in state.logFiles) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.name,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { context.shareCsv(file) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.recordings_share))
                    }
                    IconButton(onClick = { viewModel.deleteLog(file) }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.recordings_delete),
                            tint = CindyTheme.colors.danger,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Values(state: DebugState, modifier: Modifier) {
    val output = state.latest
    val observation = state.latestObservation
    val face = observation?.face
    val pose = observation?.pose
    val rows = buildList {
        add("raw" to fmt(output?.raw))
        add("ema" to fmt(output?.smoothed))
        add("conf" to fmt(output?.confidence))
        add(
            "face" to (face?.let { String.format(Locale.ROOT, "%.3f × %.3f", it.boundingBox.width, it.boundingBox.height) } ?: "–"),
        )
        add("y" to fmt(face?.centerY))
        add("orient" to (observation?.orientation?.toString() ?: "–"))
        add("phase" to (output?.phase?.rawValue ?: "–"))
        add("armed" to (output?.let { stringResource(if (it.isArmed) R.string.debug_yes else R.string.debug_no) } ?: "–"))
        add("reps" to state.repCount.toString())
        if (pose != null) {
            add("nose y" to fmt(pose.noseY))
            add("shoulder y" to fmt(pose.shoulderY))
            add("hip y" to fmt(pose.hipY))
            add("shoulder w" to fmt(pose.shoulderWidth))
        }
        observation?.metrics?.let { add("luma" to fmt(it.lumaMean)) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((label, value) in rows) {
            Row {
                Text(
                    label,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ThresholdInfo(state: DebugState) {
    val thresholds = state.activeThresholds
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val style = MaterialTheme.typography.bodySmall
        Text(String.format(Locale.ROOT, "low %.4f", thresholds.low), fontFamily = FontFamily.Monospace, style = style, color = CindyTheme.colors.info)
        Text(String.format(Locale.ROOT, "high %.4f", thresholds.high), fontFamily = FontFamily.Monospace, style = style, color = CindyTheme.colors.danger)
        Text(if (thresholds.direction == RepDirection.PEAK) "peak" else "trough", fontFamily = FontFamily.Monospace, style = style)
        Text(
            stringResource(if (state.usesCalibration) R.string.debug_calibrated else R.string.debug_hardcoded) +
                if (thresholds.isRelative) stringResource(R.string.debug_relative) else "",
            fontFamily = FontFamily.Monospace,
            style = style,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun fmt(value: Float?): String = value?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "–"
