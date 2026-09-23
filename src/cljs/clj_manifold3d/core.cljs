(ns clj-manifold3d.core
  "Synchronous, immutable modeling on fresh Manifold WASM bindings.
  Await init! once. Explicitly dispose! owned WASM handles when finished."
  (:require [clj-manifold3d.runtime :as rt]
            [clj-manifold3d.animation :as animation]
            [clj-manifold3d.mesh-io :as mesh-io]
            [clj-manifold3d.model :as native-model]
            [clj-manifold3d.spatial :as spatial]
            [goog.object :as gobj]))

(def init! rt/init!)
(def dispose! rt/dispose!)
(def with-disposal rt/with-disposal)
(def scene animation/scene)
(def scene? animation/scene?)
(def export-scene animation/export-scene)
(def material mesh-io/material)
(def export-mesh mesh-io/export-mesh)
(def import-mesh mesh-io/import-mesh)
(def model native-model/model)
(def model? native-model/model?)
(def texture native-model/texture)
(def texture-all native-model/texture-all)
(def model-info native-model/info)
(def sample-color native-model/sample-color)
(defn as-original
  "Reset a bare Manifold's construction provenance; preserve geometry and vertex properties."
  [object]
  (when (model? object) (throw (ex-info "as-original expects an untextured Manifold" {})))
  (rt/call object "asOriginal"))
(defn spatial-index
  "Build a native BVH snapshot. Release with dispose!, or use with-spatial-index."
  [object] (spatial/spatial-index object))
(defn with-spatial-index
  "Call f with a fresh native BVH; release it even on exceptions. Return data or geometry, not the index."
  [object f] (spatial/with-spatial-index object f))
(defn ray-cast
  "Nearest forward surface hit or nil; direction need not be normalized. Options: :max-distance."
  [object origin direction & options] (apply spatial/ray-cast object origin direction options))
(defn closest-point
  "Nearest surface point, distance and normal, or nil."
  [object point] (spatial/closest-point object point))
(defn contains-point?
  "Point containment; options :tolerance and :boundary?."
  [object point & options] (apply spatial/contains-point? object point options))
(defn overlap?
  "Native BVH contact/intersection/containment; optional :tolerance."
  [a b & options] (apply spatial/overlap? a b options))
(defn manifold? [x] (rt/instance-of? "Manifold" x))
(defn cross-section? [x] (rt/instance-of? "CrossSection" x))
(defn csg? [x] (or (model? x) (manifold? x) (cross-section? x)))
(defn- kind [x]
  (cond (manifold? x) "Manifold" (cross-section? x) "CrossSection"
        :else (throw (ex-info "Expected a Manifold or CrossSection" {:value x}))))
(defn- finite? [x] (and (number? x) (js/Number.isFinite x)))
(defn- tuple! [label n xs]
  (when-not (and (sequential? xs) (= n (count xs)) (every? finite? xs))
    (throw (ex-info (str label " must contain " n " finite numbers") {:value xs})))
  (to-array xs))

(defn mesh
  "Create an owned JS MeshGL snapshot; metadata uses the native camelCase names."
  [& {:keys [tri-verts vert-pos vert-properties num-prop merge-from-vert merge-to-vert
              run-index run-original-id run-transform face-id halfedge-tangent]
       :or {num-prop 3}}]
  (let [options (js-obj "numProp" num-prop
                        "triVerts" (js/Uint32Array. (clj->js (mapcat identity tri-verts)))
                        "vertProperties" (js/Float32Array. (clj->js (or vert-properties (mapcat identity vert-pos)))))]
    (doseq [[key value] [["mergeFromVert" merge-from-vert] ["mergeToVert" merge-to-vert]
                         ["runIndex" run-index] ["runOriginalID" run-original-id] ["faceID" face-id]]]
      (when value (gobj/set options key (js/Uint32Array. (clj->js value)))))
    (doseq [[key value] [["runTransform" run-transform] ["halfedgeTangent" halfedge-tangent]]]
      (when value (gobj/set options key (js/Float32Array. (clj->js value)))))
    (rt/construct "Mesh" options)))

