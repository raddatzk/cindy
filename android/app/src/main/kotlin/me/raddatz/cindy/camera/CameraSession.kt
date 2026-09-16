package me.raddatz.cindy.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.raddatz.cindy.core.SignalConfig
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Front-camera capture delivering YUV frames on a dedicated thread (iOS: `CameraSession`).
 *
 * Exposure and white balance run on auto for `exposureSettleDuration` after the camera opens and
 * are then locked, so ceiling lights and backlight do not modulate the signal — squats count on the
 * image brightness, which an auto exposure would pull back towards grey. The settle-and-lock runs
 * again every time the camera comes back.
 *
 * Bound to the [lifecycleOwner] passed in: CameraX closes the camera when it stops (app in the
 * background, a full-screen call) and reopens it when it starts again. Those transitions, and the
 * errors CameraX reports, are published through [onAvailabilityChange] like the interruption
 * notifications of an `AVCaptureSession`.
 *
 * Everything except [frameHandler] runs on the main thread.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraSession(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val config: SignalConfig = SignalConfig.default,
) {
    private val appContext = context.applicationContext

    /** Frames and everything derived from them are processed on this thread. */
    val videoExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "me.raddatz.cindy.video").apply { isDaemon = true }
    }

    /**
     * Called on [videoExecutor] for every frame. The image is closed when it returns, so the
     * camera delivers the next (latest) frame only after the handler is done with this one.
     */
    @Volatile
    var frameHandler: ((ImageProxy) -> Unit)? = null

    /** Called on the main thread when frames stop or come back. Never for a [stop] this app asked for. */
    var onAvailabilityChange: ((CameraAvailability) -> Unit)? = null

    /** The preview use case; [CameraPreview] attaches its surface. Bound together with the analysis. */
    val preview: Preview = Preview.Builder()
        .setResolutionSelector(resolutionSelector())
        .setTargetRotation(Surface.ROTATION_0)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var observedState: androidx.lifecycle.LiveData<CameraState>? = null
    private var exposureLockJob: Job? = null
    private var fpsRange: Range<Int>? = null
    private var aeLockAvailable = false
    private var awbLockAvailable = false

    /** What this app asked for, as opposed to the camera state, which the system changes on its own. */
    private var shouldBeRunning = false
    private var hasOpened = false
    private var isInterrupted = false
    private var restartAttempted = false
    private var errorHandled = false

    private val stateObserver = Observer<CameraState> { handle(it) }
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (shouldBeRunning && hasOpened) interruptionBegan()
        }
    }

    // Lifecycle

    /**
     * Opens the front camera. Safe to call again while running.
     * @throws CameraException when it cannot be started.
     */
    suspend fun start() = withContext(Dispatchers.Main.immediate) {
        if (shouldBeRunning) return@withContext
        if (!hasPermission(appContext)) throw CameraException(CameraFailure.NOT_AUTHORIZED)
        val cameraProvider = provider ?: try {
            ProcessCameraProvider.getInstance(appContext).await()
        } catch (e: Exception) {
            throw CameraException(CameraFailure.CONFIGURATION_FAILED, e.message, e)
        }.also { provider = it }
        val info = try {
            CameraSelector.DEFAULT_FRONT_CAMERA.filter(cameraProvider.availableCameraInfos).firstOrNull()
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw CameraException(CameraFailure.NO_FRONT_CAMERA)
        readCharacteristics(info)
        if (analysis == null) analysis = buildAnalysis()
        shouldBeRunning = true
        hasOpened = false
        isInterrupted = false
        restartAttempted = false
        errorHandled = false
        try {
            bind(cameraProvider)
        } catch (e: CameraException) {
            shouldBeRunning = false
            throw e
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
    }

    @MainThread
    fun stop() {
        shouldBeRunning = false
        cancelExposureLock()
        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        observedState?.removeObserver(stateObserver)
        observedState = null
        unbind()
        camera = null
        isInterrupted = false
    }

    /** Stops for good; the frame thread ends once it is idle. */
    @MainThread
    fun release() {
        stop()
        analysis?.clearAnalyzer()
        scope.cancel()
        videoExecutor.shutdown()
    }

    /** Re-runs auto exposure and locks it again after the settle duration. */
    @MainThread
    fun relockExposure() {
        if (camera == null) return
        setContinuousExposure()
        scheduleExposureLock()
    }

    // Configuration

    private fun resolutionSelector(): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        // Sizes are in sensor orientation (landscape): 1280×720 like iOS's `.hd1280x720` preset.
        .setResolutionStrategy(
            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
        )
        .build()

    private fun buildAnalysis(): ImageAnalysis {
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            // Portrait, so `rotationDegrees` is the rotation that makes a portrait frame upright —
            // the reference the orientation candidates are defined against.
            .setTargetRotation(Surface.ROTATION_0)
        // The AE target range applies to the whole capture session, the preview included.
        fpsRange?.let { Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        return builder.build().also { analysis ->
            analysis.setAnalyzer(videoExecutor) { image ->
                try {
                    frameHandler?.invoke(image)
                } finally {
                    image.close()
                }
            }
        }
    }

    private fun readCharacteristics(info: CameraInfo) {
        val camera2 = Camera2CameraInfo.from(info)
        aeLockAvailable = camera2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        awbLockAvailable = camera2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        if (analysis == null) {
            // Frame-rate configuration is best effort, like on iOS.
            val ranges = camera2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { it.lower to it.upper }
                .orEmpty()
            fpsRange = CameraTuning.targetFpsRange(ranges, config.targetFrameRate.roundToInt())
                ?.let { Range(it.first, it.second) }
        }
    }

    private fun bind(provider: ProcessCameraProvider) {
        val analysis = analysis ?: return
        observedState?.removeObserver(stateObserver)
        val bound = try {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        } catch (e: IllegalArgumentException) {
            throw CameraException(CameraFailure.CONFIGURATION_FAILED, e.message, e)
        } catch (e: IllegalStateException) {
            throw CameraException(CameraFailure.CONFIGURATION_FAILED, e.message, e)
        } catch (e: UnsupportedOperationException) {
            throw CameraException(CameraFailure.CONFIGURATION_FAILED, e.message, e)
        }
        camera = bound
        observedState = bound.cameraInfo.cameraState.also { it.observeForever(stateObserver) }
    }

    private fun unbind() {
        val provider = provider ?: return
        val analysis = analysis
        if (analysis != null) provider.unbind(preview, analysis) else provider.unbind(preview)
    }

    // Exposure

    private fun setContinuousExposure() {
        applyLocks(locked = false)
    }

    private fun scheduleExposureLock() {
        exposureLockJob?.cancel()
        exposureLockJob = scope.launch {
            delay((config.exposureSettleDuration * 1000).roundToLong())
            lockExposure()
        }
    }

    private fun cancelExposureLock() {
        exposureLockJob?.cancel()
        exposureLockJob = null
    }

    private fun lockExposure() {
        if (!shouldBeRunning || camera?.cameraInfo?.cameraState?.value?.type != CameraState.Type.OPEN) return
        applyLocks(locked = true)
    }

    /** Best effort: a device without the lock keys keeps auto exposure. */
    private fun applyLocks(locked: Boolean) {
        val camera = camera ?: return
        if (!aeLockAvailable && !awbLockAvailable) return
        val options = CaptureRequestOptions.Builder().apply {
            if (aeLockAvailable) setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
            if (awbLockAvailable) setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)
        }.build()
        Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(options)
    }

    // Interruptions

    /**
     * The camera is lost to other apps, the background and camera-service failures. Without this
     * the frames simply stop and everything downstream keeps waiting for a rep that can no longer
     * arrive.
     */
    private fun handle(state: CameraState) {
        if (!shouldBeRunning) return
        val error = state.error
        when (state.type) {
            CameraState.Type.OPEN -> opened()
            // CameraX retries these by itself once the camera is free again.
            CameraState.Type.PENDING_OPEN -> interruptionBegan()
            CameraState.Type.OPENING -> if (error != null) interruptionBegan()
            CameraState.Type.CLOSING, CameraState.Type.CLOSED -> when {
                error == null -> if (hasOpened) interruptionBegan()
                errorHandled -> Unit
                else -> {
                    errorHandled = true
                    handleError(error)
                }
            }
        }
    }

    /**
     * The exposure lock does not survive a reopen, so it is re-run rather than left wherever the
     * interruption left it — a locked exposure from the wrong moment silently shifts the signal.
     */
    private fun opened() {
        hasOpened = true
        errorHandled = false
        setContinuousExposure()
        scheduleExposureLock()
        if (isInterrupted) {
            isInterrupted = false
            restartAttempted = false
            report(CameraAvailability.Running)
        }
    }

    private fun interruptionBegan() {
        if (isInterrupted) return
        isInterrupted = true
        cancelExposureLock()
        report(CameraAvailability.Interrupted)
    }

    /**
     * A fatal camera error is the Android counterpart of `mediaServicesWereReset`: the one worth
     * retrying, by binding the use cases again. Everything else ends the session.
     */
    private fun handleError(error: CameraState.StateError) {
        val wasInterrupted = isInterrupted
        when (error.code) {
            CameraState.ERROR_CAMERA_FATAL_ERROR -> {
                interruptionBegan()
                val provider = provider
                if (!restartAttempted && provider != null) {
                    restartAttempted = true
                    try {
                        unbind()
                        bind(provider)
                        errorHandled = false
                        return
                    } catch (_: CameraException) {
                        // Falls through to the failure below.
                    }
                }
                fail(CameraFailure.STOPPED_UNEXPECTEDLY)
            }
            CameraState.ERROR_CAMERA_DISABLED -> fail(CameraFailure.DISABLED)
            CameraState.ERROR_STREAM_CONFIG -> fail(CameraFailure.CONFIGURATION_FAILED)
            // Recoverable errors while opening: CameraX keeps trying.
            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE,
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR,
            -> {
                errorHandled = false
                interruptionBegan()
            }
            else -> fail(if (wasInterrupted) CameraFailure.DID_NOT_COME_BACK else CameraFailure.STOPPED_UNEXPECTEDLY)
        }
    }

    private fun fail(failure: CameraFailure) {
        shouldBeRunning = false
        cancelExposureLock()
        isInterrupted = false
        report(CameraAvailability.Failed(failure))
    }

    private fun report(availability: CameraAvailability) {
        onAvailabilityChange?.invoke(availability)
    }

    companion object {
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
            addListener({
                try {
                    continuation.resume(get())
                } catch (e: ExecutionException) {
                    continuation.resumeWithException(e.cause ?: e)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }, { it.run() })
        }
    }
}
