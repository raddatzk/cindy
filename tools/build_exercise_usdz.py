"""Turn Mixamo FBX exports into the animated USDZ files the exercise demos play.

Mixamo gives you two kinds of download: a character (mesh + rig, T-pose) and an
animation ("without skin" — the same rig, animated, no mesh). Both use identical
bone names, so the animation's action can simply be handed to the character's
armature. That is the whole trick; everything else here is clean-up.

Run headless, once per exercise:

    Blender --background --factory-startup \
        --python tools/build_exercise_usdz.py -- \
        --animation "~/Downloads/cindy-mixamo/Push Up.fbx" \
        --character "~/Downloads/cindy-mixamo/Y Bot.fbx" \
        --out Cindy/Resources/demo_pushup.usdz

Without --character it builds a tube puppet from the skeleton instead, which is
enough to check the pipeline (and is a passable stand-in on its own).
"""
import argparse
import math
import os
import sys

import bpy
from mathutils import Vector

RING = 8            # segments around each limb tube
TUBE_RADIUS = 0.04  # in armature units
FINGERS = ("Thumb", "Index", "Middle", "Ring", "Pinky")


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--animation", required=True)
    parser.add_argument("--character")
    parser.add_argument("--out", required=True)
    parser.add_argument("--preview", help="also render a PNG of the mid-animation frame")
    parser.add_argument("--decimate", type=float, default=0.25,
                        help="fraction of faces to keep on the character mesh (1 = untouched)")
    parser.add_argument("--size", type=float, default=1.7,
                        help="largest extent of the finished model, in metres")
    args = parser.parse_args(argv)
    for field in ("animation", "character", "out", "preview"):
        value = getattr(args, field)
        if value:
            setattr(args, field, os.path.expanduser(value))
    return args


def import_fbx(path):
    """Import one Mixamo FBX and return (armature, meshes, action)."""
    before = set(bpy.data.objects)
    actions_before = set(bpy.data.actions)
    # Leaf bones are Mixamo's "_End" tips: they carry no animation and only
    # inflate the joint list that ships in the USD.
    bpy.ops.import_scene.fbx(filepath=path, ignore_leaf_bones=True,
                             automatic_bone_orientation=True)
    added = [o for o in bpy.data.objects if o not in before]
    armatures = [o for o in added if o.type == 'ARMATURE']
    if len(armatures) != 1:
        raise SystemExit(f"{os.path.basename(path)}: expected 1 armature, found {len(armatures)}")
    actions = [a for a in bpy.data.actions if a not in actions_before]
    return armatures[0], [o for o in added if o.type == 'MESH'], (actions[0] if actions else None)


def animated_bounds(scene, meshes):
    """World-space bounds of the figure across the whole animation.

    Uses each object's evaluated bounding box rather than its vertices — eight
    corners per frame instead of ten thousand, and the result only has to be
    good enough to frame a camera.
    """
    low = Vector((math.inf,) * 3)
    high = Vector((-math.inf,) * 3)
    for frame in range(scene.frame_start, scene.frame_end + 1):
        scene.frame_set(frame)
        deps = bpy.context.evaluated_depsgraph_get()
        for obj in meshes:
            evaluated = obj.evaluated_get(deps)
            for corner in evaluated.bound_box:
                point = evaluated.matrix_world @ Vector(corner)
                for axis in range(3):
                    low[axis] = min(low[axis], point[axis])
                    high[axis] = max(high[axis], point[axis])
    return low, high


def fit_to_origin(scene, meshes, target):
    """Centre the movement on the origin and scale it to a fixed size.

    Framing cannot be done in the app: `visualBounds` there reports the USDZ's
    rest pose, which says nothing about where the *animation* goes — a squat
    with the arms thrown forward reaches well outside the T-pose it was scaled
    by, and ends up cropped. Here the whole frame range is known, so every
    exercise can be normalised to the same extent and a fixed camera frames all
    of them.
    """
    low, high = animated_bounds(scene, meshes)
    centre = (low + high) / 2
    extent = max(high - low)
    scale = target / extent

    root = bpy.data.objects.new("Fit", None)
    bpy.context.collection.objects.link(root)
    root.scale = (scale, scale, scale)
    root.location = -centre * scale
    for obj in list(scene.objects):
        if obj is not root and obj.parent is None:
            obj.parent = root
    print(f"FIT: extent {extent:.3f} -> {target}, centre {tuple(round(v, 2) for v in centre)}")
    scene.frame_set(scene.frame_start)
    return root


