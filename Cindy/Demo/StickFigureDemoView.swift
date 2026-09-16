import SwiftUI

/// Draws an `ExerciseDemo` as a looping stick figure.
///
/// This is the renderer that always works: no assets, no Metal, no camera, and
/// it runs in the simulator and in Xcode previews. `ExerciseDemoView` upgrades
/// to RealityKit when a model for the exercise is bundled.
struct StickFigureDemoView: View {
    let demo: ExerciseDemo
    /// Set while the view is off screen to stop the timeline from redrawing.
    var isPaused: Bool = false

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: isPaused)) { context in
            Canvas { ctx, size in
                let scene = Scene(demo: demo, in: size)
                let pose = demo.start.blended(towards: demo.end,
                                              Self.phase(at: context.date, cycle: demo.cycle))
                draw(demo.props, in: &ctx, scene: scene)
                draw(pose, in: &ctx, scene: scene)
                // The phone goes on top: under a plank's forearms or a squat's feet it would vanish.
                for case .phone(let x) in demo.props {
                    draw(phoneAt: x, in: &ctx, scene: scene)
                }
            }
        }
        .aspectRatio(demo.aspectRatio, contentMode: .fit)
        .accessibilityHidden(true)
    }

    /// Eases out to `end` and back once per `cycle`, lingering a little at both
    /// ends — a cosine does that on its own, no keyframes needed.
    static func phase(at date: Date, cycle: Double) -> Double {
        let elapsed = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: cycle)
        return 0.5 - 0.5 * cos(2 * .pi * elapsed / cycle)
    }

    /// Maps the demo's unit-square coordinates onto the canvas, fitting the
    /// movement's own bounding box rather than the whole square.
    private struct Scene {
        let scale: Double
        let origin: CGPoint
        /// Horizontal extent of the scene on screen, for the floor and the bar.
        let left: Double
        let right: Double

        init(demo: ExerciseDemo, in size: CGSize) {
            let box = demo.bounds
            scale = min(size.width / box.width, size.height / box.height)
            origin = CGPoint(x: (size.width - box.width * scale) / 2 - box.minX * scale,
                             y: (size.height - box.height * scale) / 2 - box.minY * scale)
            left = origin.x + box.minX * scale
            right = origin.x + box.maxX * scale
        }

        func callAsFunction(_ p: CGPoint) -> CGPoint {
            CGPoint(x: origin.x + p.x * scale, y: origin.y + p.y * scale)
        }
    }

    // MARK: - Drawing

    private func draw(_ pose: StickPose, in ctx: inout GraphicsContext, scene: Scene) {
        let radius = scene.scale * demo.headRadius
        let stroke = StrokeStyle(lineWidth: radius * 0.42, lineCap: .round, lineJoin: .round)

        func limbs(_ transform: (CGPoint) -> CGPoint, shading: GraphicsContext.Shading) {
            var path = Path()
            // Start at the neck so the far arm is joined to the body instead
            // of floating beside it.
            path.addLines([transform(pose.neck), transform(pose.shoulder),
                           transform(pose.elbow), transform(pose.hand)].map(scene.callAsFunction))
            path.move(to: scene(transform(pose.hip)))
            path.addLines([transform(pose.hip), transform(pose.knee), transform(pose.foot)].map(scene.callAsFunction))
            ctx.stroke(path, with: shading, style: stroke)
        }

        // The far arm and leg first, so the near ones overlap them.
        limbs({ demo.farSide(of: $0, in: pose) }, shading: .color(.brand.opacity(0.35)))

        var torso = Path()
        torso.addLines([pose.neck, pose.hip].map(scene.callAsFunction))
        torso.move(to: scene(pose.neck))
        torso.addLine(to: scene(pose.shoulder))
        torso.move(to: scene(pose.neck))
        torso.addLine(to: scene(pose.head))
        ctx.stroke(torso, with: .color(.brand), style: stroke)

        limbs({ $0 }, shading: .color(.brand))

        let head = scene(pose.head)
        ctx.fill(Path(ellipseIn: CGRect(x: head.x - radius, y: head.y - radius,
                                        width: radius * 2, height: radius * 2)),
                 with: .color(.brand))
    }

    private func draw(_ props: [StickProp], in ctx: inout GraphicsContext, scene: Scene) {
        for prop in props {
            switch prop {
            case .floor(let y):
                line(at: y, from: 0, to: 1, width: 0.16, in: &ctx, scene: scene)
            case .bar(let y):
                line(at: y, from: 0.12, to: 0.88, width: 0.32, in: &ctx, scene: scene)
            case .phone:
                break // drawn over the figure, see `body`
            }
        }
    }

    /// A horizontal prop spanning a fraction of the scene's full width;
    /// `width` is a multiple of the head radius.
    private func line(at y: Double, from: Double, to: Double, width: Double,
                      in ctx: inout GraphicsContext, scene: Scene) {
        let span = scene.right - scene.left
        var path = Path()
        path.addLines([CGPoint(x: scene.left + span * from, y: scene(CGPoint(x: 0, y: y)).y),
                       CGPoint(x: scene.left + span * to, y: scene(CGPoint(x: 0, y: y)).y)])
        ctx.stroke(path, with: .color(.secondary),
                   style: StrokeStyle(lineWidth: scene.scale * demo.headRadius * width, lineCap: .round))
    }

    /// The phone as a thin slab lying flat on the floor, its lit screen on top.
    private func draw(phoneAt x: Double, in ctx: inout GraphicsContext, scene: Scene) {
        // A phone is about a head long — sizing it off the head
        // keeps it believable whatever scale the movement is drawn at.
        let unit = scene.scale * demo.headRadius
        let width = unit * 2.2
        let height = unit * 0.45
        let floor = scene(CGPoint(x: x, y: demo.floorY))
        // Resting on top of the floor line, whose stroke is 0.16 head radii thick.
        let body = CGRect(x: floor.x - width / 2, y: floor.y - unit * 0.08 - height, width: width, height: height)
        let screen = CGRect(x: body.minX + width * 0.06, y: body.minY, width: width * 0.88, height: height * 0.35)
        // Opaque, unlike `.secondary`: it lies on top of the limbs, which must not show through.
        ctx.fill(Path(roundedRect: body, cornerRadius: height * 0.35), with: .color(Color(.systemGray)))
        ctx.fill(Path(roundedRect: screen, cornerRadius: height * 0.15), with: .color(.brand))
    }
}

#Preview {
    VStack {
        ForEach(Exercise.allCases) { exercise in
            StickFigureDemoView(demo: exercise.demo)
                .frame(height: 150)
        }
    }
}