(defn manifold
  ([] (manifold (mesh)))
  ([mesh-data] (rt/construct "Manifold" (if (map? mesh-data) (clj->js mesh-data) mesh-data))))
(defn is-empty? [x] (rt/call x "isEmpty"))
(defn tetrahedron [] (rt/static "Manifold" "tetrahedron"))
(defn polyhedron [vertices faces] (rt/native "polyhedron" (clj->js vertices) (clj->js faces)))
(defn surface
  ([grid] (surface grid 1))
  ([grid pixel-width] (rt/native "surface" (clj->js grid) pixel-width)))
(defn cube
  ([xyz] (cube xyz false))
  ([xyz center?] (rt/static "Manifold" "cube" (tuple! "size" 3 xyz) center?))
  ([x y z] (cube [x y z] false))
  ([x y z center?] (cube [x y z] center?)))
(defn cylinder
  ([height radius] (cylinder height radius radius))
  ([height low high] (cylinder height low high 0))
  ([height low high segments] (cylinder height low high segments false))
  ([height low high segments center?] (rt/static "Manifold" "cylinder" height low high segments center?)))
(defn sphere
  ([radius] (sphere radius 0))
  ([radius segments] (rt/static "Manifold" "sphere" radius segments)))

(defn cross-section
  ([polygons] (cross-section polygons :non-zero))
  ([polygons rule]
   (if (cross-section? polygons) polygons
       (let [fill ({:even-odd "EvenOdd" :non-zero "NonZero" :positive "Positive" :negative "Negative"} rule)]
         (when-not fill (throw (ex-info "Unknown fill rule" {:fill-rule rule})))
         (rt/construct "CrossSection" (clj->js polygons) fill)))))
