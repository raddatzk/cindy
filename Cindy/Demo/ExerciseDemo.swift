import CoreGraphics
import Foundation

/// One frame of the stick figure: joint positions in the unit square, with y
/// growing downwards so the values can be used as SwiftUI coordinates directly.
///
/// Only one arm and one leg are stored. The renderer derives the second pair
/// from `StickPerspective`, which keeps a pose to eight numbers a human can
/// still reason about.
struct StickPose: Equatable {
    var head: CGPoint
    var neck: CGPoint
    var shoulder: CGPoint
    var elbow: CGPoint
    var hand: CGPoint
    var hip: CGPoint
    var knee: CGPoint
    var foot: CGPoint

    /// Linear blend towards `other`; `t` of 0 is `self`, 1 is `other`.
    func blended(towards other: StickPose, _ t: Double) -> StickPose {
        StickPose(head: Self.lerp(head, other.head, t),
                  neck: Self.lerp(neck, other.neck, t),
                  shoulder: Self.lerp(shoulder, other.shoulder, t),
                  elbow: Self.lerp(elbow, other.elbow, t),
                  hand: Self.lerp(hand, other.hand, t),
                  hip: Self.lerp(hip, other.hip, t),
                  knee: Self.lerp(knee, other.knee, t),
                  foot: Self.lerp(foot, other.foot, t))
    }

    private static func lerp(_ a: CGPoint, _ b: CGPoint, _ t: Double) -> CGPoint {
        CGPoint(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t)
    }
}

/// How the figure is seen, which decides where the second arm and leg go.
enum StickPerspective {
    /// Seen from the side: the far limbs sit slightly behind, drawn faded.
    case side
    /// Seen head-on: the far limbs are the mirror image across the spine.
    case front
}

/// Scenery drawn behind the figure. Coordinates share the pose's unit square.
enum StickProp: Equatable {
    case floor(y: Double)
    case bar(y: Double)
    /// The iPhone lying flat on the floor, screen up, directly under the face — the same
    /// spot for every exercise, no stand or weight plate needed. Sits on the `floor` prop.
    case phone(x: Double)
}

/// Everything needed to show one exercise: the movement itself, the scenery
/// around it, and the points that decide whether Cindy can count the reps.
struct ExerciseDemo {
    var perspective: StickPerspective
    /// The position the exercise starts and ends in — also the one calibration
    /// asks you to hold still before the countdown.
    var start: StickPose
    /// The far end of the movement.
    var end: StickPose
    /// Seconds for one full cycle, out to `end` and back.
    var cycle: Double
    var props: [StickProp]
    /// Short form cues, most important first.
    var cues: [String]
    /// Where the athlete is relative to the phone, which stays in one spot all workout.
    var placement: String
    /// The one cue that defines a full rep (or a good hold), for the intro.
    var keyCue: String
    /// Base name of an optional animated USDZ in the bundle. When that file is
    /// present the RealityKit renderer takes over and the stick figure becomes
    /// the fallback; see `ExerciseDemoView`.
    var modelName: String
}

extension Exercise {
    /// The demo for this exercise. Rebuilt on every access so the cues follow
    /// a language change, like every other string in the app.
    var demo: ExerciseDemo {
        switch self {
        case .pullUp: return .pullUp
        case .pushUp: return .pushUp
        case .squat: return .squat
        case .plank: return .plank
        }
    }
}

// MARK: - The four movements
//
// Coordinates are not confined to 0…1: a push-up is far wider than it is tall.
// `bounds` measures whatever the pose actually spans and the renderer fits
// that, so each movement is laid out in whichever proportions are true to it.
// Limb lengths follow rough human ratios — arm ≈ 1.2 × torso, leg ≈ 1.5 ×
// torso — which is what makes a handful of line segments read as a body.

