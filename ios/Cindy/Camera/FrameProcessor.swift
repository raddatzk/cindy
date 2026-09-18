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
    /// Written from the main actor and `depthQueue`, read on `videoQueue`.
    private let measuresDepth = LockedValue(false)
    private let latestDepth = LockedValue<(metrics: DepthMetrics, timestamp: TimeInterval)?>(nil)

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

    /// Runs only what the signal source needs; brightness keeps face and pose for `BodyEvidence`,
    /// depth keeps the face for the plank and the on-screen indicator.
    func setDetection(for source: SignalSource) {
        setDetection(face: source != .pose, bodyPose: source == .pose || source == .brightness)
        setMetricsEnabled(source == .brightness)
        setDepthEnabled(source == .depth)
        if source == .depth { prepareDepth(for: [source]) }
    }

    /// Adds the depth stream to the configured camera when any of `sources` needs it. Switching the
    /// camera to a depth format interrupts the frames briefly, so call it before a countdown rather than
    /// between exercises; once added the stream stays (frames without a depth source ignore it).
    func prepareDepth(for sources: [SignalSource]) {
        guard sources.contains(.depth) else { return }
        camera.setDepthEnabled(true)
    }

    /// Measures the `FrameMetrics` (brightness) per frame.
    func setMetricsEnabled(_ enabled: Bool) {
        camera.videoQueue.async { self.measuresMetrics = enabled }
    }

    /// Attaches the latest TrueDepth metrics to every frame (depth signal, debug recorder).
    func setDepthEnabled(_ enabled: Bool) {
        measuresDepth.set(enabled)
        latestDepth.set(nil)
    }

    private func handleDepth(_ depthData: AVDepthData, _ timestamp: CMTime) {
        guard measuresDepth.get(), let metrics = DepthMetricsCalculator.measure(depthData) else { return }
        latestDepth.set((metrics, timestamp.seconds))
    }

    private func handle(_ buffer: CMSampleBuffer) {
        guard var observation = vision.process(buffer) else { return }
        if measuresMetrics {
            observation.metrics = FrameMetricsCalculator.measure(buffer)
        }
        if measuresDepth.get(), let latest = latestDepth.get() {
            var depth = latest.metrics
            depth.age = observation.timestamp - latest.timestamp
            observation.depth = depth
        }
        let output = pipeline?.process(observation)
        logger?.log(observation: observation, output: output, state: stateProvider?() ?? "")
        onFrame?(observation, output)
    }
}
