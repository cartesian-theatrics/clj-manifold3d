(ns clj-manifold3d.texture
  "JVM texture-coordinate properties for Manifold solids.

  UV coordinates are stored as two ordinary MeshGL property channels. Manifold
  interpolates those channels through boolean operations just like any other
  vertex property."
  (:require [clj-manifold3d.core :as manifold]
            [clj-manifold3d.pixels :as pixels]
            [clojure.data.json :as json]
            [clojure.string :as string])
  (:import [manifold3d FloatVector MeshUtils]
           [java.io File FileOutputStream]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]))

(defn image
  "Generate PNG bytes synchronously. pixel-fn receives integer x,y (top-left origin)
  and returns normalized sRGB [r g b alpha]. Dimensions must be 1..2048."
  [width height pixel-fn]
  (pixels/dimensions! width height)
  (let [image (java.awt.image.BufferedImage. width height java.awt.image.BufferedImage/TYPE_INT_ARGB)]
    (doseq [y (range height) x (range width)
            :let [[r g b a] (pixels/rgba8 (pixel-fn x y))]]
      (.setRGB image x y (unchecked-int (bit-or (bit-shift-left a 24) (bit-shift-left r 16) (bit-shift-left g 8) b))))
    (with-open [out (java.io.ByteArrayOutputStream.)]
      (javax.imageio.ImageIO/write image "png" out)
      (.toByteArray out))))

(def ^:private position-width 3)
(def ^:private uv-width 2)

(defn- finite-number? [value]
  (and (number? value) (Double/isFinite (double value))))

(defn- validate-prop-index! [prop-index]
  (when-not (and (integer? prop-index) (<= position-width prop-index))
    (throw (ex-info
            ":prop-index must be an integer at or after the three position channels"
            {:prop-index prop-index})))
  prop-index)

(defn- validate-uv! [value]
  (when-not (and (sequential? value)
                 (= uv-width (count value))
                 (every? finite-number? value))
    (throw (ex-info
            "UV coordinates must be a finite numeric pair"
            {:uv value})))
  (mapv double value))

