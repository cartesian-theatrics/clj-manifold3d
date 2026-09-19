(ns clj-manifold3d.model
  "Thread-first bindings to the shared, immutable native manifold::Model."
  (:require [clj-manifold3d.model-options :as options]
            [clojure.java.io :as io])
  (:import [manifold3d Model ModelTexture ModelIO Manifold]
           [manifold3d.linalg DoubleVec3 DoubleVec4]
           [java.nio.file Files]))

(defn model? [x] (instance? Model x))
(defn model [x]
  (cond (model? x) x
        (instance? Manifold x) (Model. ^Manifold x)
        :else (throw (ex-info "model expects a Manifold or Model" {:value x}))))
(defn- image-bytes [image]
  (if (bytes? image) image (Files/readAllBytes (.toPath (io/file image)))))
(defn texture
  "Append an image layer, generating native UVs. Returns a native Model.
  Image is PNG/JPEG bytes or a filename. :mapping is :geodesic (requires
  :origin and :size) or :planar. Layers use source-over with :opacity [0,1]."
  [object image & {:as opts}]
  (let [config (options/texture-options opts) image (image-bytes image)
        vec3 #(DoubleVec3. (nth % 0) (nth % 1) (nth % 2))]
    (with-open [p (ModelTexture.)]
      (.mapping p (config "mapping")) (.name p (config "name")) (.opacity p (config "opacity"))
      (with-open [o (vec3 (config "origin")) n (vec3 (config "normal")) r (vec3 (config "uDirection"))]
        (.origin p o) (.normal p n) (.right p r))
      (.width p (first (config "size"))) (.height p (second (config "size")))
      (.pixelSize p (config "pixelSize"))
      (let [[u0 v0 u1 v1] (config "uvRect")]
        (.u0 p u0) (.v0 p v0) (.u1 p u1) (.v1 p v1))
      (.axisU p (first (config "axes"))) (.axisV p (second (config "axes")))
      (.scaleU p (first (config "scale"))) (.scaleV p (second (config "scale")))
      (.offsetU p (first (config "offset"))) (.offsetV p (second (config "offset")))
      (.depthScale p (config "depthScale")) (.depthOffset p (config "depthOffset"))
      (.depthFade p (config "depthFade")) (.step p (= "step" (config "depthBoundary")))
      (when-let [values (config "depthValues")]
        (.SetDepth p (double-array values) (count values) (config "depthWidth") (config "depthHeight")))
      (when-let [depth (config "depthImage")]
        (let [data (image-bytes depth)] (.SetDepthImage p data (alength data))))
      (ModelIO/Texture (model object) image (alength image) p))))
(defn info [object]
  (let [^Model value (model object)]
    {:layers (.layerCount value) :images (.imageCount value) :surfaces (.surfaceCount value)}))
(defn sample-color [object face b1 b2]
  (with-open [rgba (.sampleColor ^Model (model object) face b1 b2)]
    [(.x rgba) (.y rgba) (.z rgba) (.w rgba)]))
(defn export-model
  "Export the whole native model to GLB. nil filename returns bytes.
  Opaque regions retain original UVs/images; :tile-size (default 16) controls
  render-atlas resolution when alpha layering or boundary crossings need baking."
  [object filename & {:as opts}]
  (when (and filename (not (.endsWith (.toLowerCase (str filename)) ".glb")))
    (throw (ex-info "Native Model appearance export requires .glb" {})))
  (with-open [native (ModelIO/ExportGLB (model object) (options/export-options opts))]
    (let [data (.toByteArray native)]
      (if filename (do (with-open [out (io/output-stream filename)] (.write out data)) filename) data))))
