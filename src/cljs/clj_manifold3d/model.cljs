(ns clj-manifold3d.model
  "Thread-first bindings to the same native Model as the JVM."
  (:require [clj-manifold3d.runtime :as rt]
            [clj-manifold3d.model-options :as options]))
(defn model? [x] (rt/instance-of? "Model" x))
(defn model [x]
  (cond (model? x) x
        (rt/instance-of? "Manifold" x) (rt/construct "Model" x)
        :else (throw (ex-info "model expects a Manifold or Model" {:value x}))))
(defn- image-bytes [image]
  (cond (instance? js/Uint8Array image) image
        (instance? js/ArrayBuffer image) (js/Uint8Array. image)
        :else (throw (ex-info "Load PNG/JPEG bytes before threading a Model; URL loading is asynchronous" {}))))
(defn texture [object image & {:as opts}]
  (let [config (options/texture-options opts)
        config (cond-> config (get config "depthImage") (update "depthImage" image-bytes))]
    (rt/native "modelTexture" (model object) (image-bytes image) (clj->js config))))
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
