"""Synthesise the pull-up animation Mixamo does not have, and export it as USDZ.

Mixamo's catalogue has no usable pull-up, so this poses the same X Bot rig by
hand. Three things make that tractable without a 3D editor:

  * poses are given as *world directions* per bone ("the upper arm points up
    and slightly out") rather than as local Euler angles, which are impossible
    to reason about blind;
  * the arms are solved analytically to a fixed grip rather than with an IK
    constraint — at full hang the chain is exactly straight, the degenerate
    case where IK flips the elbow and the wrists visibly snap;
  * the bar hangs *in front of* the body, so the head passes behind it on the
    way up, which is what the movement looks like from the front.

Run:
    Blender --background --factory-startup \
        --python tools/build_pullup_usdz.py -- \
        --character "~/Downloads/cindy-mixamo/X Bot.fbx" \
        --out Cindy/Resources/demo_pullup.usdz
"""
import argparse
import math
import os
import sys

import bpy
from mathutils import Matrix, Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_exercise_usdz as common  # noqa: E402

PREFIX = "mixamorig:"
# Frame 1 hanging, 31 chin over the bar, 61 hanging again — one 2-second cycle.
TOP_FRAME = 31
END_FRAME = 61
# All as fractions of shoulder-to-wrist length.
PULL_FRACTION = 0.74   # how far the hips travel upwards
FORWARD_REACH = 0.03   # how far in front of the shoulders the bar hangs at rest
LEAN_BACK = 0.34       # how far the body travels backwards on the way up
GRIP_SPREAD = 0.30     # half the distance between the hands
FINGERS = ("Index", "Middle", "Ring", "Pinky")


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--character", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--preview")
    parser.add_argument("--decimate", type=float, default=0.25)
    parser.add_argument("--size", type=float, default=1.7)
    args = parser.parse_args(argv)
    for field in ("character", "out", "preview"):
        if getattr(args, field):
            setattr(args, field, os.path.expanduser(getattr(args, field)))
    return args


def aim(pose_bone, direction):
    """Rotate a pose bone so it points along `direction` in armature space.

    Parents must be posed first — this reads the bone's *current* direction,
    which already includes whatever the chain above it is doing.
    """
    bpy.context.view_layer.update()
    target = Vector(direction).normalized()
    current = (pose_bone.tail - pose_bone.head).normalized()
    rotation = current.rotation_difference(target)
    matrix = pose_bone.matrix.copy()
    location = matrix.translation.copy()
    matrix = rotation.to_matrix().to_4x4() @ matrix
    matrix.translation = location
    pose_bone.matrix = matrix
    bpy.context.view_layer.update()


def orient(pose_bone, direction, up):
    """Point a bone along `direction` *and* fix its roll about that axis.

    `aim` only sets where a bone points; a hand also has to be turned the right
    way round its own length, or the fingers curl off sideways instead of
    closing over the bar.
    """
    bpy.context.view_layer.update()
    y_axis = Vector(direction).normalized()
    x_axis = Vector(up).cross(y_axis)
    if x_axis.length < 1e-6:
        x_axis = Vector((1.0, 0.0, 0.0))
    x_axis.normalize()
    z_axis = x_axis.cross(y_axis)
    matrix = Matrix((x_axis, y_axis, z_axis)).transposed().to_4x4()
    matrix.translation = pose_bone.matrix.translation
    pose_bone.matrix = matrix
    bpy.context.view_layer.update()


def curl_fingers(bones, side, amount=1.15, thumb=0.5):
    """Close the hand around the bar.

    With `automatic_bone_orientation` every bone runs along its own Y, so a
    curl is a rotation about local X — the one joint angle that is safe to set
    directly instead of aiming in world space.
    """
    for finger in FINGERS:
        for segment in (1, 2, 3):
            name = f"{PREFIX}{side}Hand{finger}{segment}"
            if name in bones:
                bones[name].rotation_mode = 'XYZ'
                bones[name].rotation_euler.x = amount
    for segment in (1, 2, 3):
        name = f"{PREFIX}{side}HandThumb{segment}"
        if name in bones:
            bones[name].rotation_mode = 'XYZ'
            bones[name].rotation_euler.x = thumb


def pose_body(armature):
    """Everything that does not change during the movement.

    Armature space here is Mixamo's own: +Y up, +X to the character's left,
    +Z forward. Root to tip, so each bone sees its parent already placed.
    """
    bones = armature.pose.bones
    for side, outward in (("Left", 1), ("Right", -1)):
        # Hanging pulls the shoulders up towards the ears.
        aim(bones[f"{PREFIX}{side}Shoulder"], (outward * 0.9, 0.45, 0))
        # Legs hang straight with the toes pointed, knees very slightly bent.
        aim(bones[f"{PREFIX}{side}UpLeg"], (outward * 0.06, -1.0, -0.04))
        aim(bones[f"{PREFIX}{side}Leg"], (outward * 0.04, -1.0, 0.06))
        aim(bones[f"{PREFIX}{side}Foot"], (0, -0.55, 1.0))
        curl_fingers(bones, side)


