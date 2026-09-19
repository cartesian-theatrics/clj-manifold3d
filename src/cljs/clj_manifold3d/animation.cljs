(ns clj-manifold3d.animation
  "Immutable animation tracks and GLB scenes for ClojureScript."
  (:require [clj-manifold3d.runtime :as rt]
            [clj-manifold3d.glb :as glb]))

(defn- finite-number? [x]
  (and (number? x) (js/Number.isFinite x)))

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
  (let [length (js/Math.sqrt (dot4 q q))]
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
      (let [theta (js/Math.acos (max -1.0 (min 1.0 cosine)))
            sin-theta (js/Math.sin theta)
            wa (/ (js/Math.sin (* (- 1.0 u) theta)) sin-theta)
            wb (/ (js/Math.sin (* u theta)) sin-theta)]
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
  (= scene-type (:model/type value)))

(defn- normalize-node [index node]
  (let [id (or (:id node) (:name node))
        children (vec (or (:children node) []))
        transform (merge (select-keys node [:translation :rotation :scale])
                         (:transform node))]
    (when-not (some? id)
      (throw (ex-info "Scene nodes require an :id" {:index index :node node})))
    (doseq [[key size] [[:translation 3] [:rotation 4] [:scale 3]]]
      (when (contains? transform key)
        (assert-vector! (str ":transform/" (name key)) (get transform key) size)))
    (assoc (select-keys node [:name :geometry :extras])
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
    {:model/type scene-type
     :name (or name "Scene")
     :nodes nodes
     :roots (vec (remove child-ids ids))
     :animations (mapv #(normalize-animation node-ids %) (or animations []))
     :extras extras}))


(def ^:private align4 glb/align4)
(def ^:private floats->bytes glb/floats->bytes)
(def ^:private ints->bytes glb/ints->bytes)
(def ^:private mesh-data glb/mesh-data)
(def ^:private empty-glb-state glb/empty-state)
(def ^:private add-segment glb/add-segment)
(def ^:private add-accessor glb/add-accessor)

(defn- add-geometry [state geometry]
  (let [{:keys [positions indices min max]} geometry
        [state position-view] (add-segment state (floats->bytes positions) 34962)
        [state position-accessor] (add-accessor state position-view 5126
                                                 (/ (count positions) 3) "VEC3"
                                                 min max)
        [state index-view] (add-segment state (ints->bytes indices) 34963)
        [state index-accessor] (add-accessor state index-view 5125
                                              (count indices) "SCALAR" nil nil)]
    [state {"primitives"
            [{"attributes" {"POSITION" position-accessor}
              "indices" index-accessor
              "mode" 4}]}]))

(defn- channel-values [path track]
  (vec (mapcat path track)))

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
                                   (mapv double (:rotation transform)))
      (:scale transform) (assoc "scale" (mapv double (:scale transform)))
      extras (assoc "extras" extras))))

(defn- scene->gltf [scene]
  (when-not (scene? scene)
    (throw (ex-info "export-scene expects a scene created with scene"
                    {:value scene})))
  (let [nodes (:nodes scene)]
    (when (empty? nodes)
      (throw (ex-info "An animation scene requires at least one node" {})))
    (let [[state meshes mesh-indices]
          (reduce (fn [[state meshes mesh-indices] node]
                    (if-let [geometry (:geometry node)]
                      (let [[state mesh] (add-geometry state (mesh-data geometry))
                            mesh-index (count meshes)]
                        [state
                         (conj meshes mesh)
                         (assoc mesh-indices (:id node) mesh-index)])
                      [state meshes mesh-indices]))
                  [(empty-glb-state) [] {}]
                  nodes)
          node-indices (into {} (map-indexed (fn [index node]
                                               [(:id node) index])
                                             nodes))
          gltf-nodes (mapv (fn [node]
                             (node-transform node (get mesh-indices (:id node))
                                             node-indices))
                           nodes)
          [state animations]
          (reduce (fn [[state animations] animation]
                    (let [[state animation] (add-animation state animation node-indices)]
                      [state (conj animations animation)]))
                  [state []]
                  (:animations scene))
          bin-length (align4 (:length state))
          gltf (cond-> {"asset" {"version" "2.0"
                                  "generator" "clj-manifold3d.animation"}
                        "scene" 0
                        "scenes" [{"name" (:name scene)
                                   "nodes" (mapv node-indices (:roots scene))}]
                        "nodes" gltf-nodes
                        "meshes" meshes
                        "buffers" [{"byteLength" bin-length}]
                        "bufferViews" (:views state)
                        "accessors" (:accessors state)}
                 (seq animations) (assoc "animations" animations))]
      {:gltf gltf
       :segments (:segments state)
       :bin-length bin-length})))


(defn scene-bytes
  "Encode a scene as a self-contained GLB Uint8Array."
  [scene]
  (let [{:keys [gltf segments bin-length]} (scene->gltf scene)]
    (glb/encode gltf segments bin-length)))

(defn export-scene
  "Write/download GLB; pass nil as filename to return bytes."
  [scene filename]
  (rt/write-bytes! filename (scene-bytes scene)))

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
   (let [arm (rt/static "Manifold" "cube" #js [length width thickness] true)
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
                :geometry (rt/static "Manifold" "cylinder" base-height base-radius base-radius 32 true)}
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
   (rt/with-disposal #(export-scene (pivot-arm-scene options) filename))))
