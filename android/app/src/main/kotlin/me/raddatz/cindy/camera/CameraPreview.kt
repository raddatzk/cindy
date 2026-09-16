package me.raddatz.cindy.camera

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import me.raddatz.cindy.R

/**
 * Live front-camera preview, filling its bounds and mirrored like a selfie
 * (iOS: `CameraPreviewView` with `.resizeAspectFill`). `PreviewView` mirrors the front camera
 * and follows the portrait display rotation by itself.
 */
@Composable
fun CameraPreview(camera: CameraSession, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView-backed: composes cleanly with overlays and animations.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(camera, view) {
        camera.preview.surfaceProvider = view.surfaceProvider
        onDispose { camera.preview.surfaceProvider = null }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

/** Preview for an engine's frame source; draws nothing for sources without a camera (tests, fakes). */
@Composable
fun CameraPreview(frameSource: FrameSource, modifier: Modifier = Modifier) {
    val camera = (frameSource as? CameraFrameSource)?.camera ?: return
    CameraPreview(camera, modifier)
}

/** User-facing text for a camera failure. */
fun CameraFailure.message(context: Context, detail: String? = null): String = when (this) {
    CameraFailure.NOT_AUTHORIZED, CameraFailure.DISABLED -> context.getString(R.string.camera_error_not_authorized)
    CameraFailure.NO_FRONT_CAMERA -> context.getString(R.string.camera_error_no_front_camera)
    CameraFailure.CONFIGURATION_FAILED -> context.getString(R.string.camera_error_configuration_failed, detail ?: "")
    CameraFailure.DID_NOT_COME_BACK -> context.getString(R.string.camera_error_did_not_come_back)
    CameraFailure.STOPPED_UNEXPECTEDLY -> context.getString(R.string.camera_error_stopped_unexpectedly)
}
