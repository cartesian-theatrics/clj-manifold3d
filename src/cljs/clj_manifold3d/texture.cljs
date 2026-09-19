(ns clj-manifold3d.texture
  "Boolean-compatible texture properties, native surface walks and depth.
  Geometry algorithms are shared with the JVM in MeshUtils C++, not ported to JS."
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.runtime :as rt]
            [clj-manifold3d.glb :as glb]
            [clj-manifold3d.raster :as raster]
            ["fast-png" :as png]
            [goog.object :as gobj]))

(defn bake
  "Bake opaque m/color geometry, viewed from +Z, to PNG bytes synchronously.
  Options: :width/:height (1..2048), :bounds [xmin ymin xmax ymax],
  :background (linear RGBA), :color-index (absolute property index, default 3).
  Preserves colors without lighting; +Y is image-up. No file/network access."
  [artwork & options]
  (let [{:keys [width height data]} (apply raster/render artwork options)]
    ((gobj/get png "encode") (js-obj "width" width "height" height "data" data "channels" 4 "depth" 8))))

(defn- finite? [x] (and (number? x) (js/Number.isFinite x)))
(defn- tuple! [label n value]
  (when-not (and (sequential? value) (= n (count value)) (every? finite? value))
    (throw (ex-info (str label " must contain " n " finite numbers") {:value value})))
  (vec value))
(defn- pair! [label value] (tuple! label 2 (if (number? value) [value value] value)))
(defn- shape! [object]
  (when-not (m/manifold? object) (throw (ex-info "Texture mapping expects a Manifold" {:value object}))))
(defn- prop-index! [object index]
  (if (= :append index) (+ 3 (rt/call object "numProp"))
      (do (when-not (and (integer? index) (<= 3 index 2147483645))
            (throw (ex-info "Property index must be an integer at or after position channels" {:prop-index index})))
          index)))

