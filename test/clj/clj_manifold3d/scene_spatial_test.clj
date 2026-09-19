(ns clj-manifold3d.scene-spatial-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clj-manifold3d.core :as m]
            [clj-manifold3d.scene :as s]
            [clj-manifold3d.spatial :as spatial]
            [clj-manifold3d.glb :as glb]
            [clj-manifold3d.animation :as animation])
  (:import [org.bytedeco.javacpp PointerScope]
           [java.util Base64]
           [java.nio ByteBuffer ByteOrder]))

(use-fixtures :each (fn [f] (with-open [_ (PointerScope.)] (f))))
(defn near? [a b] (< (Math/abs (double (- a b))) 1e-5))
(defn nearv? [a b] (and (= (count a) (count b)) (every? true? (map near? a b))))
(defn volume [x] (:volume (m/get-properties x)))
(def red (.decode (Base64/getDecoder) "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4XmP4z8DwHwAFAAH/NQZ7kgAAAABJRU5ErkJggg=="))
(defn fixture-scene []
  (m/scene {:name "Fixture" :extras {"source" "test"}
            :nodes [{:id :pivot :children [:arm] :extras {"partID" "pivot-1"}}
                    {:id :arm :geometry (-> (m/cube 2 2 2 true) (m/color [0 1 0 1])
                                           (m/texture red :mapping :planar))
                     :material {:roughness 0.23 :metalness 0.61}
                     :translation [3 0 0]}]
            :animations [{:name "Move" :channels
                          [{:node :pivot :path :rotation :track
                            [{:time 0 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}
                             {:time 1 :translation [0 0 0] :rotation [0 0 1 0] :scale [1 1 1]}]}]}]}))