extension ExerciseDemo {
    /// Seen head-on, hanging from the bar. The hands stay put; everything else
    /// travels upwards until the chin clears the bar.
    static var pullUp: ExerciseDemo {
        ExerciseDemo(
            perspective: .front,
            start: StickPose(head: CGPoint(x: 0.50, y: 0.35),
                             neck: CGPoint(x: 0.50, y: 0.44),
                             shoulder: CGPoint(x: 0.58, y: 0.44),
                             elbow: CGPoint(x: 0.62, y: 0.29),
                             hand: CGPoint(x: 0.63, y: 0.14),
                             hip: CGPoint(x: 0.50, y: 0.69),
                             knee: CGPoint(x: 0.53, y: 0.89),
                             foot: CGPoint(x: 0.52, y: 1.08)),
            end: StickPose(head: CGPoint(x: 0.50, y: 0.07),
                           neck: CGPoint(x: 0.50, y: 0.16),
                           shoulder: CGPoint(x: 0.58, y: 0.16),
                           elbow: CGPoint(x: 0.72, y: 0.30),
                           hand: CGPoint(x: 0.63, y: 0.14),
                           hip: CGPoint(x: 0.50, y: 0.41),
                           knee: CGPoint(x: 0.53, y: 0.61),
                           foot: CGPoint(x: 0.52, y: 0.80)),
            cycle: 2.6,
            props: [.bar(y: 0.14), .floor(y: 1.12), .phone(x: 0.50)],
            cues: [L("Start hanging with straight arms."),
                   L("Pull until your chin is above the bar, then lower all the way down again."),
                   L("Phone on the floor under the bar, screen facing up.")],
            placement: L("Hang from the bar right above the phone."),
            keyCue: L("Pull until your chin is above the bar, then lower all the way down again."),
            modelName: "demo_pullup")
    }

    /// Seen from the side. Shoulders, hips and heels stay one straight line;
    /// only the elbow angle changes.
    static var pushUp: ExerciseDemo {
        ExerciseDemo(
            perspective: .side,
            start: StickPose(head: CGPoint(x: 0.13, y: 0.605),
                             neck: CGPoint(x: 0.26, y: 0.645),
                             shoulder: CGPoint(x: 0.22, y: 0.63),
                             elbow: CGPoint(x: 0.21, y: 0.79),
                             hand: CGPoint(x: 0.20, y: 0.95),
                             hip: CGPoint(x: 0.72, y: 0.78),
                             knee: CGPoint(x: 1.00, y: 0.864),
                             foot: CGPoint(x: 1.28, y: 0.95)),
            // Chest just off the floor, face still a gap above the phone lying under it.
            end: StickPose(head: CGPoint(x: 0.13, y: 0.80),
                           neck: CGPoint(x: 0.26, y: 0.865),
                           shoulder: CGPoint(x: 0.22, y: 0.86),
                           elbow: CGPoint(x: 0.40, y: 0.92),
                           hand: CGPoint(x: 0.20, y: 0.95),
                           hip: CGPoint(x: 0.72, y: 0.905),
                           knee: CGPoint(x: 1.00, y: 0.926),
                           foot: CGPoint(x: 1.28, y: 0.95)),
            cycle: 2.2,
            props: [.floor(y: 0.95), .phone(x: 0.11)],
            cues: [L("Start at the top with straight arms, face above the phone."),
                   L("Lower until your chest is just off the floor, then press back up."),
                   L("Keep your body one straight line from shoulders to heels.")],
            placement: L("With straight arms, your face is right above the phone."),
            keyCue: L("Lower until your chest is just off the floor, then press back up."),
            modelName: "demo_pushup")
    }

    /// Seen from the side so the depth of the squat is actually visible.
    static var squat: ExerciseDemo {
        ExerciseDemo(
            perspective: .side,
            start: StickPose(head: CGPoint(x: 0.50, y: 0.10),
                             neck: CGPoint(x: 0.50, y: 0.20),
                             shoulder: CGPoint(x: 0.50, y: 0.23),
                             elbow: CGPoint(x: 0.53, y: 0.42),
                             hand: CGPoint(x: 0.54, y: 0.61),
                             hip: CGPoint(x: 0.49, y: 0.52),
                             knee: CGPoint(x: 0.49, y: 0.81),
                             foot: CGPoint(x: 0.47, y: 1.10)),
            // Torso leans forward at the bottom, bringing the face over the phone in front of the toes.
            end: StickPose(head: CGPoint(x: 0.60, y: 0.50),
                           neck: CGPoint(x: 0.55, y: 0.59),
                           shoulder: CGPoint(x: 0.55, y: 0.63),
                           elbow: CGPoint(x: 0.74, y: 0.68),
                           hand: CGPoint(x: 0.90, y: 0.74),
                           hip: CGPoint(x: 0.35, y: 0.87),
                           knee: CGPoint(x: 0.64, y: 0.86),
                           foot: CGPoint(x: 0.47, y: 1.10)),
            cycle: 2.4,
            // In front of the toes, where the face goes at the bottom — not between the legs.
            props: [.floor(y: 1.10), .phone(x: 0.63)],
            cues: [L("Toes just behind the phone."),
                   L("Squat until your hips are at least level with your knees, then stand up tall."),
                   L("Look wherever you like.")],
            placement: L("Phone just in front of your toes, where your face is at the bottom; look wherever you like."),
            keyCue: L("Squat until your hips are at least level with your knees, then stand up tall."),
            modelName: "demo_squat")
    }