(defn to-polygons [section] (js->clj (rt/call section "toPolygons")))
(defn square
  ([x y] (square x y false))
  ([x y center?] (rt/static "CrossSection" "square" #js [x y] center?)))
(defn- circle-parameters [[ax ay] [bx by] [cx cy]]
  (let [d (* 2 (+ (* ax (- by cy)) (* bx (- cy ay)) (* cx (- ay by))))]
    (when (< (js/Math.abs d) 1e-12) (throw (ex-info "Circle points must not be collinear" {})))
    (let [aa (+ (* ax ax) (* ay ay)) bb (+ (* bx bx) (* by by)) cc (+ (* cx cx) (* cy cy))
          x (/ (+ (* aa (- by cy)) (* bb (- cy ay)) (* cc (- ay by))) d)
          y (/ (+ (* aa (- cx bx)) (* bb (- ax cx)) (* cc (- bx ax))) d)]
      [(js/Math.hypot (- ax x) (- ay y)) [x y]])))
(declare translate)
(defn circle
  ([radius] (circle radius 0))
  ([radius segments] (rt/static "CrossSection" "circle" radius segments))
  ([a b c] (circle a b c 0))
  ([a b c segments]
   (let [[radius center] (circle-parameters a b c) base (circle radius segments)]
     (try (translate base center) (finally (dispose! base))))))
(defn three-point-arc-points [a b c segments]
  (when-not (pos-int? segments) (throw (ex-info "segments must be a positive integer" {})))
  (let [[radius [x y]] (circle-parameters a b c)
        angle (fn [[px py]] (js/Math.atan2 (- py y) (- px x)))
        tau (* 2 js/Math.PI) start (angle a)
        middle (mod (- (angle b) start) tau) end (mod (- (angle c) start) tau)
        sweep (if (<= middle end) end (- end tau))]
    (mapv (fn [i] (let [t (+ start (* sweep (/ i segments)))]
                    [(+ x (* radius (js/Math.cos t))) (+ y (* radius (js/Math.sin t)))]))
          (range (inc segments)))))
(defn three-point-arc [a b c segments] (cross-section (three-point-arc-points a b c segments)))

(defn- with-section [value f]
  (if (cross-section? value) (f value)
      (let [section (cross-section value)] (try (f section) (finally (dispose! section))))))
(defn extrude
  ([section height] (extrude section height 0))
  ([section height divisions] (extrude section height divisions 0))
  ([section height divisions twist] (extrude section height divisions twist [1 1]))
  ([section height divisions twist top]
   (with-section section #(rt/call % "extrude" height divisions twist (tuple! "scale-top" 2 top)))))
(defn revolve
  ([section] (revolve section 0))
  ([section segments] (revolve section segments 360))
  ([section segments degrees] (with-section section #(rt/call % "revolve" segments degrees))))

(defn- batch [method objects]
  (let [objects (vec objects)]
    (when (empty? objects) (throw (ex-info "At least one operand is required" {})))
    (if (some model? objects)
      (if (= method "compose")
        (reduce #(rt/call %1 "compose" (model %2)) (model (first objects)) (rest objects))
        (let [op ({"union" 0 "difference" 1 "intersection" 2} method)]
        (when-not (some? op) (throw (ex-info "This operation needs an explicit appearance policy for Model" {:operation method})))
        (reduce #(rt/call %1 "booleanOp" (model %2) op) (model (first objects)) (rest objects))))
      (let [class-name (kind (first objects))]
      (when-not (every? #(= class-name (kind %)) objects)
        (throw (ex-info "Cannot mix Manifold and CrossSection operands" {})))
      (rt/static class-name method (to-array objects))))))
(defn union
  ([a] (batch "union" (if (sequential? a) a [a])))
  ([a b & more] (batch "union" (list* a b more))))
(defn difference
  ([a] (batch "difference" (if (sequential? a) a [a])))
  ([a b & more] (batch "difference" (list* a b more))))
(defn intersection
  ([a] (batch "intersection" (if (sequential? a) a [a])))
  ([a b & more] (batch "intersection" (list* a b more))))
(defn hull
  ([a] (batch "hull" (if (sequential? a) a [a])))
  ([a b & more] (batch "hull" (list* a b more))))
(defn compose
  ([objects] (batch "compose" (if (sequential? objects) objects [objects])))
  ([a b & more] (batch "compose" (list* a b more))))
(defn decompose [object] (vec (array-seq (rt/call object "decompose"))))

(defrecord Frame [columns])
(defn frame
  ([] (frame 1))
  ([v] (->Frame [[v 0 0] [0 v 0] [0 0 v] [0 0 0]]))
  ([x y z position] (->Frame (mapv vec [x y z position])))
  ([a b c d e f g h i j k l] (frame [a b c] [d e f] [g h i] [j k l])))
(defn frame-2d
  ([] (frame-2d 1))
  ([v] (->Frame [[v 0] [0 v] [0 0]]))
  ([x position] (frame-2d x [(second x) (- (first x))] position))
  ([x y position] (->Frame (mapv vec [x y position])))
  ([a b c d e f] (frame-2d [a b] [c d] [e f])))
(defn- dot [a b] (reduce + (map * a b)))
(defn- cross [[a b c] [x y z]] [(- (* b z) (* c y)) (- (* c x) (* a z)) (- (* a y) (* b x))])
(defn- matrix-vector [columns v]
  (mapv #(reduce + (map (fn [column x] (* (nth column %) x)) columns v)) (range (count (first columns)))))
(defn- normalize [v]
  (let [length (js/Math.sqrt (dot v v))] (mapv #(/ % length) v)))
(defn compose-frames
  "Compose frames and normalize the resulting basis, matching the JVM API."
  ([a b]
   (let [ac (:columns a) bc (:columns b) n (dec (count ac)) basis (subvec ac 0 n)]
     (->Frame (conj (mapv #(normalize (matrix-vector basis %)) (subvec bc 0 n))
                    (mapv + (ac n) (matrix-vector basis (bc n)))))))
  ([a b & more] (reduce compose-frames (compose-frames a b) more)))
(defn invert-frame [f]
  ;; Like MatrixTransforms/InvertTransform, this is a rigid-frame inverse:
  ;; transpose the basis, not a general affine matrix inverse.
  (let [columns (:columns f) n (dec (count columns)) basis (subvec columns 0 n)
        inverse (apply mapv vector basis)]
    (->Frame (conj inverse (mapv - (matrix-vector inverse (columns n)))))))
(defn translate [object vector]
  (if (instance? Frame object)
    (let [columns (:columns object) n (dec (count columns))]
      (->Frame (assoc columns n (mapv + (columns n) (matrix-vector (subvec columns 0 n) vector)))))
    (rt/call object "translate" (clj->js vector))))
(defn tx [object x] (translate object [x 0 0]))
(defn ty [object y] (translate object [0 y 0]))
(defn tz [object z] (translate object [0 0 z]))
(defn- rotate-vector [v axis angle]
  (let [c (js/Math.cos angle) s (js/Math.sin angle) d (* (dot axis v) (- 1 c))]
    (mapv + (mapv #(* c %) v) (mapv #(* s %) (cross axis v)) (mapv #(* d %) axis))))
(defn rotate [object angles]
  (if (instance? Frame object)
    (if (= 3 (count (:columns object)))
      (let [c (js/Math.cos angles) s (js/Math.sin angles)]
        (->Frame (conj (mapv (fn [[x y]] [(- (* c x) (* s y)) (+ (* s x) (* c y))])
                             (subvec (:columns object) 0 2)) (last (:columns object)))))
      (->Frame
        (reduce (fn [columns [axis angle]]
                  (if (zero? angle) columns
                      (assoc columns
                             (mod (inc axis) 3) (rotate-vector (columns (mod (inc axis) 3)) (columns axis) angle)
                             (mod (+ 2 axis) 3) (rotate-vector (columns (mod (+ 2 axis) 3)) (columns axis) angle))))
                (:columns object) (map-indexed vector angles))))
    (rt/call object "rotate" (clj->js angles))))
(defn transform [object f]
  (let [columns (:columns f) dim (if (cross-section? object) 2 3)
        matrix (if columns
                 (vec (mapcat #(conj (vec %1) %2) columns (if (= dim 3) [0 0 0 1] [0 0 1])))
                 f)]
    (rt/call object "transform" (clj->js matrix))))
(defn scale [object factors] (rt/call object "scale" (clj->js factors)))
(defn mirror [object normal] (rt/call object "mirror" (clj->js normal)))
(defn bounds [object]
  (let [value (rt/call object (if (cross-section? object) "bounds" "boundingBox"))]
    {:min (vec (array-seq (gobj/get value "min"))) :max (vec (array-seq (gobj/get value "max")))}))
(defn get-height [object]
  (let [{:keys [min max]} (bounds object)] (- (peek max) (peek min))))
(defn center
  ([object] (center object #{:x :y}))
  ([object axes]
   (let [{:keys [min max]} (bounds object) axes (set axes)]
     (translate object (mapv #(if (axes %3) (- (/ (+ %1 %2) 2)) 0) min max [:x :y :z])))))
(defn snap
  ([object] (snap object #{:x}))
  ([object axes]
   (let [{:keys [min]} (bounds object) axes (set axes)]
     (translate object (mapv #(if (axes %2) (- %1) 0) min [:x :y :z])))))
(defn scale-to-height [object height]
  (scale object (vec (repeat (if (cross-section? object) 2 3) (/ height (get-height object))))))
(defn get-properties [object] {:volume (rt/call object "volume") :surface-area (rt/call object "surfaceArea")})
(defn area [section] (rt/call section "area"))
(defn trim-by-plane
  ([object normal] (trim-by-plane object normal 0))
  ([object normal offset]
   (when (model? object) (throw (ex-info "Plane cuts require a new-surface appearance policy; cut before texturing" {})))
   (rt/call object "trimByPlane" (clj->js normal) offset)))
(defn split-by-plane
  ([object normal] (split-by-plane object normal 0))
  ([object normal offset]
   (when (model? object) (throw (ex-info "Plane cuts require a new-surface appearance policy; cut before texturing" {})))
   (vec (array-seq (rt/call object "splitByPlane" (clj->js normal) offset)))))
(defn split [object cutter]
  (if (or (model? object) (model? cutter))
    [(intersection object cutter) (difference object cutter)]
    (vec (array-seq (rt/call object "split" cutter)))))
(defn project [object] (rt/call object "project"))
(defn slice
  ([object] (slice object 0))
  ([object height] (rt/call object "slice" height)))
(defn slices [object bottom top n]
  (when-not (pos-int? n) (throw (ex-info "n-slices must be positive" {})))
  (mapv #(slice object (+ bottom (* (- top bottom) (if (= n 1) 0 (/ % (dec n)))))) (range n)))
(defn smooth
  ([mesh] (smooth mesh []))
  ([mesh sharp] (rt/static "Manifold" "smooth" mesh (clj->js sharp))))
(defn smooth-out
  ([object] (smooth-out object 60))
  ([object angle] (smooth-out object angle 0))
  ([object angle smoothness] (rt/call object "smoothOut" angle smoothness)))
(defn refine [object n] (rt/call object "refine" n))
(defn refine-to-length [object length] (rt/call object "refineToLength" length))
(defn calculate-normals [object index angle] (rt/call object "calculateNormals" index angle))
(defn warp [object f]
  (rt/call object "warp" (fn [v] (let [result (f (vec (array-seq v)))]
                                 (doseq [i (range (count result))] (aset v i (nth result i)))))))
(defn offset
  ([section delta] (offset section delta :square))
  ([section delta join] (offset section delta join 2))
  ([section delta join limit] (offset section delta join limit 0))
  ([section delta join limit segments]
   (let [join-name ({:square "Square" :round "Round" :miter "Miter"} join)]
     (when-not join-name (throw (ex-info "Unknown join type" {:join-type join})))
     (rt/call section "offset" delta join-name limit segments))))
(defn simplify [section epsilon] (rt/call section "simplify" epsilon))
(defn get-mesh
  ([object] (rt/call object "getMesh"))
  ([object normal-index] (rt/call object "getMesh" normal-index)))
(def get-mesh-gl get-mesh)
(defn status [object]
  (nth [:NoError :NonFiniteVertex :NotManifold :VertexOutOfBounds :PropertiesWrongLength
        :MissingPositionProperties :MergeVectorsDifferentLengths :MergeIndexOutOfBounds
        :TransformWrongLength :RunIndexWrongLength :FaceIDWrongLength :InvalidConstruction]
       (gobj/get (rt/call object "status") "value")))
(defn color
  ([object rgba] (color object rgba 3))
  ([object rgba index]
   (when-not (and (integer? index) (<= 3 index 1024))
     (throw (ex-info "Color property index must be an integer between 3 and 1024" {:prop-index index})))
   (if (model? object)
     (do (when-not (= index 3) (throw (ex-info "Model manages its color channels" {})))
         (rt/call object "color" (tuple! "color" 4 rgba)))
     (rt/native "colorVertices" object (tuple! "color" 4 rgba) index))))

(defn loft
  ([segments]
   (when-not (:cross-section (first segments))
     (throw (ex-info "First loft segment must contain :cross-section" {})))
   (let [steps (rest (reductions (fn [{:keys [cross-section frame]} step]
                                   {:cross-section (if-let [f (:cross-section-fn step)] (f cross-section) (or (:cross-section step) cross-section))
                                    :frame (if-let [f (:frame-fn step)] (f frame) (or (:frame step) frame))})
                                 {:frame (or (:frame (first segments)) (frame))
                                  :cross-section (:cross-section (first segments))} segments))]
     (loft (mapv :cross-section steps) (mapv :frame steps) (:algorithm (first segments) :eager-nearest-neighbor))))
  ([sections frames] (loft sections frames :eager-nearest-neighbor))
  ([sections frames algorithm]
   (let [sections (if (cross-section? sections) (repeat (count frames) sections) sections)
         owned (atom [])]
     (when-not (= (count sections) (count frames)) (throw (ex-info "Sections and frames must match" {})))
     (try
       (let [sections (mapv #(if (cross-section? %) %
                              (let [s (cross-section %)] (swap! owned conj s) s)) sections)]
         (rt/native "loft" (to-array sections) (clj->js (mapv #(vec (mapcat identity (:columns %))) frames)) (name algorithm)))
       (finally (apply dispose! @owned))))))

(defprotocol IHalfEdge (is-forward [this]))
(defrecord Halfedge [start-vert end-vert paired-halfedge]
  IHalfEdge (is-forward [_] (< start-vert end-vert)))
(defrecord Edge [start-vert end-vert])
(defn- topology [object]
  (let [mesh (get-mesh object) properties (gobj/get mesh "vertProperties") stride (gobj/get mesh "numProp")
        from (gobj/get mesh "mergeFromVert") to (gobj/get mesh "mergeToVert")
        merges (zipmap (array-seq from) (array-seq to))
        root (fn [i] (loop [i i] (if-let [j (get merges i)] (recur j) i)))
        roots (mapv root (range (/ (alength properties) stride)))
        ids (zipmap (distinct roots) (range))]
    {:vertices (mapv (fn [i] (mapv #(aget properties (+ (* i stride) %)) (range 3))) (distinct roots))
     :triangles (mapv vec (partition 3 (map #(ids (roots %)) (array-seq (gobj/get mesh "triVerts")))))}))
(defn get-vertices [object] (:vertices (topology object)))
(defn get-triangles [object] (:triangles (topology object)))
(defn get-halfedges [object]
  (let [edges (vec (mapcat (fn [[a b c]] [[a b] [b c] [c a]]) (get-triangles object)))
        indices (zipmap edges (range))]
    (mapv (fn [[a b]] (->Halfedge a b (indices [b a]))) edges)))
(defn get-edges [object]
  (->> (get-halfedges object) (map (fn [{:keys [start-vert end-vert]}]
                                  [(min start-vert end-vert) (max start-vert end-vert)]))
       distinct sort (mapv #(apply ->Edge %))))
(defn get-face-normals [object]
  (let [{:keys [vertices triangles]} (topology object)]
    (mapv (fn [[a b c]] (let [n (cross (mapv - (vertices b) (vertices a)) (mapv - (vertices c) (vertices a)))
                              length (js/Math.sqrt (dot n n))] (mapv #(/ % length) n))) triangles)))

(defn text
  "Promise of a CrossSection; font may be TTF bytes, a filename, or a URL."
  ([font content] (text font content 10))
  ([font content height] (text font content height 10))
  ([font content height resolution] (text font content height resolution :even-odd))
  ([font content height resolution fill-rule]
   (let [fill ({:even-odd 0 :non-zero 1 :positive 2 :negative 3} fill-rule)]
     (when-not (some? fill) (throw (ex-info "Unknown fill rule" {:fill-rule fill-rule})))
     (rt/with-native-file font #(rt/native "text" % content height resolution fill)))))
(defn load-surface
  ([source] (load-surface source 1))
  ([source pixel-width] (rt/with-native-file source #(rt/native "loadSurface" % pixel-width))))
(defn load-image
  ([source depth] (load-image source depth 1))
  ([source depth pixel-width] (rt/with-native-file source #(rt/native "loadImage" % depth pixel-width))))
(defn ply-file-to-surface
  ([source] (ply-file-to-surface source 10 20 304.8))
  ([source cell-size] (ply-file-to-surface source cell-size 20 304.8))
  ([source cell-size offset] (ply-file-to-surface source cell-size offset 304.8))
  ([source cell-size offset scale] (rt/with-native-file source #(rt/native "plyToSurface" % cell-size offset scale))))

(defn export-model [model filename & options]
  (cond (model? model) (apply native-model/export-model model filename options)
        (scene? model) (export-scene model filename)
        :else (apply export-mesh model filename options)))
