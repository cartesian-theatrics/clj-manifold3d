(ns clj-manifold3d.animation
  "JVM rigid animation tracks, scene graphs, and a glTF/GLB scene exporter.

  Geometry is authored in local coordinates; node transforms and animation
  tracks are written separately."
  (:require [clj-manifold3d.core :as manifold]
            [clojure.data.json :as json])
  (:import [java.io FileOutputStream]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]))

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

(defn- align4 [n]
  (+ n (mod (- 4 (mod n 4)) 4)))

(defn- pad-bytes [bytes fill]
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

(defn- mesh-data [solid]
  (let [mesh (manifold/get-mesh solid)
        properties (.toFloatArray (.vertProperties mesh))
        triangles (.toIntArray (.triVerts mesh))
        num-properties (.numProp mesh)
        num-vertices (.NumVert mesh)
        positions (vec (mapcat (fn [vertex-index]
                                 (let [offset (* vertex-index num-properties)]
                                   [(aget properties offset)
                                    (aget properties (inc offset))
                                    (aget properties (+ offset 2))]))
                               (range num-vertices)))
        indices (vec triangles)
        coordinates (partition 3 positions)
        minimum (reduce (fn [result coordinate]
                          (mapv min result coordinate))
                        (vec (repeat 3 Double/POSITIVE_INFINITY))
                        coordinates)
        maximum (reduce (fn [result coordinate]
                          (mapv max result coordinate))
                        (vec (repeat 3 Double/NEGATIVE_INFINITY))
                        coordinates)]
    {:positions positions
     :indices indices
     :min minimum
     :max maximum}))

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

(defn- join-segments [segments length]
  (let [output (byte-array length)]
    (loop [offset 0
           segments segments]
      (when-let [segment (first segments)]
        (System/arraycopy segment 0 output offset (alength segment))
        (recur (+ offset (alength segment)) (next segments))))
    output))

(defn export-scene
  "Export a validated scene to a binary glTF 2.0 file.

  `scene` may be created with `scene`, and `filename` is returned after the
  file is written. Static nodes can contain geometry; transform-only nodes
  can be used as parents or pivots. Animation channels target node IDs and
  emit glTF translation, rotation, or scale samplers."
  [scene filename]
  (let [{:keys [gltf segments bin-length]} (scene->gltf scene)
        json-bytes (.getBytes (json/write-str gltf) StandardCharsets/UTF_8)
        json-chunk (pad-bytes json-bytes 32)
        bin-chunk (pad-bytes (join-segments segments bin-length) 0)
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
   (let [arm (manifold/cube length width thickness true)
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
                :geometry (manifold/cylinder base-height base-radius base-radius 32 true)}
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