    /// A hold, so `start` and `end` differ only by a breath — enough to show
    /// the figure is alive without suggesting a movement.
    static var plank: ExerciseDemo {
        let held = StickPose(head: CGPoint(x: 0.20, y: 0.67),
                             neck: CGPoint(x: 0.36, y: 0.715),
                             shoulder: CGPoint(x: 0.32, y: 0.70),
                             elbow: CGPoint(x: 0.32, y: 0.95),
                             hand: CGPoint(x: 0.10, y: 0.95),
                             hip: CGPoint(x: 0.80, y: 0.815),
                             knee: CGPoint(x: 1.06, y: 0.878),
                             foot: CGPoint(x: 1.32, y: 0.94))
        var breathing = held
        breathing.head.y += 0.012
        breathing.neck.y += 0.010
        breathing.shoulder.y += 0.010
        breathing.hip.y += 0.006
        return ExerciseDemo(
            perspective: .side,
            start: held,
            end: breathing,
            cycle: 4.0,
            props: [.floor(y: 0.95), .phone(x: 0.20)],
            cues: [L("Forearms on the floor, elbows under your shoulders, face above the phone."),
                   L("Hold still — the clock runs as long as you hold the position."),
                   L("Shoulders, hips and heels stay in one line.")],
            placement: L("Forearms on either side of the phone, face right above it."),
            keyCue: L("Shoulders, hips and heels stay in one line."),
            modelName: "demo_plank")
    }
}

extension StickPose {
    /// Every joint, for measuring the pose.
    var joints: [CGPoint] { [head, neck, shoulder, elbow, hand, hip, knee, foot] }
}

extension ExerciseDemo {
    /// Head radius, derived from the length of the whole figure rather than
    /// fixed, so a pull-up and a push-up — drawn at completely different
    /// scales — still look like the same person. An adult is about 7.5 heads
    /// tall, hence fifteen radii.
    var headRadius: Double {
        let spine = [start.head, start.neck, start.hip, start.knee, start.foot]
        let length = zip(spine, spine.dropFirst())
            .reduce(0.0) { $0 + hypot($1.1.x - $1.0.x, $1.1.y - $1.0.y) }
        return length / 15
    }

    /// Where a joint on the far side of the body is drawn.
    ///
    /// Lives here rather than in the renderer because `bounds` has to measure
    /// those limbs too — otherwise the far leg hangs off the edge of the view.
    func farSide(of joint: CGPoint, in pose: StickPose) -> CGPoint {
        switch perspective {
        case .front:
            // Head-on, the other side of the body is the mirror image.
            return CGPoint(x: 2 * pose.neck.x - joint.x, y: joint.y)
        case .side:
            // From the side, it sits a little behind. Scaled off the head so
            // the offset reads as depth at any size rather than as a slip of
            // the pen on the wider movements.
            return CGPoint(x: joint.x + headRadius * 0.55, y: joint.y - headRadius * 0.25)
        }
    }

    /// The area the movement actually occupies.
    ///
    /// The renderer maps *this* onto the view rather than a fixed square: a
    /// push-up is wide and flat, and a fixed square would draw it tiny inside a
    /// lot of empty space. The floor and the bar only contribute their height —
    /// they are stretched to whatever the final width turns out to be.
    /// Height of the floor the phone lies on.
    var floorY: Double {
        for case .floor(let y) in props { return y }
        return 1
    }

    var bounds: CGRect {
        var minX = Double.infinity, minY = Double.infinity
        var maxX = -Double.infinity, maxY = -Double.infinity
        func include(_ p: CGPoint, pad: Double = 0) {
            minX = min(minX, p.x - pad); maxX = max(maxX, p.x + pad)
            minY = min(minY, p.y - pad); maxY = max(maxY, p.y + pad)
        }
        for pose in [start, end] {
            for joint in pose.joints {
                include(joint)
                include(farSide(of: joint, in: pose))
            }
            include(pose.head, pad: headRadius)
        }
        for prop in props {
            switch prop {
            case .floor(let y), .bar(let y):
                minY = min(minY, y); maxY = max(maxY, y)
            case .phone(let x):
                include(CGPoint(x: x, y: floorY), pad: headRadius * 1.1)
            }
        }
        let pad = headRadius * 0.5
        return CGRect(x: minX - pad, y: minY - pad,
                      width: (maxX - minX) + 2 * pad, height: (maxY - minY) + 2 * pad)
    }

    /// Width over height of `bounds`, clamped so switching exercises does not
    /// make the surrounding layout jump around.
    var aspectRatio: Double {
        let box = bounds
        return min(max(box.width / box.height, 0.7), 2.2)
    }
}
