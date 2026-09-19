(ns clj-manifold3d.scene
  "Immutable embedded GLB scene editing and rigid-pose inspection (JVM).
  Keep this scene for rendering: `solid` deliberately extracts geometry only."
  (:require [clj-manifold3d.animation :as animation]
            [clj-manifold3d.glb :as glb]
            [clj-manifold3d.core :as m]))

(def scene animation/scene)
(def scene? animation/scene?)
(def document animation/scene-document)
(def import-scene glb/read-glb)
(def export-scene animation/export-scene)

(defn append
  "Append the other scene's active roots and all its assets/animation clips.
  Existing node indices stay stable; appended indices are offset."
  [value other]
  (glb/append-document (document value) (document other)))

(defn node-index
  "Resolve an integer index or unique node name. Keywords also match authored IDs."
  [value selector]
  (let [nodes (get-in (document value) [:gltf "nodes"])
        ids (if (integer? selector)
              (when (< -1 selector (count nodes)) [selector])
              (keep-indexed (fn [i n]
                              (when (contains? (if (keyword? selector)
                                                 #{(str selector) (name selector)} #{selector})
                                               (get n "name")) i)) nodes))]
    (when-not (= 1 (count ids))
      (throw (ex-info "Node selector must match exactly one node" {:selector selector :matches (vec ids)})))
    (first ids)))

(defn node
  "Get a node with keyword top-level keys (nested glTF/extras stay unchanged).
  Transform keys are :translation, :rotation, :scale or :matrix."
  [value selector]
  (let [doc (document value)]
    (into {} (map (fn [[k v]] [(keyword k) v])
                  (get-in doc [:gltf "nodes" (node-index doc selector)])))))

(defn- finite? [x] (and (number? x) (Double/isFinite (double x))))
(defn- validate-transform! [n]
  (doseq [[k size] [["translation" 3] ["rotation" 4] ["scale" 3] ["matrix" 16]]]
    (when-let [v (get n k)]
      (when-not (and (sequential? v) (= size (count v)) (every? finite? v))
        (throw (ex-info "Invalid node transform" {:key k :value v})))))
  (when (and (get n "matrix") (some #(contains? n %) ["translation" "rotation" "scale"]))
    (throw (ex-info "Use matrix or TRS, not both" {})))
  n)

(defn- graph
  "Validate a forest; return parent indices."
  [doc]
  (let [nodes (get-in doc [:gltf "nodes"] [])
        parents (reduce-kv
                 (fn [ps i n]
                   (reduce (fn [ps child]
                             (when-not (and (integer? child) (< -1 child (count nodes)) (not (contains? ps child)))
                               (throw (ex-info "Invalid child or multiple parents" {:parent i :child child})))
                             (assoc ps child i)) ps (get n "children" []))) {} nodes)]
    (doseq [i (range (count nodes))]
      (loop [p i seen #{}]
        (when (some? p)
          (when (seen p) (throw (ex-info "Cyclic scene hierarchy" {:node i})))
          (recur (parents p) (conj seen p)))))
    (doseq [s (get-in doc [:gltf "scenes"])
            root (get s "nodes")]
      (when-not (and (integer? root) (< -1 root (count nodes)) (not (contains? parents root)))
        (throw (ex-info "Invalid scene root" {:root root}))))
    parents))

(defn update-node
  "Apply f to the keyword-keyed node, retaining all other assets and metadata.
  Prefer wrap-node for offsets to animated nodes: their animated TRS is unchanged."
  [value selector f & args]
  (let [doc (document value) i (node-index doc selector)
        n (into {} (map (fn [[k v]] [(name k) v]) (apply f (node doc i) args)))
        out (assoc-in doc [:gltf "nodes" i] (validate-transform! n))]
    (when (and (get n "matrix")
               (some #(= i (get-in % ["target" "node"]))
                     (mapcat #(get % "channels") (get-in doc [:gltf "animations"]))))
      (throw (ex-info "An animated node cannot use a matrix; wrap it instead" {:node i})))
    (graph out)
    out))

(defn wrap-node
  "Insert a transform-only parent around a node, retaining its animation.
  transform uses keyword TRS or column-major :matrix. Offset is parent-local."
  [value selector transform]
  (when-not (and (map? transform) (every? #{:translation :rotation :scale :matrix} (keys transform)))
    (throw (ex-info "wrap-node accepts only transform fields" {:transform transform})))
  (let [doc (document value) i (node-index doc selector)
        parent ((graph doc) i) w (count (get-in doc [:gltf "nodes"]))
        wrapper (validate-transform! (into {"name" (str (get-in doc [:gltf "nodes" i "name"]) " offset")
                                           "children" [i]}
                                          (map (fn [[k v]] [(name k) v]) transform)))
        _ (when-not (= [i] (get wrapper "children"))
            (throw (ex-info "wrap-node only accepts transform fields" {})))
        replace-id #(mapv (fn [x] (if (= x i) w x)) %)
        doc (update-in doc [:gltf "nodes"] conj wrapper)]
    (if (some? parent)
      (update-in doc [:gltf "nodes" parent "children"] replace-id)
      (update-in doc [:gltf "scenes"] #(mapv (fn [s] (update s "nodes" replace-id)) %)))))

(defn remove-node
  "Detach a node and its subtree from every scene. Asset tables, skins and clips
  stay intact, keeping indices stable; this does not compact unused binary data."
  [value selector]
  (let [doc (document value) i (node-index doc selector)
        without #(vec (remove #{i} %))]
    (-> doc
        (update-in [:gltf "nodes"] #(mapv (fn [n] (cond-> n (get n "children") (update "children" without))) %))
        (update-in [:gltf "scenes"] #(mapv (fn [s] (update s "nodes" without)) %)))))

(def ^:private identity-matrix [1 0 0 0, 0 1 0 0, 0 0 1 0, 0 0 0 1])
(defn- multiply [a b]
  (vec (for [col (range 4) row (range 4)]
         (reduce + (for [k (range 4)] (* (a (+ row (* 4 k))) (b (+ k (* 4 col)))))))))
(defn- normalize-q [q]
  (let [length (Math/sqrt (reduce + (map #(* % %) q)))]
    (when-not (and (Double/isFinite length) (pos? length)) (throw (ex-info "Invalid quaternion" {:rotation q})))
    (mapv #(/ % length) q)))
(defn- local-matrix [n]
  (validate-transform! n)
  (or (get n "matrix")
      (let [[x y z w] (normalize-q (get n "rotation" [0 0 0 1]))
            [sx sy sz] (get n "scale" [1 1 1]) [tx ty tz] (get n "translation" [0 0 0])]
        [(* sx (- 1 (* 2 (+ (* y y) (* z z))))) (* sx 2 (+ (* x y) (* z w))) (* sx 2 (- (* x z) (* y w))) 0
         (* sy 2 (- (* x y) (* z w))) (* sy (- 1 (* 2 (+ (* x x) (* z z))))) (* sy 2 (+ (* y z) (* x w))) 0
         (* sz 2 (+ (* x z) (* y w))) (* sz 2 (- (* y z) (* x w))) (* sz (- 1 (* 2 (+ (* x x) (* y y))))) 0
         tx ty tz 1])))

(defn- interpolate [a b u rotation?]
  (if-not rotation? (mapv #(+ (* (- 1 u) %1) (* u %2)) a b)
    ;; Reuse the rigid-track quaternion interpolation (shortest-arc SLERP).
    (:rotation (animation/sample
                [{:time 0 :translation [0 0 0] :scale [1 1 1] :rotation a}
                 {:time 1 :translation [0 0 0] :scale [1 1 1] :rotation b}] u))))

(defn- sample-channel [doc sampler path t]
  (let [times (mapv first (glb/accessor doc (get sampler "input")))
        values (glb/accessor doc (get sampler "output"))
        interpolation (get sampler "interpolation" "LINEAR") cubic? (= "CUBICSPLINE" interpolation)
        n (count times) width (case path "rotation" 4 ("translation" "scale") 3
                                   (throw (ex-info "Pose sampling supports rigid TRS channels only" {:path path})))
        _ (when-not (and (pos? n) (every? finite? times) (not (neg? (first times)))
                         (or (= n 1) (apply < times))
                         (= (count values) (* n (if cubic? 3 1)))
                         (every? #(and (= width (count %)) (every? finite? %)) values)
                         (#{"LINEAR" "STEP" "CUBICSPLINE"} interpolation))
            (throw (ex-info "Invalid animation sampler" {:sampler sampler})))
        t (max (first times) (min (last times) t))
        i (max 0 (dec (count (take-while #(<= % t) times))))
        j (min (dec n) (inc i)) dt (- (times j) (times i))
        u (if (zero? dt) 0 (/ (- t (times i)) dt))
        a (values (if cubic? (inc (* 3 i)) i)) b (values (if cubic? (inc (* 3 j)) j))
        result (cond
                 (or (= i j) (= "STEP" interpolation)) a
                 cubic? (let [u2 (* u u) u3 (* u u u)
                              h00 (+ (* 2 u3) (* -3 u2) 1) h10 (+ u3 (* -2 u2) u)
                              h01 (+ (* -2 u3) (* 3 u2)) h11 (- u3 u2)]
                          (mapv #(+ (* h00 %1) (* h10 dt %2) (* h01 %3) (* h11 dt %4))
                                a (values (+ 2 (* 3 i))) b (values (* 3 j))))
                 :else (interpolate a b u (= path "rotation")))]
    (if (= path "rotation") (normalize-q result) result)))

(defn sample-scene
  "Freeze a rigid pose at time in seconds; clamp to each sampler's range.
  :animation selects a clip index or unique name (default 0). nil selects rest
  pose. All clips are removed from the returned snapshot; source is unchanged.
  LINEAR, STEP and CUBICSPLINE TRS channels are supported."
  [value t & {:keys [animation] :or {animation 0}}]
  (when-not (finite? t) (throw (ex-info "Sample time must be finite" {:time t})))
  (let [doc (document value) clips (get-in doc [:gltf "animations"] [])
        clip (cond
               (or (nil? animation) (and (= animation 0) (empty? clips))) nil
               (integer? animation) (or (get clips animation) (throw (ex-info "Unknown animation" {:animation animation})))
               :else (let [matches (filter #(= animation (get % "name")) clips)]
                       (when-not (= 1 (count matches)) (throw (ex-info "Animation name must be unique" {:animation animation})))
                       (first matches)))
        posed (reduce (fn [d channel]
                        (let [i (get-in channel ["target" "node"]) path (get-in channel ["target" "path"])]
                          (node-index doc i)
                          (when (get-in d [:gltf "nodes" i "matrix"])
                            (throw (ex-info "Animated node has a matrix" {:node i})))
                          (assoc-in d [:gltf "nodes" i path]
                                    (sample-channel doc (get-in clip ["samplers" (get channel "sampler")]) path t))))
                      doc (get clip "channels" []))]
    (update posed :gltf dissoc "animations")))

(defn world-transforms
  "Map active node indices to column-major world matrices, including hierarchy.
  Pass a sample-scene snapshot to inspect an animated pose."
  [value]
  (let [doc (document value) _ (graph doc) nodes (get-in doc [:gltf "nodes"])]
    (letfn [(visit [out i parent]
              (let [n (nodes i) world (multiply parent (local-matrix n))]
                (reduce #(visit %1 %2 world) (assoc out i world) (get n "children" []))))]
      (reduce #(visit %1 %2 identity-matrix) {}
              (get-in doc [:gltf "scenes" (get-in doc [:gltf "scene"] 0) "nodes"] [])))))

(defn- transform-point [matrix p]
  (mapv (fn [row] (+ (matrix (+ 12 row))
                     (reduce + (map-indexed (fn [col x] (* x (matrix (+ row (* col 4))))) p)))) (range 3)))

(defn- reflected? [m]
  (neg? (+ (* (m 0) (- (* (m 5) (m 10)) (* (m 9) (m 6))))
           (* (m 4) (- (* (m 9) (m 2)) (* (m 1) (m 10))))
           (* (m 8) (- (* (m 1) (m 6)) (* (m 5) (m 2)))))))

(defn- geometry-parts [doc selector]
  (let [worlds (world-transforms doc) nodes (get-in doc [:gltf "nodes"])
        selected (if (nil? selector) (set (keys worlds))
                   (letfn [(descendants [i] (cons i (mapcat descendants (get (nodes i) "children" []))))]
                     (set (descendants (node-index doc selector)))))
        _ (when-not (every? #(contains? worlds %) selected)
            (throw (ex-info "Selected node is detached from the active scene" {:selector selector})))]
    (vec (for [i (sort selected) :let [n (nodes i)]
               :when (contains? n "mesh")
               p (get-in doc [:gltf "meshes" (get n "mesh") "primitives"])]
           (do
             (when (or (contains? n "skin") (seq (get p "targets")) (not= 4 (get p "mode" 4))
                       (get-in p ["extensions" "KHR_draco_mesh_compression"]))
               (throw (ex-info "Geometry queries require uncompressed rigid triangle meshes" {:node i})))
             (let [positions (glb/accessor doc (get-in p ["attributes" "POSITION"]))
                   indices (if (contains? p "indices") (mapv first (glb/accessor doc (get p "indices")))
                             (vec (range (count positions))))]
               (when-not (and (every? #(= 3 (count %)) positions) (zero? (mod (count indices) 3))
                              (every? #(and (integer? %) (< -1 % (count positions))) indices))
                 (throw (ex-info "Invalid triangle primitive" {:node i})))
               {:node i :positions (mapv #(transform-point (worlds i) %) positions)
                :triangles (mapv #(if (reflected? (worlds i)) (vec (reverse %)) (vec %))
                                 (partition 3 indices))}))))))

(defn vertices
  "World-space render vertices of the active scene or selected subtree.
  UV/normal seam duplicates are retained. Open triangle meshes are allowed."
  ([value] (vertices value nil))
  ([value selector] (vec (mapcat :positions (geometry-parts (document value) selector)))))

(defn bounds
  "World-space {:min [x y z] :max [x y z]}, or nil for no geometry."
  ([value] (bounds value nil))
  ([value selector]
   (reduce (fn [b p] (if b {:min (mapv min (:min b) p) :max (mapv max (:max b) p)} {:min p :max p}))
           nil (vertices value selector))))

(defn- parts-solid [parts]
  (let [[positions triangles] (reduce (fn [[vs ts] p]
                                         [(into vs (:positions p))
                                          (into ts (map #(mapv (partial + (count vs)) %) (:triangles p)))])
                                       [[] []] parts)]
     (if (empty? triangles) (m/manifold)
       (with-open [mesh (m/mesh :vert-pos positions :tri-verts triangles)
                   merged (m/mesh-merge mesh)]
         (let [result (m/manifold merged)]
           (if (= :NoError (m/status result)) result
             (let [status (m/status result)]
               (.close result)
               (throw (ex-info "Scene geometry is not a closed Manifold" {:status status :node (:node (first parts))})))))))))

(defn solid
  "Extract the boolean union of closed node meshes in the active scene or selected
  subtree at its current pose. Reconstructs seam merges natively. Appearance stays
  in the scene, not in this geometry-only result. Rejects non-watertight meshes."
  ([value] (solid value nil))
  ([value selector]
   (let [parts (vals (group-by :node (geometry-parts (document value) selector)))]
     (if (empty? parts) (m/manifold)
       (reduce (fn [acc part]
                 (with-open [a acc b (parts-solid part)] (m/union a b)))
               (parts-solid (first parts)) (rest parts))))))
