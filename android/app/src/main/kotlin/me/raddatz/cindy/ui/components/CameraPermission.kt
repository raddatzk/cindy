package me.raddatz.cindy.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import me.raddatz.cindy.R
import me.raddatz.cindy.ui.theme.CindyTheme

/** The camera runtime permission as the calibration, workout and debug screens see it. */
@Stable
class CameraPermissionState internal constructor(
    granted: Boolean,
    private val launch: () -> Unit,
) {
    var isGranted by mutableStateOf(granted)
        internal set

    /** Refused with "don't ask again" (or by policy): only the system settings can grant it now. */
    var isPermanentlyDenied by mutableStateOf(false)
        internal set

    /** Asked at least once and refused. */
    var wasDenied by mutableStateOf(false)
        internal set

    fun request() = launch()
}

/**
 * Remembers the camera permission and re-reads it on every resume, so granting it in the system
 * settings takes effect when the user comes back. [onGranted] runs after a request was accepted.
 */
@Composable
fun rememberCameraPermission(onGranted: () -> Unit = {}): CameraPermissionState {
    val context = LocalContext.current
    var requested by rememberSaveable { mutableStateOf(false) }
    lateinit var state: CameraPermissionState
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state.isGranted = granted
        state.wasDenied = !granted
        state.isPermanentlyDenied = !granted && !context.shouldShowCameraRationale()
        if (granted) onGranted()
    }
    state = remember {
        CameraPermissionState(context.hasCameraPermission()) {
            requested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    OnResume {
        val granted = context.hasCameraPermission()
        state.isGranted = granted
        if (granted) {
            state.wasDenied = false
            state.isPermanentlyDenied = false
        } else if (requested) {
            state.wasDenied = true
            state.isPermanentlyDenied = !context.shouldShowCameraRationale()
        }
    }
    return state
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.shouldShowCameraRationale(): Boolean {
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Explains why Cindy needs the camera before the system asks (iOS shows the same text as the
 * usage description in its prompt), and leads to the system settings once it was refused for good.
 */
@Composable
fun CameraPermissionExplanation(state: CameraPermissionState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Filled.PhotoCamera,
            contentDescription = null,
            modifier = Modifier.size(60.dp),
            tint = CindyTheme.colors.brand,
        )
        Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(if (state.wasDenied) R.string.camera_error_not_authorized else R.string.camera_permission_body),
            textAlign = TextAlign.Center,
        )
        if (state.isPermanentlyDenied) {
            BrandButton(stringResource(R.string.common_open_settings), onClick = { context.openAppSettings() })
        } else {
            BrandButton(stringResource(R.string.camera_permission_allow), onClick = { state.request() })
        }
    }
}
