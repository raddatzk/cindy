import RealityKit
import SwiftUI

/// Plays an animated USDZ of one exercise.
///
/// Deliberately *not* an AR view: the scene is virtual, so there is no
/// pass-through camera. That matters twice over — it needs no camera
/// permission, and it never competes with the `AVCaptureSession` that counts
/// reps. Putting the same avatar into the real room later means switching the
/// camera to `.worldTracking`, nothing else.
struct RealityDemoView: View {
    let url: URL
    /// Which side to watch the movement from — the same choice the stick
    /// figure makes, for the same reason.
    let perspective: StickPerspective
    /// Called when the model cannot be loaded, so the caller can fall back to
    /// the stick figure instead of leaving an empty box.
    var onFailure: () -> Void

    /// Largest extent of a finished model, in metres. Set by the exporter's
    /// `--size`, not here: the models arrive centred on the origin and scaled
    /// to this across their whole animation, so the camera can be fixed.
    private static let modelExtent: Float = 1.7
    private static let fieldOfView: Float = 40

    var body: some View {
        RealityView { content in
            content.add(Self.makeLight())
            content.add(Self.makeCamera(perspective))
            do {
                let model = try await Entity(contentsOf: url)
                Self.loopAnimation(of: model)
                content.add(model)
            } catch {
                onFailure()
            }
        }
        .realityViewCameraControls(.orbit)
    }

    // MARK: - Scene

    /// Places the camera where the movement actually reads.
    ///
    /// Push-up, plank and squat happen in the sagittal plane, and seen head-on
    /// they are foreshortened blobs. A pull-up is the opposite: from the side
    /// the bar is a dot and the body hides behind its own arm. The orbit
    /// control lets the user turn it either way afterwards.
    private static func makeCamera(_ perspective: StickPerspective) -> Entity {
        let camera = Entity()
        camera.components.set(PerspectiveCameraComponent(fieldOfViewInDegrees: fieldOfView))
        // Far enough back that the widest frame of the movement still fits,
        // with a little air around it.
        let distance = (modelExtent * 1.15 / 2) / tan(fieldOfView / 2 * .pi / 180)
        let eye: SIMD3<Float> = switch perspective {
        case .side: SIMD3(distance, 0, 0)
        case .front: SIMD3(0, 0, distance)
        }
        camera.look(at: .zero, from: eye, relativeTo: nil)
        return camera
    }

    /// A virtual scene has no real-world light to borrow, so it needs one
    /// explicit source or the model renders black.
    private static func makeLight() -> Entity {
        let light = Entity()
        light.components.set(DirectionalLightComponent(intensity: 12_000))
        light.look(at: .zero, from: SIMD3<Float>(1.5, 3, 2.5), relativeTo: nil)
        return light
    }

    private static func loopAnimation(of model: Entity) {
        guard let animation = model.availableAnimations.first else {
            // A still model beats an empty box, but a demo is supposed to
            // move — say so rather than shipping a statue by accident.
            assertionFailure("\(model.name) has no animation to play")
            return
        }
        model.playAnimation(animation.repeat(), transitionDuration: 0.2, startsPaused: false)
    }
}
