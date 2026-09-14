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

    init(camera: CameraSession, detectBodyPose: Bool = false) {
        self.camera = camera
        self.vision = VisionProcessor(detectBodyPose: detectBodyPose)
        camera.frameHandler = { [weak self] buffer in self?.handle(buffer) }
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

    func setBodyPoseEnabled(_ enabled: Bool) {
        camera.videoQueue.async { self.vision.detectBodyPose = enabled }
    }

    private func handle(_ buffer: CMSampleBuffer) {
        guard let observation = vision.process(buffer) else { return }
        let output = pipeline?.process(observation)
        logger?.log(observation: observation, output: output, state: stateProvider?() ?? "")
        onFrame?(observation, output)
    }
}