def arm_span(armature):
    """Shoulder-to-wrist length in armature units, from the rest pose."""
    bones = armature.data.bones
    upper = (bones[f"{PREFIX}LeftArm"].tail_local - bones[f"{PREFIX}LeftArm"].head_local).length
    fore = (bones[f"{PREFIX}LeftForeArm"].tail_local
            - bones[f"{PREFIX}LeftForeArm"].head_local).length
    return upper + fore


def bone_lengths(armature):
    bones = armature.data.bones
    upper = (bones[f"{PREFIX}LeftArm"].tail_local - bones[f"{PREFIX}LeftArm"].head_local).length
    fore = (bones[f"{PREFIX}LeftForeArm"].tail_local
            - bones[f"{PREFIX}LeftForeArm"].head_local).length
    return upper, fore


def grip_points(armature):
    """Where the hands hold the bar, in armature space — fixed for the whole
    movement, because a grip does not slide along the bar.

    Placed in front of the body, not in its plane: on a real pull-up the bar is
    ahead of the face, so the head passes behind it. With the bar in the body's
    plane the head ends up in front of it, which reads as wrong from the front.
    """
    bones = armature.data.bones
    reach = arm_span(armature)
    points = {}
    for side, outward in (("Left", 1), ("Right", -1)):
        shoulder = bones[f"{PREFIX}{side}Arm"].head_local
        # Normalise the direction before scaling: adding the components
        # separately put the grip 57 units from a 56-unit arm, so the hand
        # never actually reached the bar.
        direction = Vector((outward * GRIP_SPREAD, 0.95, FORWARD_REACH)).normalized()
        points[side] = shoulder + direction * (reach * 0.97)
    return points


def pull_phase(t):
    """0 hanging, 1 at the top, over one seamless cycle.

    Asymmetric on purpose: a rep is pulled faster than it is lowered, and a
    plain cosine makes the movement look like it is floating.
    """
    rise = 0.42
    u = t / rise if t < rise else 1.0 - (t - rise) / (1.0 - rise)
    return u * u * (3.0 - 2.0 * u)


def solve_elbow(shoulder, hand, upper, fore, hint):
    """Where the elbow goes for a two-bone arm reaching from `shoulder` to `hand`.

    Deliberately not an IK constraint: at full hang the chain is exactly
    straight, the degenerate case where a solver has no stable elbow plane and
    flips. Solving it directly keeps the arm just short of straight and pins
    the elbow to whichever side `hint` points at.
    """
    to_hand = hand - shoulder
    distance = to_hand.length
    reach = (upper + fore) * 0.995
    if distance > reach:
        to_hand *= reach / distance
        distance = reach
    cosine = (upper * upper + distance * distance - fore * fore) / (2 * upper * distance)
    angle = math.acos(max(-1.0, min(1.0, cosine)))
    direction = to_hand.normalized()
    axis = direction.cross(Vector(hint).normalized())
    if axis.length < 1e-6:
        axis = Vector((1.0, 0.0, 0.0))
    axis.normalize()
    return shoulder + (Matrix.Rotation(angle, 4, axis) @ direction) * upper


def bar_through(armature, grips):
    """A bar through the grip points.

    Without it the movement is unreadable — a figure raising its arms, not a
    pull-up. The other three exercises carry their own context; this one does
    not.
    """
    # The grip points are wrist positions; the bar itself rides slightly lower,
    # inside the curl of the fingers rather than through the wrist joint.
    drop = Vector((0.0, -0.03, 0.0))
    ends = [armature.matrix_world @ (grips[side] + drop * 100) for side in ("Left", "Right")]
    centre = (ends[0] + ends[1]) / 2
    grip_width = (ends[0] - ends[1]).length

    # World units are metres here (the armature carries Mixamo's 0.01 scale),
    # so a 22 mm bar is radius 0.022 — not 2.2.
    bpy.ops.mesh.primitive_cylinder_add(vertices=12, radius=0.022,
                                        depth=grip_width * 2.4, location=centre)
    bar = bpy.context.active_object
    bar.name = "Bar"
    bar.rotation_euler = (ends[0] - ends[1]).to_track_quat('Z', 'Y').to_euler()

    material = bpy.data.materials.new("Bar")
    material.use_nodes = True
    bsdf = material.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (0.20, 0.20, 0.22, 1.0)
    bsdf.inputs["Roughness"].default_value = 0.4
    bsdf.inputs["Metallic"].default_value = 0.8
    bar.data.materials.append(material)
    return bar