(defn- property-row [properties num-prop vertex-index]
  (let [offset (* vertex-index num-prop)]
    (mapv #(aget properties (+ offset %)) (range num-prop))))

(defn- uv-values [mapping positions]
  (let [values (if (fn? mapping)
                 (mapv mapping positions)
                 (let [values (vec mapping)]
                   (when-not (= (count positions) (count values))
                     (throw (ex-info
                             "Explicit UV values must contain one pair per MeshGL vertex"
                             {:vertices (count positions)
                              :uv-values (count values)})))
                   values))]
    (mapv validate-uv! values)))

(defn uv
  "Attach UV coordinates to a Manifold as two MeshGL property channels.

  `mapping` is either a function from `[x y z]` to `[u v]`, or one UV pair per
  graphical MeshGL vertex. The latter form can represent UV seams because
  MeshGL may contain duplicate vertices with distinct properties.

  `:prop-index` is the absolute interleaved MeshGL property offset, including
  the three position channels. It defaults to `:append`; use the same explicit
  index for every operand that will participate in a boolean operation. The
  existing `core/color` helper occupies offset 3 by default, so applying `uv`
  after `color` appends UVs at offset 7 automatically."
  [manifold-object mapping & {:keys [prop-index] :or {prop-index :append}}]
  (when-not (manifold/manifold? manifold-object)
    (throw (ex-info "texture/uv expects a Manifold" {:value manifold-object})))
  (let [mesh (manifold/get-mesh-gl manifold-object)
        old-num-prop (int (.numProp mesh))
        num-vertices (int (.NumVert mesh))
        old-properties (.toFloatArray (.vertProperties mesh))
        positions (mapv #(subvec (property-row old-properties old-num-prop %) 0 3)
                        (range num-vertices))
        values (uv-values mapping positions)
        prop-index (if (= :append prop-index)
                     old-num-prop
                     (validate-prop-index! prop-index))
        new-num-prop (max old-num-prop (+ prop-index uv-width))
        properties (float-array
                    (mapcat (fn [vertex-index]
                              (let [row (vec (concat
                                              (property-row old-properties
                                                            old-num-prop
                                                            vertex-index)
                                              (repeat (- new-num-prop old-num-prop)
                                                      0.0)))
                                    [u v] (nth values vertex-index)]
                                (assoc row prop-index (float u)
                                       (inc prop-index) (float v))))
                            (range num-vertices)))]
    ;; Keep all topology, merge, run, and face metadata on the MeshGL value.
    ;; Only the property stride and interleaved property buffer change.
    (.numProp mesh new-num-prop)
    (.vertProperties mesh (FloatVector/FromArray properties))
    (manifold/manifold mesh)))

(defn- axis-index [axis]
  (cond
    (integer? axis) axis
    (= :x axis) 0
    (= :y axis) 1
    (= :z axis) 2
    :else (throw (ex-info
                  "UV axes must be :x, :y, or :z (or their integer indexes)"
                  {:axis axis}))))

(defn- pair-option [name value]
  (let [pair (if (number? value) [value value] value)]
    (when-not (and (sequential? pair)
                   (= 2 (count pair))
                   (every? finite-number? pair))
      (throw (ex-info (str name " must be a finite number or numeric pair")
                      {:name name :value value})))
    (mapv double pair)))

(defn planar-uv-native
  "Attach planar UV properties using a native C++ projection.

  `:axes` selects the position axes for `[u v]` and defaults to `[:x :z]`.
  `:scale` and `:offset` accept either one number or a `[u v]` pair. The
  property slot is absolute in MeshGL and defaults to `:append`. Unlike `uv`,
  this function does not call a Clojure mapping function once per vertex."
  [manifold-object & {:keys [axes scale offset prop-index]
                      :or {axes [:x :z]
                           scale 1.0
                           offset [0.0 0.0]
                           prop-index :append}}]
  (when-not (manifold/manifold? manifold-object)
    (throw (ex-info "texture/planar-uv-native expects a Manifold"
                    {:value manifold-object})))
  (let [[axis-u axis-v] (if (and (sequential? axes) (= 2 (count axes)))
                          (mapv axis-index axes)
                          (throw (ex-info ":axes must contain two distinct axes"
                                          {:axes axes})))
        [scale-u scale-v] (pair-option ":scale" scale)
        [offset-u offset-v] (pair-option ":offset" offset)
             prop-index (if (= :append prop-index)
                          ;; Manifold.numProp is the position-property count;
                          ;; append against the complete MeshGL stride so that
                          ;; existing color/custom properties are preserved.
                          (.numProp (manifold/get-mesh-gl manifold-object))
                          (validate-prop-index! prop-index))]
    (when (= axis-u axis-v)
      (throw (ex-info "UV axes must be distinct" {:axes axes})))
    (MeshUtils/ApplyPlanarUV manifold-object
                              (long prop-index)
                              (int axis-u)
                              (int axis-v)
                              (double scale-u)
                              (double scale-v)
                              (double offset-u)
                              (double offset-v))))


(defn- validate-vector3! [name value]
  (when-not (and (sequential? value)
                 (= 3 (count value))
                 (every? finite-number? value))
    (throw (ex-info (str name " must be a finite numeric 3-vector")
                    {name value})))
  (mapv double value))

(defn- validate-pair! [name value positive?]
  (when-not (and (sequential? value)
                 (= 2 (count value))
                 (every? finite-number? value)
                 (or (not positive?) (every? pos? value)))
    (throw (ex-info (str name " must be a finite numeric pair")
                    {name value})))
  (mapv double value))

(defn- validate-uv-rect! [value]
  (when-not (and (sequential? value)
                 (= 4 (count value))
                 (every? finite-number? value)
                 (< (double (nth value 0)) (double (nth value 2)))
                 (< (double (nth value 1)) (double (nth value 3))))
    (throw (ex-info ":uv-rect must be [u-min v-min u-max v-max]"
                    {:uv-rect value})))
  (mapv double value))


(defn- depth-input [value]
  (if (string? value)
    {:image value}
    (do
      (when-not (and (sequential? value) (<= 2 (count value))
                     (every? sequential? value))
        (throw (ex-info ":depth-map must be an image filename or a rectangular numeric grid"
                        {:depth-map value})))
      (let [height (count value)
            width (count (first value))]
        (when-not (and (<= 2 width) (<= (* width height) 2000000)
                       (every? #(= width (count %)) value)
                       (every? finite-number? (mapcat identity value)))
          (throw (ex-info "Depth grid must be rectangular, finite, and between 2x2 and two million samples"
                          {:width width :height height})))
        {:values (double-array (mapcat identity value))
         :width width :height height}))))

(defn geodesic-uv
  "Lay a rectangular image onto a local surface using native plane-cut walks.

  `:origin` is projected onto the closest face. `:normal` and `:u-direction`
  orient the cutting planes. `:size` is the physical [width height]: width
  is walked along the center baseline, height along each column. `:pixel-size`
  is the distance between surface samples (default: min(width,height)/32).
  Samples and edge crossings subdivide the original faces locally, preserving
  the surface and existing properties before optional depth displacement.
  No per-vertex JVM callbacks are used.

  UVs occupy `:uv-rect` [u-min v-min u-max v-max], with image top toward +V.
  The sticker boundary has separate inside/outside property vertices joined
  geometrically, so :outside-uv cannot smear into the image. `:prop-index`
  is an absolute MeshGL offset and defaults to :append.

  Optional `:depth-map` is an image filename or a rectangular grid of signed
  numbers (rows run top to bottom). Samples are bilinearly interpolated in
  local image coordinates, independently of :uv-rect. Displacement in model
  units is sample * :depth-scale + :depth-offset (defaults 1 and 0). Smooth
  surfaces use the original interpolated, angle-weighted normal. Positive is
  outward; negative engraves. Images decode natively to grayscale [0,1],
  preserving 16-bit PNG precision; alpha is ignored.

  Sharp planar corners automatically use a common miter direction whose dot
  product with each face normal is 1. Depth remains the normal distance from
  each original plane, with tangential motion to keep the whole chart joined.
  An inner square corner thus moves by [d d d], not a unit diagonal times d.
  This supports patches with two or three planar face orientations; more
  complex sharp joins and miters longer than eight times depth are rejected.

  `:depth-boundary` is :fade (default) or :step. With :fade, `:depth-fade`
  smoothly returns depth to zero at the patch edge, in model units. Its
  default is twice :pixel-size, capped at half the patch size.
  With zero fade, every boundary depth sample must already be zero. :step
  retains boundary depth and adds watertight side walls using :outside-uv;
  its fade must be zero. Sign changes between boundary vertices are rejected
  unless a vertex samples the zero crossing. The original surface is
  unchanged outside the patch. Local triangle folds are rejected,
  but global self-intersections are not checked: keep depth modest relative
  to local curvature, thickness, and nearby surfaces.

  This is a plane-cut chart, not a shortest-geodesic or stretch-free unwrap.
  The patch must remain a single sheet over its local tangent plane; folds,
  tangencies and incomplete coverage throw. Refine smooth tangent input first."
  [manifold-object & {:keys [origin u-direction normal size uv-rect outside-uv
                             prop-index pixel-size depth-map depth-scale
                             depth-offset depth-fade depth-boundary]
                      :or {uv-rect [0.0 0.0 1.0 1.0]
                           outside-uv [0.0 0.0]
                           prop-index :append
                           depth-scale 1.0
                           depth-offset 0.0
                           depth-boundary :fade}
                      :as options}]
  (when-let [unknown (seq (remove #{:origin :u-direction :normal :size :uv-rect
                                   :outside-uv :prop-index :pixel-size :depth-map
                                   :depth-scale :depth-offset :depth-fade :depth-boundary}
                                 (keys options)))]
    (throw (ex-info "Unknown surface mapping options" {:options unknown})))
  (when-not (manifold/manifold? manifold-object)
    (throw (ex-info "texture/geodesic-uv expects a Manifold"
                    {:value manifold-object})))
  (let [origin (validate-vector3! ":origin" origin)
        size (validate-pair! ":size" size true)
        outside-uv (validate-uv! outside-uv)
        uv-rect (validate-uv-rect! uv-rect)
        normal (if normal
                 (validate-vector3! ":normal" normal)
                 [0.0 0.0 0.0])
        u-direction (if u-direction
                      (validate-vector3! ":u-direction" u-direction)
                      [1.0 0.0 0.0])
        pixel-size (or pixel-size (/ (apply min size) 32.0))
        _ (when-not (and (finite-number? pixel-size) (pos? (double pixel-size)))
            (throw (ex-info ":pixel-size must be positive and finite"
                            {:pixel-size pixel-size})))
        mesh (manifold/get-mesh-gl manifold-object)
        prop-index (if (= :append prop-index)
                     (.numProp mesh)
                     (validate-prop-index! prop-index))
        [u-min v-min u-max v-max] uv-rect
        [width height] size
        depth (when (some? depth-map) (depth-input depth-map))
        _ (when-not (#{:fade :step} depth-boundary)
            (throw (ex-info ":depth-boundary must be :fade or :step"
                            {:depth-boundary depth-boundary})))
        depth-fade (if (some? depth-fade) depth-fade
                       (if (= :step depth-boundary) 0.0
                           (min (* 2.0 pixel-size) (/ width 2.0) (/ height 2.0))))
        _ (when-not (and (finite-number? depth-scale) (finite-number? depth-offset)
                         (finite-number? depth-fade) (<= 0 depth-fade))
            (throw (ex-info "Depth scale/offset must be finite and fade must be nonnegative"
                            {:depth-scale depth-scale :depth-offset depth-offset
                             :depth-fade depth-fade})))
        _ (when (and (nil? depth)
                     (some #(contains? options %) [:depth-scale :depth-offset :depth-fade :depth-boundary]))
            (throw (ex-info "Depth options require :depth-map" {})))]
    (cond
      (:image depth)
      (MeshUtils/GeodesicUVDepthImage
        manifold-object (long prop-index)
        (double (nth origin 0)) (double (nth origin 1)) (double (nth origin 2))
        (double (nth normal 0)) (double (nth normal 1)) (double (nth normal 2))
        (double (nth u-direction 0)) (double (nth u-direction 1)) (double (nth u-direction 2))
        (double width) (double height)
        (double u-min) (double v-min) (double (- u-max u-min)) (double (- v-max v-min))
        (double (first outside-uv)) (double (second outside-uv)) (double pixel-size)
        ^String (:image depth) (double depth-scale) (double depth-offset) (double depth-fade)
        (= :step depth-boundary))

      depth
      (MeshUtils/GeodesicUVDepth
        manifold-object (long prop-index)
        (double (nth origin 0)) (double (nth origin 1)) (double (nth origin 2))
        (double (nth normal 0)) (double (nth normal 1)) (double (nth normal 2))
        (double (nth u-direction 0)) (double (nth u-direction 1)) (double (nth u-direction 2))
        (double width) (double height)
        (double u-min) (double v-min) (double (- u-max u-min)) (double (- v-max v-min))
        (double (first outside-uv)) (double (second outside-uv)) (double pixel-size)
        ^doubles (:values depth) (int (:width depth)) (int (:height depth))
        (double depth-scale) (double depth-offset) (double depth-fade) (= :step depth-boundary))

      :else
      (MeshUtils/GeodesicUV manifold-object
                           (long prop-index)
                           (double (first origin))
                           (double (second origin))
                           (double (nth origin 2))
                           (double (first normal))
                           (double (second normal))
                           (double (nth normal 2))
                           (double (first u-direction))
                           (double (second u-direction))
                           (double (nth u-direction 2))
                           (double width)
                           (double height)
                           (double u-min)
                           (double v-min)
                           (double (- u-max u-min))
                           (double (- v-max v-min))
                           (double (first outside-uv))
                           (double (second outside-uv))
                           (double pixel-size)))))

(defn geodesic-uv-native
  "Native surface-walk mapping; see `geodesic-uv` for options and limits."
  [manifold-object & options]
  (apply geodesic-uv manifold-object options))

(defn unwrap-native
  "Unwrap a Manifold into native LSCM UV charts.

  Smooth regions are charted across edges below `:seam-angle` degrees. Closed
  components receive automatic topology cuts, and each chart is solved with a
  least-squares conformal map. Property vertices are split at chart seams
  while retaining MeshGL merge metadata, so the result remains suitable for
  boolean operations.

  With `:pack?` (the default), charts are packed into the unit square and
  `:padding` is the fractional atlas padding. With packing disabled, `:scale`
  scales the solved coordinates directly. `:prop-index` is an absolute MeshGL
  property offset and defaults to `:append`."
  [manifold-object & {:keys [seam-angle scale padding pack? prop-index]
                      :or {seam-angle 45.0
                           scale 1.0
                           padding 0.01
                           pack? true
                           prop-index :append}}]
  (when-not (manifold/manifold? manifold-object)
    (throw (ex-info "texture/unwrap-native expects a Manifold"
                    {:value manifold-object})))
  (when-not (and (finite-number? seam-angle)
                 (<= 0.0 (double seam-angle) 180.0))
    (throw (ex-info ":seam-angle must be a number in [0, 180]"
                    {:seam-angle seam-angle})))
  (when-not (and (finite-number? scale) (pos? (double scale)))
    (throw (ex-info ":scale must be a positive finite number"
                    {:scale scale})))
  (when-not (and (finite-number? padding)
                 (<= 0.0 (double padding) 0.5)
                 (< (double padding) 0.5))
    (throw (ex-info ":padding must be a number in [0, 0.5)"
                    {:padding padding})))
  (let [prop-index (if (= :append prop-index)
                     (.numProp (manifold/get-mesh-gl manifold-object))
                     (validate-prop-index! prop-index))]
    (MeshUtils/UnwrapUV manifold-object
                         (long prop-index)
                         (double seam-angle)
                         (double scale)
                         (double padding)
                         (boolean pack?))))

(defn- align4 [length]
  (+ length (mod (- 4 (mod length 4)) 4)))

(defn- padded-bytes [bytes fill]
  (let [padding (- (align4 (alength bytes)) (alength bytes))]
    (if (zero? padding)
      bytes
      (let [result (byte-array (+ (alength bytes) padding))]
        (System/arraycopy bytes 0 result 0 (alength bytes))
        (java.util.Arrays/fill result (alength bytes) (alength result) (byte fill))
        result))))

(defn- floats->bytes [values]
  (let [buffer (doto (ByteBuffer/allocate (* 4 (count values)))
                 (.order ByteOrder/LITTLE_ENDIAN))]
    (doseq [value values]
      (.putFloat buffer (float value)))
    (.array buffer)))

(defn- ints->bytes [values]
  (let [buffer (doto (ByteBuffer/allocate (* 4 (count values)))
                 (.order ByteOrder/LITTLE_ENDIAN))]
    (doseq [value values]
      (.putInt buffer (int value)))
    (.array buffer)))

(defn- empty-glb-state []
  {:segments [] :views [] :accessors [] :length 0})

(defn- add-segment [state bytes target]
  (let [offset (align4 (:length state))
        padding (- offset (:length state))
        view (cond-> {"buffer" 0
                      "byteOffset" offset
                      "byteLength" (alength bytes)}
               target (assoc "target" target))]
    [(-> state
         (update :segments into (concat [(byte-array padding)] [bytes]))
         (update :views conj view)
         (assoc :length (+ offset (alength bytes))))
     (dec (count (conj (:views state) view)))]))

(defn- add-accessor [state view component-type value-count type minimum maximum]
  (let [accessor (cond-> {"bufferView" view
                          "componentType" component-type
                          "count" value-count
                          "type" type}
                   minimum (assoc "min" minimum)
                   maximum (assoc "max" maximum))]
    [(update state :accessors conj accessor)
     (dec (count (conj (:accessors state) accessor)))]))

(defn- mesh-data [manifold-object prop-index]
  (let [mesh (manifold/get-mesh-gl manifold-object)
        num-prop (int (.numProp mesh))
        num-vertices (int (.NumVert mesh))
        _ (when-not (and (integer? prop-index)
                         (<= position-width prop-index)
                         (< (inc prop-index) num-prop))
            (throw (ex-info
                    "The requested UV property channels are not present"
                    {:prop-index prop-index :num-prop num-prop})))
        properties (.toFloatArray (.vertProperties mesh))
        rows (mapv #(property-row properties num-prop %) (range num-vertices))
        positions (vec (mapcat #(take position-width %) rows))
        uvs (vec (mapcat #(subvec % prop-index (+ prop-index uv-width)) rows))
        indices (vec (.toIntArray (.triVerts mesh)))
        coordinates (partition position-width positions)
        minimum (reduce (fn [result coordinate]
                          (mapv min result coordinate))
                        (vec (repeat position-width Double/POSITIVE_INFINITY))
                        coordinates)
        maximum (reduce (fn [result coordinate]
                          (mapv max result coordinate))
                        (vec (repeat position-width Double/NEGATIVE_INFINITY))
                        coordinates)]
    {:positions positions
     :uvs uvs
     :indices indices
     :min minimum
     :max maximum}))

(defn- extension [filename]
  (some-> (re-find #"\.([^.]+)$" (str filename)) second string/lower-case))

(defn- image-mime-type [filename]
  (case (extension filename)
    "png" "image/png"
    "jpg" "image/jpeg"
    "jpeg" "image/jpeg"
    (throw (ex-info "GLB texture export supports PNG and JPEG images"
                    {:filename filename}))))

(defn- join-segments [segments length]
  (let [output (byte-array length)]
    (loop [offset 0
           segments segments]
      (when-let [segment (first segments)]
        (System/arraycopy segment 0 output offset (alength segment))
        (recur (+ offset (alength segment)) (next segments))))
    output))

(defn export-glb
  "Export a textured Manifold to a self-contained binary glTF file.

  The image at `image-filename` is embedded in the GLB. `:prop-index` uses the
  same absolute MeshGL property offset as `uv` and defaults to 3. This exporter
  is intentionally JVM-only; it provides the texture example while the native
  MeshIO binding still only exports positions, normals, and colors."
  [manifold-object filename image-filename & {:keys [prop-index]
                                              :or {prop-index 3}}]
  (let [{:keys [positions uvs indices min max]}
        (mesh-data manifold-object prop-index)
        image-file (File. (str image-filename))
        image-bytes (Files/readAllBytes (.toPath image-file))
        image-mime (image-mime-type image-filename)
        [state position-view] (add-segment (empty-glb-state)
                                           (floats->bytes positions) 34962)
        [state position-accessor] (add-accessor state position-view 5126
                                                 (/ (count positions) 3) "VEC3"
                                                 min max)
        [state uv-view] (add-segment state (floats->bytes uvs) 34962)
        [state uv-accessor] (add-accessor state uv-view 5126
                                           (/ (count uvs) 2) "VEC2" nil nil)
        [state index-view] (add-segment state (ints->bytes indices) 34963)
        [state index-accessor] (add-accessor state index-view 5125
                                              (count indices) "SCALAR" nil nil)
        [state image-view] (add-segment state image-bytes nil)
        bin-length (align4 (:length state))
        gltf {"asset" {"version" "2.0"
                        "generator" "clj-manifold3d.texture"}
              "scene" 0
              "scenes" [{"nodes" [0]}]
              "nodes" [{"name" "Textured manifold" "mesh" 0}]
              "meshes" [{"primitives"
                         [{"attributes" {"POSITION" position-accessor
                                         "TEXCOORD_0" uv-accessor}
                           "indices" index-accessor
                           "material" 0
                           "mode" 4}]}]
              "materials" [{"name" "Embedded image texture"
                            "pbrMetallicRoughness"
                            {"baseColorTexture" {"index" 0}
                             "metallicFactor" 0.0
                             "roughnessFactor" 0.8}}]
              "textures" [{"sampler" 0 "source" 0}]
              "samplers" [{"magFilter" 9729 "minFilter" 9729
                            "wrapS" 10497 "wrapT" 10497}]
              "images" [{"bufferView" image-view "mimeType" image-mime}]
              "buffers" [{"byteLength" bin-length}]
              "bufferViews" (:views state)
              "accessors" (:accessors state)}
        json-bytes (.getBytes (json/write-str gltf)
                              StandardCharsets/UTF_8)
        json-chunk (padded-bytes json-bytes 32)
        bin-chunk (padded-bytes (join-segments (:segments state) bin-length) 0)
        total-length (+ 12 8 (alength json-chunk) 8 (alength bin-chunk))
        output (doto (ByteBuffer/allocate total-length)
                 (.order ByteOrder/LITTLE_ENDIAN)
                 (.putInt 0x46546c67)
                 (.putInt 2)
                 (.putInt total-length)
                 (.putInt (alength json-chunk))
                 (.putInt 0x4e4f534a)
                 (.put json-chunk)
                 (.putInt (alength bin-chunk))
                 (.putInt 0x004e4942)
                 (.put bin-chunk))]
    (with-open [stream (FileOutputStream. (str filename))]
      (.write stream (.array output)))
    filename))
