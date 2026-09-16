import AVFoundation
import Foundation

enum CameraError: LocalizedError {
    case notAuthorized
    case noFrontCamera
    case configurationFailed(String)

    var errorDescription: String? {
        switch self {
        case .notAuthorized:
            return L("No access to the camera. Please allow it in Settings.")
        case .noFrontCamera:
            return L("No front camera found.")
        case .configurationFailed(let reason):
            return L("The camera could not be configured: \(reason)")
        }
    }
}

/// Whether the session is delivering frames, and why not when it is not.
enum CameraAvailability: Equatable {
    case running
    /// Frames stopped for a reason the system undoes by itself: a call, another
    /// app taking the camera, the app going to the background. `running` follows
    /// once it lets go.
    case interrupted
    /// The session stopped and could not be brought back.
    case failed(String)
}

/// Front-camera capture session delivering sample buffers on a dedicated queue.
///
/// Exposure runs on auto for `exposureSettleDuration` after start and is then
/// locked so ceiling lights / backlight do not modulate the face signal.
final class CameraSession: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    /// Frames and everything derived from them are processed on this queue.
    let videoQueue = DispatchQueue(label: "me.raddatz.cindy.video", qos: .userInitiated)

    /// Called on `videoQueue` for every frame.
    var frameHandler: ((CMSampleBuffer) -> Void)?

    /// Called on the main queue when frame delivery stops or comes back. Never
    /// called for a `stop()` this app asked for. Set it before `start()`.
    var onAvailabilityChange: ((CameraAvailability) -> Void)?

    private let sessionQueue = DispatchQueue(label: "me.raddatz.cindy.camera")
    private let output = AVCaptureVideoDataOutput()
    private var device: AVCaptureDevice?
    private var isConfigured = false
    private var exposureLockWorkItem: DispatchWorkItem?
    private let config: SignalConfig
    /// What this app asked for, as opposed to `session.isRunning`, which the
    /// system changes on its own during an interruption.
    private var shouldBeRunning = false
    private var observers: [NSObjectProtocol] = []

    init(config: SignalConfig = .default) {
        self.config = config
        super.init()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    // MARK: - Authorisation

    static func requestAccess() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }

    // MARK: - Lifecycle

    /// Configures the session once. Safe to call repeatedly.
    func configure() throws {
        var thrown: Error?
        sessionQueue.sync {
            do { try self.configureLocked() } catch { thrown = error }
        }
        if let thrown { throw thrown }
    }

    func start() {
        sessionQueue.async {
            guard self.isConfigured, !self.session.isRunning else { return }
            self.shouldBeRunning = true
            self.setContinuousExposure()
            self.session.startRunning()
            self.scheduleExposureLock()
        }
    }

    func stop() {
        sessionQueue.async {
            self.shouldBeRunning = false
            self.exposureLockWorkItem?.cancel()
            self.exposureLockWorkItem = nil
            guard self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    /// Re-runs auto exposure and locks it again after the settle duration.
    func relockExposure() {
        sessionQueue.async {
            self.setContinuousExposure()
            self.scheduleExposureLock()
        }
    }

    // MARK: - Configuration

    private func configureLocked() throws {
        guard !isConfigured else { return }
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
            throw CameraError.notAuthorized
        }
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInTrueDepthCamera, .builtInWideAngleCamera],
            mediaType: .video,
            position: .front
        )
        guard let camera = discovery.devices.first else { throw CameraError.noFrontCamera }

        session.beginConfiguration()
        defer { session.commitConfiguration() }

        if session.canSetSessionPreset(.hd1280x720) {
            session.sessionPreset = .hd1280x720
        }

        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: camera)
        } catch {
            throw CameraError.configurationFailed(error.localizedDescription)
        }
        guard session.canAddInput(input) else { throw CameraError.configurationFailed("input") }
        session.addInput(input)

        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        ]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: videoQueue)
        guard session.canAddOutput(output) else { throw CameraError.configurationFailed("output") }
        session.addOutput(output)

        configureFrameRate(camera)
        device = camera
        isConfigured = true
        observeSession()
    }

    private func configureFrameRate(_ camera: AVCaptureDevice) {
        do {
            try camera.lockForConfiguration()
            defer { camera.unlockForConfiguration() }
            let fps = config.targetFrameRate
            let supports = camera.activeFormat.videoSupportedFrameRateRanges.contains {
                $0.minFrameRate <= fps && fps <= $0.maxFrameRate
            }
            if supports {
                let duration = CMTime(value: 1, timescale: CMTimeScale(fps))
                camera.activeVideoMinFrameDuration = duration
                camera.activeVideoMaxFrameDuration = duration
            }
        } catch {
            // Frame-rate configuration is best effort.
        }
    }

    // MARK: - Exposure

    private func setContinuousExposure() {
        guard let device else { return }
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            if device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposureMode = .continuousAutoExposure
            }
            if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) {
                device.whiteBalanceMode = .continuousAutoWhiteBalance
            }
        } catch {
            // Best effort.
        }
    }

    private func scheduleExposureLock() {
        exposureLockWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.lockExposure() }
        exposureLockWorkItem = item
        sessionQueue.asyncAfter(deadline: .now() + config.exposureSettleDuration, execute: item)
    }

    private func lockExposure() {
        guard let device, session.isRunning else { return }
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            if device.isExposureModeSupported(.locked) {
                device.exposureMode = .locked
            }
            if device.isWhiteBalanceModeSupported(.locked) {
                device.whiteBalanceMode = .locked
            }
        } catch {
            // Best effort.
        }
    }

    // MARK: - Interruptions

    /// A capture session loses the camera to calls, other apps and the
    /// background, and to a media-services reset it cannot recover from on its
    /// own. Without this the frames simply stop and everything downstream keeps
    /// waiting for a rep that can no longer arrive.
    private func observeSession() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: AVCaptureSession.wasInterruptedNotification,
                               object: session, queue: nil) { [weak self] _ in
                self?.sessionQueue.async { self?.handleInterruptionBegan() }
            },
            center.addObserver(forName: AVCaptureSession.interruptionEndedNotification,
                               object: session, queue: nil) { [weak self] _ in
                self?.sessionQueue.async { self?.handleInterruptionEnded() }
            },
            center.addObserver(forName: AVCaptureSession.runtimeErrorNotification,
                               object: session, queue: nil) { [weak self] note in
                let error = note.userInfo?[AVCaptureSessionErrorKey] as? AVError
                self?.sessionQueue.async { self?.handleRuntimeError(error) }
            },
        ]
    }

    private func handleInterruptionBegan() {
        guard shouldBeRunning else { return }
        exposureLockWorkItem?.cancel()
        exposureLockWorkItem = nil
        report(.interrupted)
    }

    /// The system restarts the session itself, but the exposure lock does not
    /// survive, so it is re-run rather than left wherever the interruption left
    /// it — a locked exposure from the wrong moment silently shifts the signal.
    private func handleInterruptionEnded() {
        guard shouldBeRunning else { return }
        if !session.isRunning { session.startRunning() }
        guard session.isRunning else {
            shouldBeRunning = false
            report(.failed(L("The camera did not come back. Please start again.")))
            return
        }
        setContinuousExposure()
        scheduleExposureLock()
        report(.running)
    }

    /// `mediaServicesWereReset` is the one runtime error worth retrying; the
    /// session is intact and only needs starting again.
    private func handleRuntimeError(_ error: AVError?) {
        guard shouldBeRunning else { return }
        report(.interrupted)
        if error?.code == .mediaServicesWereReset {
            session.startRunning()
            if session.isRunning {
                setContinuousExposure()
                scheduleExposureLock()
                report(.running)
                return
            }
        }
        shouldBeRunning = false
        report(.failed(error?.localizedDescription
            ?? L("The camera stopped unexpectedly. Please start again.")))
    }

    private func report(_ availability: CameraAvailability) {
        guard let handler = onAvailabilityChange else { return }
        DispatchQueue.main.async { handler(availability) }
    }

    // MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        frameHandler?(sampleBuffer)
    }

}