def animate(armature, scene, grips):
    """Pose every frame: hips on an eased cycle, arms solved to the fixed grip.

    One cosine drives everything, so frame 1 and frame `END_FRAME` are the same
    pose and the loop is seamless.
    """
    bones = armature.pose.bones
    upper, fore = bone_lengths(armature)
    hips = bones[f"{PREFIX}Hips"]
    bpy.context.view_layer.update()
    rest_hips = hips.matrix.translation.copy()
    lift = arm_span(armature) * PULL_FRACTION
    lean = arm_span(armature) * LEAN_BACK

    animated = [hips]
    for side in ("Left", "Right"):
        animated += [bones[f"{PREFIX}{side}Shoulder"], bones[f"{PREFIX}{side}Arm"],
                     bones[f"{PREFIX}{side}ForeArm"], bones[f"{PREFIX}{side}Hand"]]
    for bone in animated:
        bone.rotation_mode = 'QUATERNION'

    for frame in range(1, END_FRAME + 1):
        phase = pull_phase((frame - 1) / (END_FRAME - 1))
        matrix = hips.matrix.copy()
        # Up and *back*: you start hanging straight under the bar and finish
        # leaning away from it, which is what puts the head behind the bar.
        matrix.translation = rest_hips + Vector((0.0, lift * phase, -lean * phase))
        hips.matrix = matrix
        bpy.context.view_layer.update()

        for side, outward in (("Left", 1), ("Right", -1)):
            arm = bones[f"{PREFIX}{side}Arm"]
            forearm = bones[f"{PREFIX}{side}ForeArm"]
            hand = grips[side]
            # Elbows start tucked under the bar and flare out and down as the
            # body rises, the way they do when someone actually pulls up.
            hint = Vector((outward * (0.8 + 1.2 * phase), -0.3 - 0.5 * phase, 0.2))
            elbow = solve_elbow(arm.head, hand, upper, fore, hint)
            aim(arm, elbow - arm.head)
            aim(forearm, hand - forearm.head)
            # Overhand grip: the hand runs forward over the bar with the
            # back of the hand up, so the curled fingers close underneath it.
            orient(bones[f"{PREFIX}{side}Hand"],
                   direction=(outward * 0.10, -0.35, 1.0),
                   up=(0.0, 1.0, 0.35))

        for bone in animated:
            bone.keyframe_insert("rotation_quaternion", frame=frame)
            # Location too, not just rotation: `aim` works through the
            # armature-space matrix, so a bone's *local* offset changes
            # whenever its parent turns. Keying only the rotation replayed the
            # arms against a stale offset and the hands drifted off the bar.
            bone.keyframe_insert("location", frame=frame)

    scene.frame_start, scene.frame_end = 1, END_FRAME


def report(armature, scene, grips):
    """Sanity check: where the body is relative to the bar at the key frames."""
    for frame in (1, TOP_FRAME):
        scene.frame_set(frame)
        bpy.context.view_layer.update()
        chin = armature.pose.bones[PREFIX + "Head"].head
        forearm_tail = armature.pose.bones[PREFIX + "LeftForeArm"].tail
        tip = armature.pose.bones.get(PREFIX + "LeftHandMiddle3")
        if tip is not None:
            print(f"  GRIP frame {frame}: fingertip_y={tip.tail.y:.1f} "
                  f"wrist_y={forearm_tail.y:.1f} bar_y={grips['Left'].y:.1f} "
                  f"(fingertip should be below the bar, wrist above)")
        hand_head = armature.pose.bones[PREFIX + "LeftHand"].head
        print(f"REPORT frame {frame}: head_y={chin.y:.1f} head_z={chin.z:.1f} "
              f"bar_y={grips['Left'].y:.1f} bar_z={grips['Left'].z:.1f} "
              f"ftail_off={(forearm_tail - grips['Left']).length:.1f} "
              f"hand_vs_ftail={(hand_head - forearm_tail).length:.1f}")


def main():
    args = parse_args()
    bpy.ops.wm.read_factory_settings(use_empty=True)

    armature, meshes, _ = common.import_fbx(args.character)
    if not meshes:
        raise SystemExit("character FBX has no mesh")

    scene = bpy.context.scene
    scene.render.fps = 30
    bpy.context.view_layer.objects.active = armature
    bpy.ops.object.mode_set(mode='POSE')
    pose_body(armature)

    grips = grip_points(armature)
    if armature.animation_data is None:
        armature.animation_data_create()
    if armature.animation_data.action is None:
        armature.animation_data.action = bpy.data.actions.new("PullUp")
    animate(armature, scene, grips)
    report(armature, scene, grips)
    bpy.ops.object.mode_set(mode='OBJECT')

    bar = bar_through(armature, grips)
    common.decimate(meshes, args.decimate)
    common.normalise_scale(armature, meshes, armature.animation_data.action)
    print(f"FRAMES: {scene.frame_start}-{scene.frame_end}")
    # The bar is part of the picture, so it has to be inside the framing too.
    common.fit_to_origin(scene, meshes + [bar], args.size)

    bpy.ops.wm.usd_export(
        filepath=args.out, export_animation=True, export_armatures=True,
        export_materials=True, triangulate_meshes=True,
        convert_orientation=True, export_global_up_selection='Y',
        export_global_forward_selection='NEGATIVE_Z',
        convert_scene_units='METERS', root_prim_path='/root')
    print("EXPORTED:", args.out, f"({os.path.getsize(args.out) // 1024} KB)")

    if args.preview:
        common.render_preview(args.preview, scene)


if __name__ == "__main__":
    main()
