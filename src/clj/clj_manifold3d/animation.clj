(ns clj-manifold3d.animation
  "JVM rigid animation tracks, scene graphs, and a glTF/GLB scene exporter.

  Geometry is authored in local coordinates; node transforms and animation
  tracks are written separately."
  (:require [clj-manifold3d.model :as model]
            [clj-manifold3d.glb :as glb]
            [clj-manifold3d.scene-features :as features])
  (:import [java.nio ByteBuffer ByteOrder]
           [manifold3d Manifold]
           [manifold3d.linalg DoubleVec3]))

(defn- finite-number? [x]
  (and (number? x) (Double/isFinite (double x))))

(defn- assert-vector! [name value n]
  (when-not (and (vector? value)
                 (= n (count value))
                 (every? finite-number? value))
    (throw (ex-info (str name " must be a finite numeric vector of length " n)
                    {:name name :value value :length n}))))

(defn- validate-frame! [{:keys [time translation rotation scale] :as frame}]
  (when-not (finite-number? time)
    (throw (ex-info ":time must be finite" {:frame frame})))
  (assert-vector! ":translation" translation 3)
  (assert-vector! ":rotation" rotation 4)
  (when (< (reduce + (map #(* % %) rotation)) 1.0e-24)
    (throw (ex-info "Quaternion must have non-zero length" {:frame frame})))
  (assert-vector! ":scale" scale 3)
  frame)

(defn- validate-keyframes! [frames]
  (let [frames (vec frames)]
    (when (empty? frames)
      (throw (ex-info "Animation requires at least one keyframe" {})))
    (doseq [[a b] (partition 2 1 frames)]
      (when-not (< (:time a) (:time b))
        (throw (ex-info "Keyframe times must be strictly increasing"
                        {:a a :b b}))))
    (mapv validate-frame! frames)))

(defn keyframes
  "Create a validated immutable rigid-transform track.

  Frames contain :time, :translation (3), :rotation quaternion (x y z w),
  and :scale (3). Times are in seconds."
  [frames]
  (validate-keyframes! frames))

(defn- lerp [a b u]
  (+ (double a) (* u (- (double b) (double a)))))

(defn- lerp-vector [a b u]
  (mapv #(lerp %1 %2 u) a b))

(defn- dot4 [a b]
  (reduce + (map * a b)))

(defn- normalize4 [q]
  (let [length (Math/sqrt (dot4 q q))]
    (when (< length 1.0e-12)
      (throw (ex-info "Quaternion must have non-zero length" {:rotation q})))
    (mapv #(/ (double %) length) q)))

(defn- slerp [a b u]
  (let [a (normalize4 a)
        b0 (normalize4 b)
        cosine (dot4 a b0)
        [b cosine] (if (neg? cosine) [(mapv - b0) (- cosine)] [b0 cosine])]
    (if (> cosine 0.9995)
      (normalize4 (lerp-vector a b u))
      (let [theta (Math/acos (max -1.0 (min 1.0 cosine)))
            sin-theta (Math/sin theta)
            wa (/ (Math/sin (* (- 1.0 u) theta)) sin-theta)
            wb (/ (Math/sin (* u theta)) sin-theta)]
        (normalize4 (mapv #(+ (* wa %1) (* wb %2)) a b))))))

(defn- bracket [frames time]
  (let [time (double time)]
    (cond
      (<= time (:time (first frames))) [(first frames) (first frames) 0.0]
      (>= time (:time (last frames))) [(last frames) (last frames) 0.0]
      :else
      (let [[_ [a b]] (first (keep-indexed
                              (fn [i [a b]]
                                (when (<= (:time a) time (:time b)) [i [a b]]))
                              (partition 2 1 frames)))
            u (/ (- time (:time a)) (- (:time b) (:time a)))]
        [a b (max 0.0 (min 1.0 u))]))))

(defn sample
  "Sample a rigid-transform track at time `t`, clamping outside its range."
  [frames t]
  (when-not (finite-number? t)
    (throw (ex-info "Sample time must be finite" {:time t})))
  (let [frames (validate-keyframes! frames)
        [a b u] (bracket frames t)]
    {:time (double t)
     :translation (lerp-vector (:translation a) (:translation b) u)
     :rotation (slerp (:rotation a) (:rotation b) u)
     :scale (lerp-vector (:scale a) (:scale b) u)}))

(defn sample-times
  "Sample `frames` at every time in `times`, preserving time order."
  [frames times]
  (mapv #(sample frames %) times))

(def ^:private scene-type :clj-manifold3d.animation/scene)

(defn scene?
  "Return true when `value` is a validated animation scene."
  [value]
  (or (= scene-type (:model/type value)) (glb/document? value)))

(defn- normalize-node [index node]
  (features/validate-node! node)
  (features/validate-material! (:material node))
  (let [id (or (:id node) (:name node))
        children (vec (or (:children node) []))
        transform (merge (select-keys node [:translation :rotation :scale :matrix])
                         (:transform node))]
    (when-not (some? id)
      (throw (ex-info "Scene nodes require an :id" {:index index :node node})))
    (doseq [[key size] [[:translation 3] [:rotation 4] [:scale 3]]]
      (when (contains? transform key)
        (assert-vector! (str ":transform/" (name key)) (get transform key) size)))
    (when-let [matrix (:matrix transform)]
      (assert-vector! ":transform/matrix" matrix 16)
      (when (some #(contains? transform %) [:translation :rotation :scale])
        (throw (ex-info "Use matrix or TRS, not both" {:node id}))))
    (assoc (select-keys node [:name :geometry :material :extras :light :camera])
           :id id
           :children children
           :transform transform)))

(defn- cycle? [children-by-id]
  (letfn [(walk [id visiting]
            (cond
              (contains? visiting id) true
              :else (some #(walk % (conj visiting id))
                          (get children-by-id id))))]
    (boolean (some #(walk % #{}) (keys children-by-id)))))

(defn- normalize-channel [node-ids channel]
  (let [path (keyword (:path channel))
        node (:node channel)]
    (when-not (contains? node-ids node)
      (throw (ex-info "Animation channel targets an unknown node"
                      {:node node :nodes node-ids})))
    (when-not (contains? #{:translation :rotation :scale} path)
      (throw (ex-info "Animation channel path must be :translation, :rotation, or :scale"
                      {:path path})))
    (when-not (#{"LINEAR" "STEP"} (or (:interpolation channel) "LINEAR"))
      (throw (ex-info "Authored channels support LINEAR or STEP" {:channel channel})))
    {:node node
     :path path
     :track (keyframes (:track channel))
     :interpolation (or (:interpolation channel) "LINEAR")}))

(defn- normalize-animation [node-ids animation]
  (let [channels (:channels animation)]
    (when-not (seq channels)
      (throw (ex-info "Animations require non-empty :channels"
                      {:animation animation})))
    (assoc (select-keys animation [:name :extras])
           :channels (mapv #(normalize-channel node-ids %) channels))))

(defn scene
  "Create a validated animation scene.

  A scene is a data-only map with `:nodes` and optional `:animations`.
  Nodes have stable `:id` values, optional Manifold `:geometry`, a nested
  `:transform` map, and optional `:children` IDs. Transform-only nodes are
  useful as pivots. Animation channels target a node and one transform path."
  [{:keys [name nodes animations extras]}]
  (let [nodes (vec (or nodes []))
        nodes (mapv normalize-node (range) nodes)
        ids (mapv :id nodes)
        node-ids (set ids)
        children-by-id (into {} (map (juxt :id :children) nodes))
        child-ids (set (mapcat :children nodes))]
    (when (empty? nodes)
      (throw (ex-info "Scenes require at least one node" {})))
    (when-not (= (count ids) (count node-ids))
      (throw (ex-info "Scene node IDs must be unique" {:ids ids})))
    (when-not (every? node-ids child-ids)
      (throw (ex-info "Scene contains an unknown child node"
                      {:children (vec (remove node-ids child-ids))})))
    (when (cycle? children-by-id)
      (throw (ex-info "Scene node hierarchy cannot contain cycles" {})))
    (when (some #(> % 1) (vals (frequencies (mapcat :children nodes))))
      (throw (ex-info "Scene nodes may have only one parent" {})))
    (doseq [animation animations]
      (let [targets (map (juxt :node :path) (:channels animation))]
        (when-not (= (count targets) (count (set targets)))
          (throw (ex-info "A clip cannot target a node path twice" {:targets targets}))))
      (doseq [channel (:channels animation)]
        (when (:matrix (:transform (first (filter #(= (:id %) (:node channel)) nodes))))
          (throw (ex-info "Animated nodes must use TRS, not matrix" {:node (:node channel)})))
        (when (some #(neg? (:time %)) (:track channel))
          (throw (ex-info "glTF animation times must be nonnegative" {:channel channel})))))
    {:model/type scene-type
     :name (or name "Scene")
     :nodes nodes
     :roots (vec (remove child-ids ids))
     :animations (mapv #(normalize-animation node-ids %) (or animations []))
     :extras extras}))

(defn- align4 [n]
  (+ n (mod (- 4 (mod n 4)) 4)))

(defn- floats->bytes [values]
  (let [buffer (doto (ByteBuffer/allocate (* 4 (count values)))
                 (.order ByteOrder/LITTLE_ENDIAN))]
    (doseq [value values]
      (.putFloat buffer (float value)))
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

(defn- add-accessor [state buffer-view component-type value-count type minimum maximum]
  (let [accessor (cond-> {"bufferView" buffer-view
                          "componentType" component-type
                          "count" value-count
                          "type" type}
                   minimum (assoc "min" minimum)
                   maximum (assoc "max" maximum))]
    [(update state :accessors conj accessor)
     (dec (count (conj (:accessors state) accessor)))]))

(defn- channel-values [path track]
  (vec (mapcat (if (= path :rotation) #(normalize4 (:rotation %)) path) track)))

(defn- channel-type [path]
  (case path
    :translation "VEC3"
    :rotation "VEC4"
    :scale "VEC3"))

(defn- add-animation [state animation node-indices]
  (loop [state state
         remaining (:channels animation)
         samplers []
         gltf-channels []]
    (if-let [{:keys [node path track interpolation]} (first remaining)]
      (let [node-index (get node-indices node)
            times (mapv :time track)
            values (channel-values path track)
            [state time-view] (add-segment state (floats->bytes times) nil)
            [state time-accessor] (add-accessor state time-view 5126 (count times)
                                                 "SCALAR" [(apply min times)]
                                                 [(apply max times)])
            [state value-view] (add-segment state (floats->bytes values) nil)
            [state value-accessor] (add-accessor state value-view 5126
                                                  (count track) (channel-type path)
                                                  nil nil)
            sampler-index (count samplers)]
        (recur state
               (next remaining)
               (conj samplers {"input" time-accessor
                               "output" value-accessor
                               "interpolation" interpolation})
               (conj gltf-channels {"sampler" sampler-index
                                    "target" {"node" node-index
                                              "path" (name path)}})))
      [state (cond-> {"name" (or (:name animation) "Animation")
                      "samplers" samplers
                      "channels" gltf-channels}
               (:extras animation) (assoc "extras" (:extras animation)))])))

(defn- node-transform [node mesh-index node-indices]
  (let [{:keys [id name children transform extras]} node]
    (cond-> {"name" (or name (str id))}
      (some? mesh-index) (assoc "mesh" mesh-index)
      (seq children) (assoc "children" (mapv node-indices children))
      (:translation transform) (assoc "translation"
                                      (mapv double (:translation transform)))
      (:rotation transform) (assoc "rotation"
                                   (normalize4 (:rotation transform)))
      (:scale transform) (assoc "scale" (mapv double (:scale transform)))
      (:matrix transform) (assoc "matrix" (:matrix transform))
      extras (assoc "extras" extras))))

(defn- scene->gltf [scene]
  (when-not (scene? scene)
    (throw (ex-info "export-scene expects a scene created with scene"
                    {:value scene})))
  (let [nodes (:nodes scene)]
    (when (empty? nodes)
      (throw (ex-info "An animation scene requires at least one node" {})))
    (let [node-indices (into {} (map-indexed (fn [index node]
                                               [(:id node) index])
                                             nodes))
          gltf-nodes (mapv (fn [node]
                             (node-transform node nil
                                             node-indices))
                           nodes)
          [state animations]
          (reduce (fn [[state animations] animation]
                    (let [[state animation] (add-animation state animation node-indices)]
                      [state (conj animations animation)]))
                  [(empty-glb-state) []]
                  (:animations scene))
          bin-length (align4 (:length state))
          gltf (cond-> {"asset" {"version" "2.0"
                                  "generator" "clj-manifold3d.animation"}
                        "scene" 0
                        "scenes" [(cond-> {"name" (:name scene)
                                           "nodes" (mapv node-indices (:roots scene))}
                                    (:extras scene) (assoc "extras" (:extras scene)))]
                        "nodes" gltf-nodes
                        "buffers" [{"byteLength" bin-length}]
                        "bufferViews" (:views state)
                        "accessors" (:accessors state)}
                 (seq animations) (assoc "animations" animations))]
      {:gltf (features/decorate-nodes gltf nodes)
       :segments (:segments state)
       :bin-length bin-length})))

(defn- join-segments [segments length]
  (let [output (byte-array length)]
    (loop [offset 0
           segments segments]
      (when-let [segment (first segments)]
        (System/arraycopy segment 0 output offset (alength segment))
        (recur (+ offset (alength segment)) (next segments))))
    output))

(defn- appearance-assets [geometry material]
  (let [asset (glb/read-glb (model/export-model (model/model geometry) nil))
        asset (-> asset (assoc-in [:gltf "nodes"] [])
                  (assoc-in [:gltf "scenes"] [{"nodes" []}]))]
    (update asset :gltf features/apply-material material)))

(defn scene-document
  "Compile a scene to an immutable GLB document, sharing repeated geometry.
  Each node's Model owns its colors, UVs, normals, and embedded images."
  [value]
  (if (glb/document? value) value
    (let [value (scene value)
          {:keys [gltf segments bin-length]} (scene->gltf value)
          base (glb/document gltf (join-segments segments bin-length))]
      (first
       (reduce (fn [[doc cache] [index {:keys [geometry material]}]]
                 (if-not geometry [doc cache]
                   (let [key [geometry material]
                         cached (get cache key)
                         mesh-index (or cached (count (get-in doc [:gltf "meshes"])))
                         doc (if cached doc (glb/append-document doc (appearance-assets geometry material)))]
                     [(assoc-in doc [:gltf "nodes" index "mesh"] mesh-index)
                      (assoc cache key mesh-index)])))
               [base {}] (map-indexed vector (:nodes value)))))))

(defn export-scene
  "Export a validated scene to a binary glTF 2.0 file.

  `scene` may be created with `scene`, and `filename` is returned after the
  file is written. Static nodes can contain geometry; transform-only nodes
  can be used as parents or pivots. Animation channels target node IDs and
  emit glTF translation, rotation, or scale samplers."
  [scene filename]
  (glb/write-glb (scene-document scene) filename))

(defn pivot-arm-scene
  "Create a simple base-and-arm scene whose arm pivots around the origin.

  Options are `:length`, `:width`, `:thickness`, `:base-radius`, and
  `:base-height`. The default animation rotates the arm 90 degrees around Z
  and returns it to its starting pose over two seconds."
  ([] (pivot-arm-scene {}))
  ([{:keys [length width thickness base-radius base-height]
     :or {length 40.0
          width 5.0
          thickness 5.0
          base-radius 7.0
          base-height 5.0}}]
   (let [arm (with-open [size (DoubleVec3. length width thickness)]
               (Manifold/Cube size true))
         pivot-track (keyframes
                      [{:time 0.0
                        :translation [0.0 0.0 0.0]
                        :rotation [0.0 0.0 0.0 1.0]
                        :scale [1.0 1.0 1.0]}
                       {:time 1.0
                        :translation [0.0 0.0 0.0]
                        :rotation [0.0 0.0 0.7071067811865475 0.7071067811865476]
                        :scale [1.0 1.0 1.0]}
                       {:time 2.0
                        :translation [0.0 0.0 0.0]
                        :rotation [0.0 0.0 0.0 1.0]
                        :scale [1.0 1.0 1.0]}])]
     (scene
      {:name "Pivot Arm"
       :nodes [{:id :base
                :name "Base"
                :geometry (Manifold/Cylinder base-height base-radius base-radius 32 true)}
               {:id :arm-pivot
                :name "Arm Pivot"
                :children [:arm]}
               {:id :arm
                :name "Arm"
                :geometry arm
                :transform {:translation [(/ (double length) 2.0) 0.0 0.0]}}]
       :animations [{:name "Pivot"
                     :channels [{:node :arm-pivot
                                 :path :rotation
                                 :track pivot-track}]}]}))))

(defn write-pivot-arm-glb
  "Write the demonstration pivot arm to `filename` and return `filename`."
  ([filename]
   (write-pivot-arm-glb filename {}))
  ([filename options]
   (export-scene (pivot-arm-scene options) filename)))
