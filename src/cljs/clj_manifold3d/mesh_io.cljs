(ns clj-manifold3d.mesh-io
  "Browser/Node mesh I/O, without Assimp or Closure-sensitive JS dependencies.
  STL/OBJ carry geometry; GLB also carries explicitly selected properties."
  (:require [clj-manifold3d.glb :as glb]
            [clj-manifold3d.runtime :as rt]
            [clojure.string :as str]))

(defn material [& {:as options}] options)

(defn- extension [filename]
  (some->> filename str/lower-case (re-find #"\.([a-z0-9]+)(?:[?#].*)?$") second keyword))
(defn- normal [[a b c]]
  (let [[x y z] (mapv - b a) [u v w] (mapv - c a)
        n [(- (* y w) (* z v)) (- (* z u) (* x w)) (- (* x v) (* y u))]]
    (mapv #(/ % (max 1e-30 (js/Math.hypot (n 0) (n 1) (n 2)))) n)))
(defn- triangles [{:keys [rows indices]}]
  (mapv #(mapv (fn [i] (subvec (rows i) 0 3)) %) (partition 3 indices)))

(defn- stl-bytes [mesh]
  (let [faces (triangles mesh) bytes (js/Uint8Array. (+ 84 (* 50 (count faces))))
        view (js/DataView. (.-buffer bytes))]
    (.setUint32 view 80 (count faces) true)
    (doseq [[i face] (map-indexed vector faces)
            [j value] (map-indexed vector (concat (normal face) (mapcat identity face)))]
      (.setFloat32 view (+ 84 (* 50 i) (* 4 j)) value true))
    bytes))
(defn- obj-bytes [{:keys [rows indices]}]
  (.encode (js/TextEncoder.)
           (str "# clj-manifold3d\n"
                (str/join "\n" (concat (map #(str "v " (str/join " " (subvec % 0 3))) rows)
                                          (map #(str "f " (str/join " " (map inc %))) (partition 3 indices)) )) "\n")))

(defn- add-attribute [[state attributes] label values size minimum maximum]
  (let [[state view] (glb/add-segment state (glb/floats->bytes values) 34962)
        [state accessor] (glb/add-accessor state view 5126 (/ (count values) size)
                                         (str "VEC" size) minimum maximum)]
    [state (assoc attributes label accessor)]))

(defn- glb-bytes [{:keys [positions indices rows num-prop min max] :as mesh}
                  {:keys [material faceted]}]
  (let [{:keys [color alpha roughness metalness normal-idx color-idx alpha-idx uv-idx]} material
        ;; Channel selectors follow the JVM material API: relative to properties,
        ;; excluding XYZ. UV defaults only for unambiguous XYZ+UV meshes.
        uv-idx (or uv-idx (when (= num-prop 5) 0))
        selected (fn [index n]
                   (when-not (and (nat-int? index) (<= (+ 3 index n) num-prop))
                     (throw (ex-info "Material channel is outside the mesh properties" {:index index :size n})))
                   (mapv #(subvec % (+ 3 index) (+ 3 index n)) rows))
        attributes (cond-> [["POSITION" (mapv #(subvec % 0 3) rows) 3 min max]]
                     (some? uv-idx) (conj ["TEXCOORD_0" (selected uv-idx 2) 2 nil nil])
                     (some? normal-idx) (conj ["NORMAL" (selected normal-idx 3) 3 nil nil])
                     (some? color-idx) (conj ["COLOR_0"
                                              (if (some? alpha-idx)
                                                (mapv #(conj %1 (first %2)) (selected color-idx 3) (selected alpha-idx 1))
                                                (selected color-idx 3))
                                              (if (some? alpha-idx) 4 3) nil nil]))
        attributes (if faceted
                     (conj (mapv (fn [[label values & rest]] (into [label (mapv values indices)] rest))
                                 (remove #(= "NORMAL" (first %)) attributes))
                           ["NORMAL" (vec (mapcat #(repeat 3 (normal %)) (triangles mesh))) 3 nil nil])
                     attributes)
        [state attributes] (reduce (fn [state [label values size minimum maximum]]
                                     (add-attribute state label (vec (mapcat identity values)) size minimum maximum))
                                   [(glb/empty-state) {}] attributes)
        indices (if faceted (vec (range (count indices))) indices)
        [state view] (glb/add-segment state (glb/ints->bytes indices) 34963)
        [state accessor] (glb/add-accessor state view 5125 (count indices) "SCALAR" nil nil)
        rgba (conj (vec (or (some-> color (subvec 0 3)) [1 1 1])) (or alpha (get color 3) 1))
        doc {"asset" {"version" "2.0" "generator" "clj-manifold3d"}
             "scene" 0 "scenes" [{"nodes" [0]}] "nodes" [{"mesh" 0}]
             "meshes" [{"primitives" [{"attributes" attributes "indices" accessor "mode" 4 "material" 0}]}]
             "materials" [(cond-> {"pbrMetallicRoughness" {"baseColorFactor" rgba
                                                              "roughnessFactor" (or roughness 1)
                                                              "metallicFactor" (or metalness 0)}}
                             (or (< (last rgba) 1) (some? alpha-idx)) (assoc "alphaMode" "BLEND"))]
             "buffers" [{"byteLength" (:length state)}] "bufferViews" (:views state) "accessors" (:accessors state)}]
    (glb/encode doc (:segments state) (:length state))))

(defn export-mesh
  "Export MeshGL/Manifold as GLB, binary STL, or geometry-only OBJ.
  Pass nil filename and :format to return bytes instead of writing/downloading."
  [mesh filename & {:keys [format] :as options}]
  (let [format (or format (extension filename) :glb) mesh (glb/mesh-data mesh)
        bytes (case format
                :glb (glb-bytes mesh options) :stl (stl-bytes mesh) :obj (obj-bytes mesh)
                (throw (ex-info "WASM mesh export supports :glb, :stl, and :obj" {:format format})))]
    (rt/write-bytes! filename bytes)))

(defn- make-mesh [rows indices]
  (when (or (empty? rows) (empty? indices) (not (zero? (mod (count indices) 3)))
            (not (every? #(and (integer? %) (<= 0 % (dec (count rows)))) indices))
            (not (every? js/Number.isFinite (mapcat identity rows))))
    (throw (ex-info "Invalid or empty triangle mesh" {})))
  (let [mesh (rt/construct "Mesh" (js-obj "numProp" (count (first rows))
                                         "vertProperties" (js/Float32Array. (to-array (mapcat identity rows)))
                                         "triVerts" (js/Uint32Array. (to-array indices))))]
    ;; Restore physical connectivity without merging discontinuous UV/color rows.
    (rt/call mesh "merge")
    mesh))

(defn- read-stl [bytes]
  (let [view (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))
        face-count (when (>= (alength bytes) 84) (.getUint32 view 80 true))
        vertices (if (and face-count (= (alength bytes) (+ 84 (* 50 face-count))))
                   (vec (for [i (range face-count) j (range 3)]
                          (mapv #(.getFloat32 view (+ 96 (* 50 i) (* 12 j) (* 4 %)) true) (range 3))))
                   (let [text (.decode (js/TextDecoder.) bytes)]
                     (when-not (str/starts-with? (str/trim text) "solid")
                       (throw (ex-info "Invalid STL header or length" {})))
                     (mapv (fn [[_ x y z]] (mapv js/Number [x y z]))
                           (re-seq #"vertex\s+(\S+)\s+(\S+)\s+(\S+)" text))))]
    (make-mesh vertices (vec (range (count vertices))))))

(defn- read-obj [bytes]
  (let [{:keys [vertices indices]}
        (reduce (fn [state line]
                  (let [[kind & values] (str/split (str/trim (or (first (str/split line #"#")) "")) #"\s+")]
                    (case kind
                      "v" (update state :vertices conj (mapv js/Number (take 3 values)))
                      "f" (let [face (mapv (fn [value]
                                             (let [n (js/Number (first (str/split value #"/")))]
                                               (if (neg? n) (+ (count (:vertices state)) n) (dec n)))) values)]
                            (when-not (= 3 (count face))
                              (throw (ex-info "OBJ import requires triangulated faces" {})))
                            (update state :indices into face))
                      state)))
                {:vertices [] :indices []} (str/split-lines (.decode (js/TextDecoder.) bytes)))]
    (make-mesh vertices indices)))

(defn- glb-accessor [document binary index]
  (let [{:keys [bufferView byteOffset componentType count type sparse normalized]} (get-in document [:accessors index])
        view-spec (get-in document [:bufferViews bufferView])
        size ({"SCALAR" 1 "VEC2" 2 "VEC3" 3 "VEC4" 4} type)
        width ({5121 1 5123 2 5125 4 5126 4} componentType)
        start (+ (or byteOffset 0) (:byteOffset view-spec 0))
        stride (:byteStride view-spec (* (or width 0) (or size 0)))
        view (js/DataView. (.-buffer binary) (.-byteOffset binary) (.-byteLength binary))]
    (when (or (nil? size) (nil? width) sparse (not= 0 (:buffer view-spec))
              (not (pos-int? count)) (< stride (* width size))
              (> (+ start (* (dec count) stride) (* width size)) (alength binary)))
      (throw (ex-info "Unsupported or invalid GLB accessor" {:index index})))
    (mapv (fn [i]
            (mapv (fn [j]
                    (let [offset (+ start (* i stride) (* j width))
                          value (case componentType 5121 (.getUint8 view offset)
                                       5123 (.getUint16 view offset true) 5125 (.getUint32 view offset true)
                                       5126 (.getFloat32 view offset true))]
                      (if (and normalized (not= componentType 5126))
                        (/ value (dec (js/Math.pow 2 (* 8 width)))) value))) (range size))) (range count))))

(defn- read-glb [bytes]
  (let [view (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))]
    (when (or (< (alength bytes) 28) (not= 0x46546c67 (.getUint32 view 0 true))
              (not= 2 (.getUint32 view 4 true)) (not= (alength bytes) (.getUint32 view 8 true))
              (not= 0x4e4f534a (.getUint32 view 16 true)))
      (throw (ex-info "Invalid GLB 2.0 header" {})))
    (let [json-end (+ 20 (.getUint32 view 12 true))
          _ (when (> (+ 8 json-end) (alength bytes)) (throw (ex-info "Truncated GLB" {})))
          doc (js->clj (js/JSON.parse (.decode (js/TextDecoder.) (.subarray bytes 20 json-end))) :keywordize-keys true)
          binary (.subarray bytes (+ json-end 8))
          primitive (get-in doc [:meshes 0 :primitives 0])
          {:keys [POSITION TEXCOORD_0 COLOR_0 NORMAL]} (:attributes primitive)]
      (when (or (not= 0x004e4942 (.getUint32 view (+ json-end 4) true))
                (> (.getUint32 view json-end true) (alength binary))
                (seq (:extensionsRequired doc)) (seq (:animations doc))
                (not= 1 (count (:meshes doc))) (not= 1 (count (:nodes doc)))
                (not= 0 (get-in doc [:nodes 0 :mesh]))
                (some #(contains? (first (:nodes doc)) %) [:matrix :translation :rotation :scale])
                (not= 1 (count (get-in doc [:meshes 0 :primitives]))) (not= 4 (:mode primitive 4))
                (nil? POSITION))
        (throw (ex-info "GLB mesh import requires one static, untransformed triangle primitive; scene/animation import is not supported" {})))
      (let [positions (glb-accessor doc binary POSITION)
            attributes (mapv #(glb-accessor doc binary %) (remove nil? [TEXCOORD_0 COLOR_0 NORMAL]))
            _ (when-not (every? #(= (count positions) (count %)) attributes)
                (throw (ex-info "Mismatched GLB attribute lengths" {})))
            rows (reduce #(mapv into %1 %2) positions attributes)
            indices (if-let [index (:indices primitive)] (mapv first (glb-accessor doc binary index))
                        (vec (range (count rows))))]
        (make-mesh rows indices)))))

(defn import-mesh
  "Promise of a Mesh snapshot from a filename, URL, or Uint8Array.
  Formats: STL; triangulated geometry-only OBJ; single-primitive static GLB.
  Second arg accepts the JVM cleanup flag or {:format :glb/:stl/:obj}."
  ([source] (import-mesh source false))
  ([source options]
   (-> (rt/read-bytes source)
       (.then (fn [bytes]
                (let [format (or (when (map? options) (:format options))
                                 (when (string? source) (extension source))
                                 (when (and (>= (alength bytes) 4) (= [103 108 84 70] (vec (array-seq (.subarray bytes 0 4))))) :glb)
                                 :stl)]
                  (case format :glb (read-glb bytes) :stl (read-stl bytes) :obj (read-obj bytes)
                        (throw (ex-info "WASM mesh import supports :glb, :stl, and :obj" {:format format})))))))))
