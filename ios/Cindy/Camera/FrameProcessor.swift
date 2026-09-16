import AVFoundation
import Foundation

/// Glue between the camera queue and the signal chain.
///
/// Owns the `VisionProcessor`, the current `SignalPipeline` and an optional
/// `FrameLogger`. Everything here runs on the camera's `videoQueue`; the
/// pipeline is swapped by dispatching onto that queue, so the swap never races
/// a frame that is being processed.
final class FrameProcessor {
    let camera: CameraSession
    let vision: VisionProcessor

    /// Called on the video queue for every frame.
    var onFrame: ((FrameObservation, PipelineOutput?) -> Void)?

    private var pipeline: SignalPipeline?
    private var logger: FrameLogger?
    private var stateProvider: (() -> String)?
    private var measuresMetrics = false
    private var measuresDepth = false
    private var latestDepth: (metrics: DepthMetrics, timestamp: TimeInterval)?

    init(camera: CameraSession) {
        self.camera = camera
        self.vision = VisionProcessor()
        camera.frameHandler = { [weak self] buffer in self?.handle(buffer) }
        camera.depthHandler = { [weak self] depthData, timestamp in self?.handleDepth(depthData, timestamp) }
    }

    /// Replaces the pipeline (nil = vision only, no rep detection).
    func setPipeline(_ pipeline: SignalPipeline?) {
        camera.videoQueue.async { self.pipeline = pipeline }
    }

    func setLogger(_ logger: FrameLogger?, stateProvider: (() -> String)? = nil) {
        camera.videoQueue.async {
            self.logger = logger
            self.stateProvider = stateProvider
        }
    }

    /// Chooses the Vision requests; each costs processing time per frame.
    func setDetection(face: Bool, bodyPose: Bool) {
        camera.videoQueue.async {
            self.vision.detectFace = face
            self.vision.detectBodyPose = bodyPose
        }
    }

    /// Runs only what the signal source needs; brightness keeps face and pose for `BodyEvidence`.
    func setDetection(for source: SignalSource) {
        setDetection(face: source != .pose, bodyPose: source != .face)
        setMetricsEnabled(source == .brightness)
    }

    /// Measures the `FrameMetrics` (brightness) per frame.
    func setMetricsEnabled(_ enabled: Bool) {
        camera.videoQueue.async { self.measuresMetrics = enabled }
    }

    /// Attaches the latest TrueDepth metrics to every frame (debug recorder only).
    func setDepthEnabled(_ enabled: Bool) {
        camera.videoQueue.async {
            self.measuresDepth = enabled
            self.latestDepth = nil
        }
    }

    private func handleDepth(_ depthData: AVDepthData, _ timestamp: CMTime) {
        guard measuresDepth, let metrics = DepthMetricsCalculator.measure(depthData) else { return }
        latestDepth = (metrics, timestamp.seconds)
    }

    private func handle(_ buffer: CMSampleBuffer) {
        guard var observation = vision.process(buffer) else { return }
        if measuresMetrics {
            observation.metrics = FrameMetricsCalculator.measure(buffer)
        }
        if measuresDepth, var depth = latestDepth?.metrics, let depthTime = latestDepth?.timestamp {
            depth.age = observation.timestamp - depthTime
            observation.depth = depth
        }
        let output = pipeline?.process(observation)
        logger?.log(observation: observation, output: output, state: stateProvider?() ?? "")
        onFrame?(observation, output)
    }
}
