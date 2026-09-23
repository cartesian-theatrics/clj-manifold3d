(ns clj-manifold3d.journal.runtime-api
  "Shared contract for SCI exposure and the agent's read-only API catalog.")

(def core-exclusions
  '[init! dispose! with-disposal import-mesh export-mesh export-model export-scene
    text load-image load-surface ply-file-to-surface])
(def texture-functions
  '[uv planar-uv-native unwrap-native geodesic-uv geodesic-uv-native bake image])
(def animation-functions
  '[scene scene? keyframes sample sample-times pivot-arm-scene])
(def math-functions '[pi sin cos sqrt pow abs floor atan2 hypot])

(defn exposed? [namespace name]
  (let [sym (symbol name)]
    (case namespace
      "clj-manifold3d.core" (not (some #{sym} core-exclusions))
      "clj-manifold3d.texture" (boolean (some #{sym} texture-functions))
      "clj-manifold3d.animation" (boolean (some #{sym} animation-functions))
      "clj-manifold3d.math" (boolean (some #{sym} math-functions))
      false)))

(def capabilities
  {:evaluation "SCI + Manifold WASM in the browser. Proposed code/import edits are automatically evaluated in a fresh journal worker, including geometry export; failures return to the assistant for repair. Passed results render inline. API lookup itself never executes code."
   :imports "All aliases and refers must be declared in the document's visible ns header. No implicit m, texture, animation or math aliases. Patch that header when adding imports."
   :textures "Native Models carry colors, images and surface UVs. Use m/texture for local surface decals; m/texture-all covers every face (default :box repeating tiles, or :mapping :unwrap for an atlas). texture/bake produces image bytes. Return the Model to preview/download a textured GLB."
   :animation "m/scene supports a node hierarchy and translation/rotation/scale keyframes. Scene rotations are quaternions; m/rotate uses degrees."
   :scene-materials "Animated scene nodes preserve Manifold colors and native Model materials, UVs, normals and embedded texture images. Use a colored Manifold or textured Model as :geometry. Optional per-node :material supports :color [r g b a], :roughness and :metalness in [0,1], :emissive [r g b] in [0,1], and nonnegative :emissive-strength. Emission is visible but does not itself illuminate other objects. Return one scene containing both textured and animated nodes."
   :lighting "Scene nodes support :light {:type :point|:spot|:directional :color [r g b] :intensity n}. Point/spot lights optionally accept positive :range. Spot angles :inner-cone and :outer-cone are radians. Local -Z is the light direction; orient the node with a quaternion. Lights and perspective :camera {:yfov radians :znear n :zfar n} nodes support transform animation. These are node data, not m/light constructors. The journal viewer also supplies fixed fill lighting; cinematic bloom/reflections and camera selection are renderer-specific."
   :files "No shell, arbitrary filesystem, network or evaluation tools. Export via the viewer Download button, not JVM file APIs."})
