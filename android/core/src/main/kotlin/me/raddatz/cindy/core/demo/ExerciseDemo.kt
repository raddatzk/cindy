package me.raddatz.cindy.core.demo

import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.Point
import me.raddatz.cindy.core.Rect
import kotlin.math.hypot

/**
 * One frame of the stick figure: joint positions in the unit square, with y growing downwards
 * so the values can be used as view coordinates directly.
 *
 * Only one arm and one leg are stored. The renderer derives the second pair from
 * [StickPerspective], which keeps a pose to eight points a human can still reason about.
 */
data class StickPose(
    val head: Point,
    val neck: Point,
    val shoulder: Point,
    val elbow: Point,
    val hand: Point,
    val hip: Point,
    val knee: Point,
    val foot: Point,
) {
    /** Linear blend towards [other]; `t` of 0 is this pose, 1 is [other]. */
    fun blended(other: StickPose, t: Double): StickPose = StickPose(
        head = lerp(head, other.head, t),
        neck = lerp(neck, other.neck, t),
        shoulder = lerp(shoulder, other.shoulder, t),
        elbow = lerp(elbow, other.elbow, t),
        hand = lerp(hand, other.hand, t),
        hip = lerp(hip, other.hip, t),
        knee = lerp(knee, other.knee, t),
        foot = lerp(foot, other.foot, t),
    )

    /** Every joint, for measuring the pose. */
    val joints: List<Point> get() = listOf(head, neck, shoulder, elbow, hand, hip, knee, foot)

    private companion object {
        fun lerp(a: Point, b: Point, t: Double): Point = Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }
}

/** How the figure is seen, which decides where the second arm and leg go. */
enum class StickPerspective {
    /** Seen from the side: the far limbs sit slightly behind, drawn faded. */
    SIDE,

    /** Seen head-on: the far limbs are the mirror image across the spine. */
    FRONT,
}

/** Scenery drawn behind the figure. Coordinates share the pose's unit square. */
sealed interface StickProp {
    data class Floor(val y: Double) : StickProp
    data class Bar(val y: Double) : StickProp

    /**
     * The phone lying flat on the floor, screen up, directly under the face — the same spot for
     * every exercise, no stand or weight plate needed. Sits on the [Floor] prop.
     *
     * [betweenHands] draws it behind the near arm and in front of the far one, which is where it
     * lies when the hands are planted on either side of it. Off, it is drawn over the whole
     * figure, so a plank's forearms along the floor cannot hide it.
     */
    data class Phone(val x: Double, val betweenHands: Boolean = false) : StickProp
}

/** Typed replacement for the localized demo texts; the English source string is on each case. */
enum class DemoCue {
    /** "Start hanging with straight arms." */
    PULL_UP_START_HANGING,

    /** "Pull until your chin is above the bar, then lower all the way down again." */
    PULL_UP_CHIN_ABOVE_BAR,

    /** "Phone on the floor under the bar, screen facing up." */
    PULL_UP_PHONE_UNDER_BAR,

    /** "Hang from the bar right above the phone." */
    PULL_UP_PLACEMENT,

    /** "Start at the top with straight arms, face above the phone." */
    PUSH_UP_START_AT_TOP,

    /** "Lower until your chest is just off the floor, then press back up." */
    PUSH_UP_CHEST_OFF_FLOOR,

    /** "Keep your body one straight line from shoulders to heels." */
    PUSH_UP_STRAIGHT_LINE,

    /** "With straight arms, your face is right above the phone." */
    PUSH_UP_PLACEMENT,

    /** "Toes just behind the phone." */
    SQUAT_TOES_BEHIND_PHONE,

    /** "Squat until your hips are at least level with your knees, then stand up tall." */
    SQUAT_DEPTH,

    /** "Look wherever you like." */
    SQUAT_LOOK_ANYWHERE,

    /** "Phone just in front of your toes, where your face is at the bottom; look wherever you like." */
    SQUAT_PLACEMENT,

    /** "Forearms on the floor, elbows under your shoulders, face above the phone." */
    PLANK_FOREARMS,

    /** "Hold still — the clock runs as long as you hold the position." */
    PLANK_HOLD_STILL,

    /** "Shoulders, hips and heels stay in one line." */
    PLANK_ONE_LINE,

    /** "Forearms on either side of the phone, face right above it." */
    PLANK_PLACEMENT,
}

/**
 * Everything needed to show one exercise: the movement itself, the scenery around it, and the
 * points that decide whether Cindy can count the reps.
 */
