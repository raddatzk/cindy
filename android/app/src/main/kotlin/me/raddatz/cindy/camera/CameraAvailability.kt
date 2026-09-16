package me.raddatz.cindy.camera

/** Whether the camera is delivering frames, and why not when it is not. */
sealed interface CameraAvailability {
    data object Running : CameraAvailability

    /**
     * Frames stopped for a reason the system undoes by itself: a call screen, another app taking
     * the camera, the app going to the background. [Running] follows once it lets go.
     */
    data object Interrupted : CameraAvailability

    /** The camera stopped and could not be brought back. */
    data class Failed(val failure: CameraFailure, val detail: String? = null) : CameraAvailability
}

/**
 * Why the camera cannot run. User-facing text: [message] (iOS keys quoted per case).
 */
enum class CameraFailure {
    /** "No access to the camera. Please allow it in Settings." */
    NOT_AUTHORIZED,

    /** "No front camera found." */
    NO_FRONT_CAMERA,

    /** "The camera could not be configured: %@" (detail = reason). */
    CONFIGURATION_FAILED,

    /** Camera disabled by device policy; shown with the "No access to the camera" text. */
    DISABLED,

    /** "The camera did not come back. Please start again." */
    DID_NOT_COME_BACK,

    /** "The camera stopped unexpectedly. Please start again." */
    STOPPED_UNEXPECTEDLY,
}

/** Thrown by [FrameSource.start] when the camera cannot be started. */
class CameraException(
    val failure: CameraFailure,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: failure.name, cause)
