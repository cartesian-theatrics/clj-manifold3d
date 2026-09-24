"""Optional studio preview of the Clojure-generated GLB; Blender creates no engine geometry.

blender -b -t 8 --python examples/raptor_3_render.py -- target/raptor-3.glb target/raptor-3.png
"""
import sys
from pathlib import Path

import bpy
from mathutils import Vector

args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
source = Path(args[0] if args else "target/raptor-3.glb").resolve()
output = Path(args[1] if len(args) > 1 else "target/raptor-3.png").resolve()
output.parent.mkdir(parents=True, exist_ok=True)
bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete(use_global=False)
bpy.ops.import_scene.gltf(filepath=str(source))

scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 64
scene.cycles.use_denoising = True
scene.render.resolution_x = 1440
scene.render.resolution_y = 1600
scene.render.resolution_percentage = 100
scene.world.use_nodes = True
scene.world.node_tree.nodes["Background"].inputs[0].default_value = (0.16, 0.19, 0.23, 1)
scene.world.node_tree.nodes["Background"].inputs[1].default_value = 0.35
scene.view_settings.view_transform = "AgX"

bpy.ops.mesh.primitive_plane_add(size=200, location=(0, 0, -0.19))
floor = bpy.context.object
floor.name = "Studio floor (preview only)"
material = bpy.data.materials.new("Studio charcoal")
material.use_nodes = True
shader = material.node_tree.nodes.get("Principled BSDF")
shader.inputs["Base Color"].default_value = (0.043, 0.055, 0.069, 1)
shader.inputs["Roughness"].default_value = 0.64
floor.data.materials.append(material)

def aim(obj, point):
    obj.rotation_euler = (Vector(point) - obj.location).to_track_quat("-Z", "Y").to_euler()

def area(name, location, power, color, size, target=(0, 0, 1.3), size_y=None):
    data = bpy.data.lights.new(name, "AREA")
    data.energy, data.color, data.shape, data.size = power, color, "RECTANGLE", size
    data.size_y = size_y or size
    obj = bpy.data.objects.new(name, data)
    scene.collection.objects.link(obj)
    obj.location = location
    aim(obj, target)

area("Large warm key", (-3.3, -4.4, 5.0), 1500, (1.0, 0.91, 0.79), 3.0, size_y=5)
area("Cool rim", (3.0, 1.6, 3.7), 1800, (0.65, 0.79, 1.0), 2.4, size_y=4)
area("Front strip", (1.3, -4.2, 2.4), 650, (0.9, 0.95, 1.0), 1.1, size_y=3.5)
area("Top softbox", (-0.5, 0.2, 5.8), 1000, (1.0, 1.0, 1.0), 3)

bpy.ops.object.camera_add(location=(3.7, -7.3, 3.2))
camera = bpy.context.object
aim(camera, (0.06, 0, 1.27))
camera.data.type = "ORTHO"
camera.data.ortho_scale = 3.55
scene.camera = camera
scene.render.image_settings.file_format = "PNG"
scene.render.filepath = str(output)
bpy.ops.wm.save_as_mainfile(filepath=str(output.with_suffix(".blend")))
bpy.ops.render.render(write_still=True)
print(f"Rendered {source} to {output}")