def decimate(meshes, ratio):
    """Thin out the character mesh.

    Mixamo ships ~25k faces, which is a couple of megabytes of geometry and
    skin weights per exercise. The demo is a few hundred points wide on a
    phone, so most of that detail never resolves on screen.
    """
    if ratio >= 1.0:
        return
    for obj in meshes:
        before = len(obj.data.polygons)
        modifier = obj.modifiers.new("Decimate", 'DECIMATE')
        modifier.ratio = ratio
        bpy.context.view_layer.objects.active = obj
        # Ahead of the armature modifier, so it thins the rest mesh and leaves
        # the skinning to deform whatever is left.
        bpy.ops.object.modifier_move_to_index(modifier=modifier.name, index=0)
        bpy.ops.object.modifier_apply(modifier=modifier.name)
        print(f"DECIMATED {obj.name}: {before} -> {len(obj.data.polygons)} faces")


def action_fcurves(action):
    """F-curves of an action, on both Blender's slotted (4.4+) and older layouts."""
    if hasattr(action, "fcurves"):
        yield from action.fcurves
        return
    for layer in action.layers:
        for strip in layer.strips:
            for bag in getattr(strip, "channelbags", []):
                yield from bag.fcurves


def normalise_scale(armature, meshes, action):
    """Bake the importer's unit conversion into the data.

    Mixamo FBX is authored in centimetres, and Blender absorbs that as a 0.01
    scale on the armature object rather than in the geometry. The result
    exports as a mesh in metres bound to a skeleton in centimetres: correct
    once skinned, but `visualBounds` on the freshly loaded entity reports the
    raw mesh and comes out a hundred times too small, which is exactly what the
    app uses to size the model.
    """
    factor = armature.scale.x
    if abs(factor - 1.0) < 1e-6:
        return factor
    bpy.ops.object.select_all(action='DESELECT')
    for obj in [armature, *meshes]:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = armature
    bpy.ops.object.transform_apply(location=True, rotation=False, scale=True)
    # Applying the scale rescales the rest pose but leaves the animated bone
    # locations alone, so the hips would shoot off across the room. Scale
    # those curves by the same factor to keep the motion intact.
    for fcurve in action_fcurves(action):
        if not fcurve.data_path.endswith("location"):
            continue
        for key in fcurve.keyframe_points:
            key.co.y *= factor
            key.handle_left.y *= factor
            key.handle_right.y *= factor
    return factor


def is_finger(bone):
    return any(f in bone.name for f in FINGERS)


def build_tube_puppet(arm):
    """A rigid tube per bone, weighted 1:1 to that bone.

    Deterministic and instant — no automatic weights to go wrong — and it still
    produces real UsdSkel skinning, which is what has to survive the export.
    """
    verts, faces, groups = [], [], {}
    for bone in arm.data.bones:
        if is_finger(bone):
            continue
        axis = bone.tail_local - bone.head_local
        if axis.length < 0.02:
            continue
        rotation = axis.to_track_quat('Z', 'Y')
        base = len(verts)
        for end in (0.0, axis.length):
            for i in range(RING):
                angle = 2 * math.pi * i / RING
                offset = Vector((TUBE_RADIUS * math.cos(angle), TUBE_RADIUS * math.sin(angle), end))
                verts.append(bone.head_local + rotation @ offset)
        for i in range(RING):
            j = (i + 1) % RING
            faces.append([base + i, base + j, base + RING + j, base + RING + i])
        faces.append(list(range(base, base + RING))[::-1])
        faces.append(list(range(base + RING, base + 2 * RING)))
        groups[bone.name] = list(range(base, len(verts)))

    mesh = bpy.data.meshes.new("FigureMesh")
    mesh.from_pydata([tuple(v) for v in verts], [], faces)
    mesh.validate()
    mesh.update()
    obj = bpy.data.objects.new("Figure", mesh)
    bpy.context.collection.objects.link(obj)
    for name, indices in groups.items():
        obj.vertex_groups.new(name=name).add(indices, 1.0, 'REPLACE')
    obj.modifiers.new("Armature", 'ARMATURE').object = arm
    # The tubes were built from `head_local`, i.e. in armature space, so the
    # mesh has to sit there exactly — any transform of its own would be applied
    # on top of the bones' and tear the figure apart.
    obj.parent = arm
    obj.matrix_parent_inverse.identity()
    obj.matrix_basis.identity()
    mesh.materials.append(figure_material())
    return obj


def figure_material():
    mat = bpy.data.materials.new("Figure")
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    # Roughly the app's accent colour, so the 3D demo and the stick figure
    # read as the same thing.
    bsdf.inputs["Base Color"].default_value = (0.85, 0.42, 0.05, 1.0)
    bsdf.inputs["Roughness"].default_value = 0.55
    bsdf.inputs["Metallic"].default_value = 0.0
    return mat