(defn uv
  "Map a function [x y z] -> [u v], or one UV pair per graphical vertex.
  Defaults to appending after existing properties; preserves physical seam metadata."
  [object mapping & {:keys [prop-index] :or {prop-index :append}}]
  (shape! object)
  (let [mesh (m/get-mesh-gl object) old-width (gobj/get mesh "numProp")
        old (gobj/get mesh "vertProperties") count-vertices (/ (alength old) old-width)
        index (prop-index! object prop-index) width (max old-width (+ 2 index))
        values (if (fn? mapping)
                 (mapv (fn [i] (mapping (mapv #(aget old (+ (* i old-width) %)) (range 3)))) (range count-vertices))
                 (vec mapping))
        properties (js/Float32Array. (* width count-vertices))]
    (when-not (= count-vertices (count values))
      (throw (ex-info "Expected one UV pair per graphical vertex" {:vertices count-vertices :values (count values)})))
    (doseq [i (range count-vertices)]
      (.set properties (.subarray old (* i old-width) (* (inc i) old-width)) (* i width))
      (let [[u v] (tuple! "UV" 2 (nth values i))]
        (aset properties (+ (* i width) index) u)
        (aset properties (+ (* i width) index 1) v)))
    (gobj/set mesh "numProp" width)
    (gobj/set mesh "vertProperties" properties)
    (m/manifold mesh)))

(defn planar-uv-native
  [object & {:keys [axes scale offset prop-index]
              :or {axes [:x :z] scale 1 offset [0 0] prop-index :append}}]
  (shape! object)
  (let [axes (mapv #(if (integer? %) % ({:x 0 :y 1 :z 2} %)) axes)]
    (when-not (and (= 2 (count axes)) (every? #{0 1 2} axes) (apply not= axes))
      (throw (ex-info "UV axes must be two distinct position axes" {:axes axes})))
    (rt/native "applyPlanarUV" object
               (js-obj "propIndex" (prop-index! object prop-index) "axes" (clj->js axes)
                       "scale" (clj->js (pair! "scale" scale)) "offset" (clj->js (pair! "offset" offset))))))

(defn unwrap-native
  [object & {:keys [seam-angle scale padding pack? prop-index]
              :or {seam-angle 45 scale 1 padding 0.01 pack? true prop-index :append}}]
  (shape! object)
  (when-not (and (finite? seam-angle) (<= 0 seam-angle 180) (finite? scale) (pos? scale)
                 (finite? padding) (<= 0 padding) (< padding 0.5))
    (throw (ex-info "Invalid unwrap angle, scale, or padding" {})))
  (rt/native "unwrapUV" object
             (js-obj "propIndex" (prop-index! object prop-index) "seamAngle" seam-angle
                     "scale" scale "padding" padding "pack" (boolean pack?))))

(defn geodesic-uv
  "Native halfedge/plane-cut mapping with optional numeric or image depth.
  Options match the JVM. Images may be Uint8Array/ArrayBuffer (synchronous) or
  a filename/URL (returns a Promise). Depth is normal-relative on smooth
  surfaces and mitered across two/three-face planar corners. :fade/:step select
  boundary behavior. Global self-intersections are not checked."
  [object & {:keys [origin normal u-direction size pixel-size uv-rect outside-uv prop-index
                    depth-map depth-scale depth-offset depth-fade depth-boundary]
             :or {normal [0 0 0] u-direction [1 0 0] uv-rect [0 0 1 1] outside-uv [0 0]
                  prop-index :append depth-scale 1 depth-offset 0 depth-boundary :fade}
             :as options}]
  (shape! object)
  (when-let [unknown (seq (remove #{:origin :normal :u-direction :size :pixel-size :uv-rect :outside-uv
                                   :prop-index :depth-map :depth-scale :depth-offset :depth-fade :depth-boundary}
                                 (keys options)))]
    (throw (ex-info "Unknown surface mapping options" {:options unknown})))
  (let [origin (tuple! "origin" 3 origin) normal (tuple! "normal" 3 normal)
        right (tuple! "u-direction" 3 u-direction) size (tuple! "size" 2 size)
        [u0 v0 u1 v1 :as rect] (tuple! "uv-rect" 4 uv-rect)
        outside (tuple! "outside-uv" 2 outside-uv)
        pixel (or pixel-size (/ (apply min size) 32))
        fade (if (some? depth-fade) depth-fade
                 (if (= :step depth-boundary) 0 (min (* 2 pixel) (/ (apply min size) 2))))]
    (when-not (and (every? pos? size) (< u0 u1) (< v0 v1) (finite? pixel) (pos? pixel))
      (throw (ex-info "Size/pixel-size must be positive and UV rectangle increasing" {})))
    (when-not (and (#{:step :fade} depth-boundary) (every? finite? [depth-scale depth-offset fade]) (<= 0 fade))
      (throw (ex-info "Invalid depth scale, offset, fade or boundary mode" {})))
    (when (and (nil? depth-map) (some #(contains? options %) [:depth-scale :depth-offset :depth-fade :depth-boundary]))
      (throw (ex-info "Depth options require :depth-map" {})))
    (let [config (js-obj "origin" (clj->js origin) "normal" (clj->js normal)
                         "uDirection" (clj->js right) "size" (clj->js size) "pixelSize" pixel
                         "uvRect" (clj->js rect) "outsideUV" (clj->js outside)
                         "propIndex" (prop-index! object prop-index) "depthScale" depth-scale
                         "depthOffset" depth-offset "depthFade" fade "depthBoundary" (name depth-boundary))
          image? (or (string? depth-map) (instance? js/Uint8Array depth-map) (instance? js/ArrayBuffer depth-map))]
      (cond
        (string? depth-map)
        (-> (rt/read-bytes depth-map)
            (.then (fn [bytes] (gobj/set config "depthImage" bytes) (rt/native "geodesicUV" object config))))
        image?
        (do (gobj/set config "depthImage" (if (instance? js/ArrayBuffer depth-map) (js/Uint8Array. depth-map) depth-map))
            (rt/native "geodesicUV" object config))
        :else
        (do
          (when (some? depth-map)
            (when-not (and (sequential? depth-map) (<= 2 (count depth-map))
                           (every? sequential? depth-map) (<= 2 (count (first depth-map)))
                           (<= (* (count depth-map) (count (first depth-map))) 2000000)
                           (every? #(= (count (first depth-map)) (count %)) depth-map)
                           (every? finite? (mapcat identity depth-map)))
              (throw (ex-info "Depth requires a rectangular finite grid of at least 2x2" {})))
            (gobj/set config "depthValues" (js/Float64Array. (clj->js (mapcat identity depth-map))))
            (gobj/set config "depthWidth" (count (first depth-map)))
            (gobj/set config "depthHeight" (count depth-map)))
          (rt/native "geodesicUV" object config))))))
(def geodesic-uv-native geodesic-uv)

(defn- mime-type [bytes]
  (cond
    (and (>= (alength bytes) 8) (= 137 (aget bytes 0)) (= 80 (aget bytes 1))
         (= 78 (aget bytes 2)) (= 71 (aget bytes 3))) "image/png"
    (and (>= (alength bytes) 3) (= 255 (aget bytes 0)) (= 216 (aget bytes 1)) (= 255 (aget bytes 2))) "image/jpeg"
    :else (throw (ex-info "GLB textures require PNG or JPEG bytes" {}))))

(defn glb-bytes
  "Encode a textured solid and PNG/JPEG bytes as a self-contained GLB."
  [object image-bytes & {:keys [prop-index] :or {prop-index 3}}]
  (let [{:keys [positions indices rows num-prop min max]} (glb/mesh-data object)
        _ (when-not (and (integer? prop-index) (<= 3 prop-index) (< (inc prop-index) num-prop))
            (throw (ex-info "Requested UV property channels are absent" {:prop-index prop-index :num-prop num-prop})))
        uvs (vec (mapcat #(subvec % prop-index (+ 2 prop-index)) rows))
        [state position-view] (glb/add-segment (glb/empty-state) (glb/floats->bytes positions) 34962)
        [state position-accessor] (glb/add-accessor state position-view 5126 (/ (count positions) 3) "VEC3" min max)
        [state uv-view] (glb/add-segment state (glb/floats->bytes uvs) 34962)
        [state uv-accessor] (glb/add-accessor state uv-view 5126 (/ (count uvs) 2) "VEC2" nil nil)
        [state index-view] (glb/add-segment state (glb/ints->bytes indices) 34963)
        [state index-accessor] (glb/add-accessor state index-view 5125 (count indices) "SCALAR" nil nil)
        [state image-view] (glb/add-segment state image-bytes nil)
        length (glb/align4 (:length state))
        document {"asset" {"version" "2.0" "generator" "clj-manifold3d.texture.cljs"}
                  "scene" 0 "scenes" [{"nodes" [0]}] "nodes" [{"mesh" 0}]
                  "meshes" [{"primitives" [{"attributes" {"POSITION" position-accessor "TEXCOORD_0" uv-accessor}
                                            "indices" index-accessor "material" 0 "mode" 4}]}]
                  "materials" [{"pbrMetallicRoughness" {"baseColorTexture" {"index" 0} "metallicFactor" 0 "roughnessFactor" 0.8}}]
                  "textures" [{"sampler" 0 "source" 0}]
                  "samplers" [{"magFilter" 9729 "minFilter" 9729 "wrapS" 10497 "wrapT" 10497}]
                  "images" [{"bufferView" image-view "mimeType" (mime-type image-bytes)}]
                  "buffers" [{"byteLength" length}] "bufferViews" (:views state) "accessors" (:accessors state)}]
    (glb/encode document (:segments state) length)))

(defn export-glb
  "Promise of filename after writing/downloading, or bytes if filename is nil."
  [object filename image & options]
  (-> (rt/read-bytes image)
      (.then (fn [bytes] (rt/write-bytes! filename (apply glb-bytes object bytes options))))))