(deftest merge-repairs-triangle-soup-without-mutation
  (let [original (m/get-mesh-gl (m/cube 2 2 2 true))
        rows (vec (partition (.numProp original) (.toFloatArray (.vertProperties original))))
        positions (mapv #(vec (take 3 (rows %))) (.toIntArray (.triVerts original)))
        soup (m/mesh :vert-pos positions :tri-verts (partition 3 (range (count positions))))
        merged (m/mesh-merge soup)]
    (is (= :NotManifold (m/status (m/manifold soup))))
    (is (= :NoError (m/status (m/manifold merged))))
    (is (near? 8 (volume (m/manifold merged))))
    (is (empty? (.toIntArray (.mergeFromVert soup))))
    (is (pos? (count (.toIntArray (.mergeFromVert merged)))))))

(deftest textured-scenes-roundtrip-and-append
  (let [doc (s/document (fixture-scene))
        roundtrip (m/import-scene (m/export-model doc nil))
        g (:gltf roundtrip)
        attrs (get-in g ["meshes" 0 "primitives" 0 "attributes"])
        out (s/append roundtrip roundtrip)
        g2 (:gltf out)]
    (is (m/scene? doc))
    (is (every? #(contains? attrs %) ["POSITION" "NORMAL" "TEXCOORD_0"]))
    (is (seq (get g "images")))
    (is (= {"partID" "pivot-1"} (:extras (s/node roundtrip :pivot))))
    (is (= {"source" "test"} (get-in g ["scenes" 0 "extras"])))
    (is (near? 0.23 (get-in g ["materials" 0 "pbrMetallicRoughness" "roughnessFactor"])))
    (is (near? 0.61 (get-in g ["materials" 0 "pbrMetallicRoughness" "metallicFactor"])))
    (is (= 2 (count (get g2 "animations"))))
    (is (= [0 2] (get-in g2 ["scenes" 0 "nodes"])))
    (is (= 2 (get-in g2 ["animations" 1 "channels" 0 "target" "node"])))
    (is (= 1 (get-in g2 ["nodes" 3 "mesh"])))
    (is (= 1 (get-in g2 ["textures" 1 "source"])))
    (is (= 1 (get-in g2 ["materials" 1 "pbrMetallicRoughness" "baseColorTexture" "index"])))
    (is (= (glb/accessor roundtrip (attrs "POSITION"))
           (glb/accessor out (get-in g2 ["meshes" 1 "primitives" 0 "attributes" "POSITION"])))))
  (let [geometry (m/color (m/cube 1 1 1) [0.2 0.4 0.6 1])
        g (:gltf (s/document (m/scene {:nodes [{:id :a :geometry geometry} {:id :b :geometry geometry}]})))]
    (is (= 1 (count (get g "meshes"))))
    (is (contains? (get-in g ["meshes" 0 "primitives" 0 "attributes"]) "COLOR_0"))))

(deftest edit-and-sample-an-imported-pose
  (let [doc (m/import-scene (m/export-scene (fixture-scene) nil))
        pose (s/sample-scene doc 0.5)
        shifted (-> doc (s/update-node :arm assoc :extras {"edited" true})
                    (s/wrap-node :pivot {:translation [10 0 0]})
                    (s/sample-scene 0.5))]
    (is (nearv? [-1 2 -1] (:min (s/bounds pose :arm)) ))
    (is (nearv? [1 4 1] (:max (s/bounds pose :arm)) ))
    (is (nearv? [9 2 -1] (:min (s/bounds shifted :arm))))
    (is (near? 8 (volume (s/solid shifted :arm))))
    (is (= {"edited" true} (:extras (s/node shifted :arm))))
    (is (nil? (:extras (s/node doc :arm))))
    (is (= 1 (count (get-in doc [:gltf "animations"]))))
    (is (nil? (get-in pose [:gltf "animations"])))
    (is (nearv? [2 -1 -1] (:min (s/bounds (s/sample-scene doc -2)))))
    (is (nearv? [-4 -1 -1] (:min (s/bounds (s/sample-scene doc 2)))))
    (is (nil? (s/bounds (s/remove-node doc :pivot))))
    (is (= 2 (count (get-in (s/remove-node doc :pivot) [:gltf "nodes"]))))
    (is (thrown-with-msg? Exception #"animated node" (s/update-node doc :pivot assoc :matrix [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1])))))

(defn sampler-fixture [mode values]
  (let [data (vec (concat [0 2] (mapcat identity values)))
        buffer (doto (ByteBuffer/allocate (* 4 (count data))) (.order ByteOrder/LITTLE_ENDIAN))]
    (doseq [x data] (.putFloat buffer (float x)))
    (glb/document {"asset" {"version" "2.0"} "scene" 0 "scenes" [{"nodes" [0]}] "nodes" [{"name" "n"}]
                   "bufferViews" [{"buffer" 0 "byteOffset" 0 "byteLength" 8}
                                  {"buffer" 0 "byteOffset" 8 "byteLength" (* 12 (count values))}]
                   "accessors" [{"bufferView" 0 "componentType" 5126 "count" 2 "type" "SCALAR"}
                                {"bufferView" 1 "componentType" 5126 "count" (count values) "type" "VEC3"}]
                   "animations" [{"samplers" [{"input" 0 "output" 1 "interpolation" mode}]
                                  "channels" [{"sampler" 0 "target" {"node" 0 "path" "translation"}}]}]} (.array buffer))))

(deftest imported-sampler-interpolations
  (let [step (sampler-fixture "STEP" [[0 0 0] [2 4 6]])
        cubic (sampler-fixture "CUBICSPLINE" [[0 0 0] [0 0 0] [2 0 0] [0 0 0] [2 0 0] [0 0 0]])]
    (is (nearv? [0 0 0] (:translation (s/node (s/sample-scene step 1.999) 0))))
    (is (nearv? [2 4 6] (:translation (s/node (s/sample-scene step 2) 0))))
    (is (nearv? [1.5 0 0] (:translation (s/node (s/sample-scene cubic 1) 0))))))

(deftest invalid-scenes-are-rejected
  (doseq [nodes [[{:id :a :children [:b]} {:id :b :children [:a]}]
                [{:id :a :children [:c]} {:id :b :children [:c]} {:id :c}]
                [{:id :a :children [:missing]}]]]
    (is (thrown? Exception (m/scene {:nodes nodes}))))
  (is (thrown? Exception (glb/read-glb (byte-array 12))))
  (is (thrown? Exception (s/sample-scene (fixture-scene) Double/NaN))))

(deftest mirrored-and-overlapping-node-geometry
  (let [cube (m/cube 2 2 2 true)
        doc (s/document (m/scene {:nodes [{:id :a :geometry cube :scale [-1 1 1]}
                                        {:id :b :geometry cube :translation [1 0 0]}]}))]
    (is (near? 8 (volume (s/solid doc :a))))
    (is (near? 12 (volume (s/solid doc))))
    (is (m/contains-point? (s/solid doc) [1.5 0 0]))))

(deftest interleaved-normalized-and-sparse-accessors
  (let [data (byte-array (map unchecked-byte [99 255 0 127 77 0 255 255 1 128 64 32]))
        doc (glb/document {"bufferViews" [{"buffer" 0 "byteLength" 8 "byteStride" 4}
                                          {"buffer" 0 "byteOffset" 8 "byteLength" 1}
                                          {"buffer" 0 "byteOffset" 9 "byteLength" 3}]
                            "accessors" [{"bufferView" 0 "byteOffset" 1 "componentType" 5121
                                          "count" 2 "type" "VEC3" "normalized" true
                                          "sparse" {"count" 1 "indices" {"bufferView" 1 "componentType" 5121}
                                                    "values" {"bufferView" 2}}}]} data)]
    (is (nearv? [1 0 (/ 127 255)] (first (glb/accessor doc 0))))
    (is (nearv? [(/ 128 255) (/ 64 255) (/ 32 255)] (second (glb/accessor doc 0))))
    (is (thrown? Exception (glb/accessor (assoc-in doc [:gltf "bufferViews" 0 "byteLength"] 3) 0)))))

(deftest append-remaps-core-references-and-preserves-unknown-data
  (let [doc (assoc-in (s/document (fixture-scene)) [:gltf "extras"] {"vendor" {"keep" [1 2 3]}})
        source (-> doc
                   (assoc-in [:gltf "cameras"] [{"type" "perspective" "perspective" {"yfov" 1 "znear" 0.1}}])
                   (assoc-in [:gltf "nodes" 0 "camera"] 0)
                   (assoc-in [:gltf "skins"] [{"joints" [0] "skeleton" 0}])
                   (assoc-in [:gltf "nodes" 1 "skin"] 0))
        out (s/append source source)]
    (is (= (get-in source [:gltf "extras"]) (get-in out [:gltf "extras"])))
    (is (= 1 (get-in out [:gltf "nodes" 2 "camera"])))
    (is (= 1 (get-in out [:gltf "nodes" 3 "skin"])))
    (is (= [2] (get-in out [:gltf "skins" 1 "joints"])))
    (is (= 2 (get-in out [:gltf "skins" 1 "skeleton"])))
    (is (= (seq (:binary doc)) (seq (:binary (m/import-scene (m/export-scene doc nil))))))
    (is (thrown? Exception (s/solid source :arm)))
    (is (thrown? Exception (s/append doc (assoc-in doc [:gltf "extensionsUsed"] ["UNKNOWN_index_extension"]))))))

(deftest native-ray-closest-and-containment
  (let [cube (m/cube 2 2 2 true) index (m/spatial-index cube)
        hit (m/ray-cast index [3 0 0] [-2 0 0])
        nearest (m/closest-point index [3 0.2 0.3])]
    (is (near? 2 (:distance hit)))
    (is (nearv? [1 0 0] (:position hit)))
    (is (nearv? [1 0 0] (:normal hit)))
    (is (near? 1 (reduce + (:barycentric hit))))
    (is (nearv? [1 0.2 0.3] (:position nearest)))
    (is (near? 2 (:distance nearest)))
    (is (nil? (m/ray-cast index [3 0 0] [1 0 0])))
    (is (nil? (m/ray-cast index [3 0 0] [-1 0 0] :max-distance 1.99)))
    (is (near? 1 (:distance (m/ray-cast index [0 0 0] [0 1 0]))))
    (is (m/contains-point? index [0 0 0]))
    (is (m/contains-point? index [1 0 0]))
    (is (not (m/contains-point? index [1 0 0] :boundary? false)))
    (is (= :outside (spatial/classify-point index [2 0 0])))
    (is (thrown? Exception (m/ray-cast index [0 0 0] [0 0 0])))
    (is (thrown? Exception (m/closest-point index [Double/NaN 0 0])))
    (is (thrown? Exception (m/contains-point? index [0 0 0] :tolerance -1)))
    (is (m/contains-point? (m/model cube) [0 0 0]))))

(deftest native-overlap-cavities-contact-and-disconnected-components
  (let [cube (m/cube 2 2 2 true) shell (m/difference (m/cube 6 6 6 true) (m/cube 4 4 4 true))
        far (m/translate cube [10 0 0]) disjoint (m/union far (m/translate cube [-10 0 0]))]
    (is (m/overlap? cube cube))
    (is (m/overlap? cube (m/translate cube [2 0 0])))
    (is (m/overlap? cube (m/translate cube [2 2 2])))
    (is (not (m/overlap? cube (m/translate cube [2.01 0 0]))))
    (is (m/overlap? cube (m/cube 0.1 0.1 0.1 true)))
    (is (not (m/overlap? cube shell)))
    (is (not (m/contains-point? shell [0 0 0])))
    (is (m/contains-point? shell [2.5 0 0]))
    (is (not (m/overlap? cube disjoint)))
    (is (m/overlap? disjoint (m/translate (m/cube 0.1 0.1 0.1 true) [-10 0 0])))
    (is (not (m/overlap? (m/manifold) cube)))
    (is (nil? (m/closest-point (m/manifold) [0 0 0])))
    (is (nil? (m/ray-cast (m/manifold) [0 0 0] [1 0 0])))
    (is (not (m/contains-point? (m/manifold) [0 0 0])))))

(deftest bvh-agrees-with-analytic-boxes
  (let [index (m/spatial-index (m/cube 2 2 2 true)) rng (java.util.Random. 42)]
    (dotimes [_ 120]
      (let [point (vec (repeatedly 3 #(- (* 6 (.nextDouble rng)) 3)))
            clamped (mapv #(max -1 (min 1 %)) point)
            inside? (every? #(< -1 % 1) point)]
        (is (= inside? (m/contains-point? index point :boundary? false)))
        (when-not inside? (is (nearv? clamped (:position (m/closest-point index point)))))
        (is (= (every? #(< (Math/abs (double %)) 2) point)
               (m/overlap? index (m/translate (m/cube 2 2 2 true) point))))))))

(deftest bvh-agrees-with-boolean-intersections-for-oblique-solids
  (let [a (m/rotate (m/cube 2 1 3 true) [17 31 43])
        index (m/spatial-index a) rng (java.util.Random. 718)]
    (dotimes [_ 40]
      (let [rotation (vec (repeatedly 3 #(* 180 (.nextDouble rng))))
            translation (vec (repeatedly 3 #(- (* 6 (.nextDouble rng)) 3)))
            b (-> (m/cube 1 2 3 true) (m/rotate rotation) (m/translate translation))
            expected (> (volume (m/intersection a b)) 1e-9)]
        (is (= expected (m/overlap? index b)) {:rotation rotation :translation translation})))))

(deftest native-float-buffer-conversion-copies-the-remaining-range
  (let [buffer (doto (ByteBuffer/allocateDirect 20) (.order (ByteOrder/nativeOrder)))
        floats (.asFloatBuffer buffer)]
    (.put floats (float-array [99 1 2 3 88]))
    (.position floats 1) (.limit floats 4)
    (with-open [native (manifold3d.FloatVector/FromBuffer floats)]
      (.put floats 1 (float 77))
      (is (= [1.0 2.0 3.0] (vec (.toFloatArray native)))))))