def retarget(action, armature):
    """Hand an action from the animation rig to the character rig."""
    if armature.animation_data is None:
        armature.animation_data_create()
    armature.animation_data.action = action
    # Blender 4.4+ wraps actions in slots; bind the first one or the action
    # plays back empty.
    slots = getattr(action, "slots", None)
    if slots:
        armature.animation_data.action_slot = slots[0]


def posed_bounds(scene):
    """World-space bounds of the figure at the current frame."""
    deps = bpy.context.evaluated_depsgraph_get()
    points = []
    for obj in scene.objects:
        if obj.type != 'MESH':
            continue
        evaluated = obj.evaluated_get(deps)
        mesh = evaluated.to_mesh()
        points.extend(evaluated.matrix_world @ v.co for v in mesh.vertices)
        evaluated.to_mesh_clear()
    low = Vector(min(p[i] for p in points) for i in range(3))
    high = Vector(max(p[i] for p in points) for i in range(3))
    return low, high


def render_preview(path, scene):
    """A still from the middle of the movement, to eyeball the result."""
    scene.frame_set((scene.frame_start + scene.frame_end) // 2)
    bpy.context.view_layer.update()
    low, high = posed_bounds(scene)
    centre, size = (low + high) / 2, high - low

    camera_data = bpy.data.cameras.new("Preview")
    camera_data.type = 'ORTHO'
    camera = bpy.data.objects.new("Preview", camera_data)
    bpy.context.collection.objects.link(camera)
    # Look along whichever horizontal axis the figure is *narrowest* on, so a
    # push-up is seen from the side rather than end-on.
    if size.x >= size.y:
        camera.location = (centre.x, centre.y - 6, centre.z)
        camera.rotation_euler = (math.radians(90), 0, 0)
        width = size.x
    else:
        camera.location = (centre.x - 6, centre.y, centre.z)
        camera.rotation_euler = (math.radians(90), 0, math.radians(-90))
        width = size.y
    camera_data.ortho_scale = max(width, size.z) * 1.3
    scene.camera = camera

    light_data = bpy.data.lights.new("Key", type='SUN')
    light_data.energy = 4.0
    light = bpy.data.objects.new("Key", light_data)
    bpy.context.collection.objects.link(light)
    light.rotation_euler = (math.radians(55), 0, math.radians(35))

    scene.render.engine = 'BLENDER_EEVEE'
    scene.render.resolution_x = scene.render.resolution_y = 640
    scene.world = bpy.data.worlds.new("W")
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs[0].default_value = (1, 1, 1, 1)
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    print("PREVIEW:", path)


def main():
    args = parse_args()
    bpy.ops.wm.read_factory_settings(use_empty=True)

    anim_arm, _, action = import_fbx(args.animation)
    if action is None:
        raise SystemExit(f"{os.path.basename(args.animation)} contains no animation")

    if args.character:
        char_arm, meshes, _ = import_fbx(args.character)
        if not meshes:
            raise SystemExit(f"{os.path.basename(args.character)} has no mesh — "
                             "download the character *with* skin")
        retarget(action, char_arm)
        bpy.data.objects.remove(anim_arm, do_unlink=True)
        armature = char_arm
    else:
        armature, meshes = anim_arm, []

    if args.character:
        decimate(meshes, args.decimate)

    factor = normalise_scale(armature, meshes, action)
    if not args.character:
        # Built after normalising, so the tube radius is in metres like
        # everything else.
        build_tube_puppet(armature)
    print(f"UNIT-SCALE: {factor}")

    scene = bpy.context.scene
    scene.frame_start, scene.frame_end = (int(v) for v in action.frame_range)
    print(f"FRAMES: {scene.frame_start}-{scene.frame_end}  FPS: {scene.render.fps}")
    fit_to_origin(scene, meshes or [o for o in scene.objects if o.type == 'MESH'], args.size)

    bpy.ops.wm.usd_export(
        filepath=args.out,
        export_animation=True,
        export_armatures=True,
        export_materials=True,
        triangulate_meshes=True,
        # Blender is Z-up, USDZ is Y-up with -Z forward. Verified against a
        # native RealityKit load: without this the model arrives on its back
        # and `extents.y` reports the body's depth instead of its height.
        convert_orientation=True,
        export_global_up_selection='Y',
        export_global_forward_selection='NEGATIVE_Z',
        convert_scene_units='METERS',
        root_prim_path='/root',
    )
    print("EXPORTED:", args.out, f"({os.path.getsize(args.out) // 1024} KB)")

    if args.preview:
        render_preview(args.preview, scene)


if __name__ == "__main__":
    main()
