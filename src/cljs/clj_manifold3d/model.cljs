(ns clj-manifold3d.model
  "Thread-first bindings to the same native Model as the JVM."
  (:require [clj-manifold3d.runtime :as rt]
            [clj-manifold3d.model-options :as options]))
(defn model? [x] (rt/instance-of? "Model" x))
(defn model
  "Wrap a Manifold in a native Model carrying geometry, colors and image layers.
  Passing an existing Model returns it unchanged. Supports thread-first use."
  [x]
  (cond (model? x) x
        (rt/instance-of? "Manifold" x) (rt/construct "Model" x)
        :else (throw (ex-info "model expects a Manifold or Model" {:value x}))))
(defn- image-bytes [image]
  (cond (instance? js/Uint8Array image) image
        (instance? js/ArrayBuffer image) (js/Uint8Array. image)
        :else (throw (ex-info "Load PNG/JPEG bytes before threading a Model; URL loading is asynchronous" {}))))
(defn texture
  "Append an image layer with native UV mapping; return a new native Model.
  object is a Manifold or Model. image is PNG/JPEG Uint8Array or ArrayBuffer
  bytes (e.g. texture/bake output), not a filename or URL.

  :mapping defaults to :geodesic, requiring :origin [x y z] on the surface
  and positive :size [width height] in model units. :normal [x y z] selects
  the surface direction (default [0 0 0] auto); :u-direction [1 0 0] sets
  image orientation. Positive :pixel-size sets surface sampling distance.
  :uv-rect [u0 v0 u1 v1] defaults to [0 0 1 1]; :opacity defaults to 1.
  Layers composite source-over. :name is an optional string.

  :mapping :box and :unwrap cover the whole surface; see texture-all.
  :mapping :planar supports :axes [:x :z], :scale (scalar or pair), and
  :offset [u v]. Optional geodesic :depth-map is a rectangular numeric grid
  (at least 2x2) or image bytes; :depth-scale defaults to 1, :depth-offset to
  0. :depth-boundary is :fade (default) or :step; :depth-fade is a nonnegative
  distance. Depth displaces geometry relative to the surface normal."
  [object image & {:as opts}]
  (let [config (options/texture-options opts)
        config (cond-> config (get config "depthImage") (update "depthImage" image-bytes))]
    (rt/native "modelTexture" (model object) (image-bytes image) (clj->js config))))

(defn texture-all
  "Cover every face with PNG/JPEG bytes; return a new native Model.
  Default :mapping :box uses dominant-normal projection with repeating tiles.
  :size [w h] defaults to [1 1] model units, :origin to [0 0 0], :scale to 1,
  and :offset to [0 0]. Projection seams are expected; no triplanar blending.
  :mapping :unwrap cuts/flattens charts into one image atlas: :seam-angle 45,
  :padding 0.01, :pack? true. With :pack? false, charts use model-unit UVs
  times uniform :scale and the image repeats. This is not a seamless wrap.
  :planar is also supported, but edge-on faces can have collapsed UVs.
  :opacity and :name work as for texture. Existing geometry/layers survive."
  [object image & {:as opts}]
  (apply texture object image (mapcat identity (options/whole-surface-options opts))))
(defn info [object]
  (let [value (model object)]
    {:layers (rt/call value "layerCount") :images (rt/call value "imageCount") :surfaces (rt/call value "surfaceCount")}))
(defn sample-color [object face b1 b2] (vec (array-seq (rt/call (model object) "sampleColor" face b1 b2))))
(defn export-model
  "Preserve opaque regions' source UVs/images in GLB; nil returns bytes.
  :tile-size controls the atlas fallback for alpha layering/boundary crossings."
  [object filename & {:as opts}]
  (when (and filename (not (.endsWith (.toLowerCase (str filename)) ".glb")))
    (throw (ex-info "Native Model appearance export requires .glb" {})))
  (rt/write-bytes! filename (rt/native "modelGLB" (model object) (options/export-options opts))))