data class ExerciseDemo(
    val perspective: StickPerspective,
    /** The position the exercise starts and ends in — also the one calibration asks you to hold still before the countdown. */
    val start: StickPose,
    /** The far end of the movement. */
    val end: StickPose,
    /** Seconds for one full cycle, out to [end] and back. */
    val cycle: Double,
    val props: List<StickProp>,
    /** Short form cues, most important first. */
    val cues: List<DemoCue>,
    /** Where the athlete is relative to the phone, which stays in one spot all workout. */
    val placement: DemoCue,
    /** The one cue that defines a full rep (or a good hold), for the intro. */
    val keyCue: DemoCue,
) {
    /**
     * Head radius, derived from the length of the whole figure rather than fixed, so a pull-up
     * and a push-up — drawn at completely different scales — still look like the same person. An
     * adult is about 7.5 heads tall, hence fifteen radii.
     */
    val headRadius: Double
        get() {
            val spine = listOf(start.head, start.neck, start.hip, start.knee, start.foot)
            val length = spine.zipWithNext().fold(0.0) { sum, (a, b) -> sum + hypot(b.x - a.x, b.y - a.y) }
            return length / 15
        }

    /**
     * Where a joint on the far side of the body is drawn.
     *
     * Lives here rather than in the renderer because [bounds] has to measure those limbs too —
     * otherwise the far leg hangs off the edge of the view.
     */
    fun farSide(joint: Point, pose: StickPose): Point = when (perspective) {
        // Head-on, the other side of the body is the mirror image.
        StickPerspective.FRONT -> Point(2 * pose.neck.x - joint.x, joint.y)
        // From the side, it sits a little behind. Scaled off the head so the offset reads as
        // depth at any size rather than as a slip of the pen on the wider movements.
        StickPerspective.SIDE -> Point(joint.x + headRadius * 0.55, joint.y - headRadius * 0.25)
    }

    /** Height of the floor the phone lies on. */
    val floorY: Double
        get() = props.firstNotNullOfOrNull { (it as? StickProp.Floor)?.y } ?: 1.0

    /**
     * The area the movement actually occupies.
     *
     * The renderer maps *this* onto the view rather than a fixed square: a push-up is wide and
     * flat, and a fixed square would draw it tiny inside a lot of empty space. The floor and the
     * bar only contribute their height — they are stretched to whatever the final width turns out
     * to be.
     */
    val bounds: Rect
        get() {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            fun include(p: Point, pad: Double = 0.0) {
                minX = minOf(minX, p.x - pad); maxX = maxOf(maxX, p.x + pad)
                minY = minOf(minY, p.y - pad); maxY = maxOf(maxY, p.y + pad)
            }
            for (pose in listOf(start, end)) {
                for (joint in pose.joints) {
                    include(joint)
                    include(farSide(joint, pose))
                }
                include(pose.head, pad = headRadius)
            }
            for (prop in props) {
                when (prop) {
                    is StickProp.Floor -> { minY = minOf(minY, prop.y); maxY = maxOf(maxY, prop.y) }
                    is StickProp.Bar -> { minY = minOf(minY, prop.y); maxY = maxOf(maxY, prop.y) }
                    is StickProp.Phone -> include(Point(prop.x, floorY), pad = headRadius * 1.1)
                }
            }
            val pad = headRadius * 0.5
            return Rect(minX - pad, minY - pad, (maxX - minX) + 2 * pad, (maxY - minY) + 2 * pad)
        }

    /** Width over height of [bounds], clamped so switching exercises does not make the surrounding layout jump around. */
    val aspectRatio: Double
        get() {
            val box = bounds
            return minOf(maxOf(box.width / box.height, 0.7), 2.2)
        }

    // The four movements
    //
    // Coordinates are not confined to 0…1: a push-up is far wider than it is tall. `bounds`
    // measures whatever the pose actually spans and the renderer fits that, so each movement is
    // laid out in whichever proportions are true to it. Limb lengths follow rough human ratios —
    // arm ≈ 1.2 × torso, leg ≈ 1.5 × torso — which is what makes a handful of line segments read
    // as a body.
    companion object {
        /** Seen head-on, hanging from the bar. The hands stay put; everything else travels upwards until the chin clears the bar. */
        val pullUp: ExerciseDemo = ExerciseDemo(
            perspective = StickPerspective.FRONT,
            start = StickPose(
                head = Point(0.50, 0.35),
                neck = Point(0.50, 0.44),
                shoulder = Point(0.58, 0.44),
                elbow = Point(0.62, 0.29),
                hand = Point(0.63, 0.14),
                hip = Point(0.50, 0.69),
                knee = Point(0.53, 0.89),
                foot = Point(0.52, 1.08),
            ),
            end = StickPose(
                head = Point(0.50, 0.07),
                neck = Point(0.50, 0.16),
                shoulder = Point(0.58, 0.16),
                elbow = Point(0.72, 0.30),
                hand = Point(0.63, 0.14),
                hip = Point(0.50, 0.41),
                knee = Point(0.53, 0.61),
                foot = Point(0.52, 0.80),
            ),
            cycle = 2.6,
            props = listOf(StickProp.Bar(0.14), StickProp.Floor(1.12), StickProp.Phone(0.50)),
            cues = listOf(DemoCue.PULL_UP_START_HANGING, DemoCue.PULL_UP_CHIN_ABOVE_BAR, DemoCue.PULL_UP_PHONE_UNDER_BAR),
            placement = DemoCue.PULL_UP_PLACEMENT,
            keyCue = DemoCue.PULL_UP_CHIN_ABOVE_BAR,
        )

        /**
         * Seen from the side. Shoulders, hips and heels stay one straight line; only the elbow angle
         * changes. The hands are planted a little ahead of the shoulders, on either side of the
         * phone, so the face is right above it.
         */
        val pushUp: ExerciseDemo = ExerciseDemo(
            perspective = StickPerspective.SIDE,
            start = StickPose(
                head = Point(0.13, 0.605),
                neck = Point(0.26, 0.645),
                shoulder = Point(0.22, 0.63),
                elbow = Point(0.185, 0.79),
                hand = Point(0.15, 0.95),
                hip = Point(0.72, 0.78),
                knee = Point(1.00, 0.864),
                foot = Point(1.28, 0.95),
            ),
            // Chest just off the floor, face still a gap above the phone lying under it.
            end = StickPose(
                head = Point(0.13, 0.80),
                neck = Point(0.26, 0.865),
                shoulder = Point(0.22, 0.86),
                elbow = Point(0.36, 0.92),
                hand = Point(0.15, 0.95),
                hip = Point(0.72, 0.905),
                knee = Point(1.00, 0.926),
                foot = Point(1.28, 0.95),
            ),
            cycle = 2.2,
            // Under the face and between the hands: the near arm is drawn over it.
            props = listOf(StickProp.Floor(0.95), StickProp.Phone(0.14, betweenHands = true)),
            cues = listOf(DemoCue.PUSH_UP_START_AT_TOP, DemoCue.PUSH_UP_CHEST_OFF_FLOOR, DemoCue.PUSH_UP_STRAIGHT_LINE),
            placement = DemoCue.PUSH_UP_PLACEMENT,
            keyCue = DemoCue.PUSH_UP_CHEST_OFF_FLOOR,
        )

        /** Seen from the side so the depth of the squat is actually visible. */
        val squat: ExerciseDemo = ExerciseDemo(
            perspective = StickPerspective.SIDE,
            start = StickPose(
                head = Point(0.50, 0.10),
                neck = Point(0.50, 0.20),
                shoulder = Point(0.50, 0.23),
                elbow = Point(0.53, 0.42),
                hand = Point(0.54, 0.61),
                hip = Point(0.49, 0.52),
                knee = Point(0.49, 0.81),
                foot = Point(0.47, 1.10),
            ),
            // Torso leans forward at the bottom, bringing the face over the phone in front of the toes.
            end = StickPose(
                head = Point(0.60, 0.50),
                neck = Point(0.55, 0.59),
                shoulder = Point(0.55, 0.63),
                elbow = Point(0.74, 0.68),
                hand = Point(0.90, 0.74),
                hip = Point(0.35, 0.87),
                knee = Point(0.64, 0.86),
                foot = Point(0.47, 1.10),
            ),
            cycle = 2.4,
            // In front of the toes, where the face goes at the bottom — not between the legs.
            props = listOf(StickProp.Floor(1.10), StickProp.Phone(0.63)),
            cues = listOf(DemoCue.SQUAT_TOES_BEHIND_PHONE, DemoCue.SQUAT_DEPTH, DemoCue.SQUAT_LOOK_ANYWHERE),
            placement = DemoCue.SQUAT_PLACEMENT,
            keyCue = DemoCue.SQUAT_DEPTH,
        )

        /** A hold, so [start] and [end] differ only by a breath — enough to show the figure is alive without suggesting a movement. */
        val plank: ExerciseDemo = run {
            val held = StickPose(
                head = Point(0.20, 0.67),
                neck = Point(0.36, 0.715),
                shoulder = Point(0.32, 0.70),
                elbow = Point(0.32, 0.95),
                hand = Point(0.10, 0.95),
                hip = Point(0.80, 0.815),
                knee = Point(1.06, 0.878),
                foot = Point(1.32, 0.94),
            )
            val breathing = held.copy(
                head = held.head.copy(y = held.head.y + 0.012),
                neck = held.neck.copy(y = held.neck.y + 0.010),
                shoulder = held.shoulder.copy(y = held.shoulder.y + 0.010),
                hip = held.hip.copy(y = held.hip.y + 0.006),
            )
            ExerciseDemo(
                perspective = StickPerspective.SIDE,
                start = held,
                end = breathing,
                cycle = 4.0,
                props = listOf(StickProp.Floor(0.95), StickProp.Phone(0.20)),
                cues = listOf(DemoCue.PLANK_FOREARMS, DemoCue.PLANK_HOLD_STILL, DemoCue.PLANK_ONE_LINE),
                placement = DemoCue.PLANK_PLACEMENT,
                keyCue = DemoCue.PLANK_ONE_LINE,
            )
        }
    }
}

/** The demo for this exercise. */
val Exercise.demo: ExerciseDemo
    get() = when (this) {
        Exercise.PULL_UP -> ExerciseDemo.pullUp
        Exercise.PUSH_UP -> ExerciseDemo.pushUp
        Exercise.SQUAT -> ExerciseDemo.squat
        Exercise.PLANK -> ExerciseDemo.plank
    }
