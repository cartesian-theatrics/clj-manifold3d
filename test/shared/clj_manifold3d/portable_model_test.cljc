(ns clj-manifold3d.portable-model-test
  (:require #?(:clj [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [clj-manifold3d.core :as m]
            [clj-manifold3d.animation :as animation]
            [clj-manifold3d.test-support :as support])
  #?(:clj (:import [java.util Base64])))

(use-fixtures :each support/with-disposal)
(defn- decode [s]
  #?(:clj (.decode (Base64/getDecoder) s)
     :cljs (js/Uint8Array.from (js/atob s) #(.charCodeAt % 0))))
(def red (decode "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4XmP4z8DwHwAFAAH/NQZ7kgAAAABJRU5ErkJggg=="))
(def blue-half (decode "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4XmNgYPjfAAACgwGA/yS+jAAAAABJRU5ErkJggg=="))
(def blue (decode "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4XmNgYPj/HwADAgH/WuhNIQAAAABJRU5ErkJggg=="))
(def red-blue (decode "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4XmP4z8AAQv8BD/kD/fiqmIEAAAAASUVORK5CYII="))
(def depth16 (decode "iVBORw0KGgoAAAANSUhEUgAAAAMAAAADEAAAAAAj0zYgAAAAHklEQVR4XmNgYOBfKO/IYGBZxbQpj+Hu1VeJ//8DADbSCBGZ0iviAAAAAElFTkSuQmCC"))
(defn near? [a b] (< (support/abs (- a b)) 1e-5))
(defn colors [object]
  (mapv #(m/sample-color object % 0.25 0.25) (range (support/triangle-count object))))

(deftest thread-first-models-compose-native-layers
  (let [source (m/model (m/cube 4 4 4 true))
        first (m/texture source red :mapping :planar :name "red")
        result (-> first
                   (m/texture blue-half :mapping :planar :name "blue")
                   (m/translate [1 2 3])
                   (m/rotate [10 20 30])
                   (m/scale [2 2 2])
                   (m/refine 2))]
    (is (m/model? result))
    (is (= :NoError (m/status result)))
    (is (= 0 (:layers (m/model-info source))))
    (is (= 1 (:layers (m/model-info first))))
    (is (= 2 (:layers (m/model-info result)) (:images (m/model-info result))))
    (is (near? 512 (:volume (m/get-properties result))))
    (is (= 7 (support/mesh-field (m/get-mesh-gl result) "numProp")))
    (is (every? #(every? true? (map near? % [(/ 127 255) 0 (/ 128 255) 1])) (colors result)))))

(deftest boolean-branches-keep-their-own-images
  (let [source (m/model (m/cube 4 4 4 true))
        left (m/texture source red :mapping :planar)
        right (-> source (m/texture blue :mapping :planar) (m/translate [1 0.5 0.25]))]
    (doseq [op [m/union m/difference m/intersection]]
      (let [result (op left right) samples (colors result)]
        (is (= :NoError (m/status result)))
        (is (= 2 (:layers (m/model-info result)) (:images (m/model-info result))))
        (is (some #(> (nth % 0) 0.99) samples))
        (is (some #(> (nth % 2) 0.99) samples))))
    (is (m/model? (m/union (m/cube 1 1 1) left)))
    (is (m/model? (m/difference left (m/cube 1 1 1))))
    (is (= 0 (:layers (m/model-info source))))))

(deftest native-model-owns-images-and-preserves-existing-vertex-colors
  (let [image #?(:clj (aclone red) :cljs (.slice red))
        source (-> (m/cube 2 2 2) (m/color [0 0 1 1]) m/model)
        result (m/texture source image :mapping :planar :opacity 0.5)]
    #?(:clj (aset-byte image 0 (byte 0)) :cljs (aset image 0 0)) ; corrupt the caller's PNG after native decoding
    (is (= 9 (support/mesh-field (m/get-mesh-gl result) "numProp")))
    (is (every? #(every? true? (map near? % [0.5 0 0.5 1])) (colors result)))
    (is (every? #(near? 1 (nth % 2)) (colors source)))))

(deftest local-texture-coverage-and-stepped-depth
  (let [result (-> (m/cube 10 10 2 true)
                   m/model
                   (m/color [0 1 0 1])
                   (m/texture red :origin [0 0 1] :normal [0 0 1] :size [3 2]
                              :pixel-size 0.25 :depth-map [[1 1] [1 1]]
                              :depth-scale 0.3 :depth-boundary :step))
        samples (colors result)]
    (is (= :NoError (m/status result)))
    (is (near? 201.8 (:volume (m/get-properties result))))
    (is (some #(> (nth % 0) 0.99) samples))
    (is (some #(> (nth % 1) 0.99) samples))
    (is (every? #(or (> (nth % 0) 0.99) (> (nth % 1) 0.99)) samples))))

(deftest overlapping-decals-keep-independent-uvs
  (let [base (-> (m/cube 10 10 2 true) m/model (m/color [0 1 0 1]))
        first (m/texture base red :origin [-1 0 1] :normal [0 0 1] :size [4 2] :pixel-size 0.5)
        result (m/texture first blue-half :origin [1 0 1] :normal [0 0 1] :size [4 2] :pixel-size 0.5)
        rows (support/rows result)
        faces (partition 3 (support/mesh-field (m/get-mesh-gl result) "triVerts"))
        overlapping (keep-indexed
                      (fn [i corners]
                        (let [[x y z] (mapv #(/ % 3) (apply mapv + (map #(subvec (rows %) 0 3) corners)))]
                          (when (and (< (support/abs x) 0.8) (< (support/abs y) 0.8) (near? z 1)) i)))
                      faces)]
    (is (= 2 (:layers (m/model-info result))))
    (is (= 1 (:layers (m/model-info first))))
    (is (seq overlapping))
    (doseq [face overlapping]
      (is (every? true? (map near? (m/sample-color result face (/ 1 3) (/ 1 3))
                             [(/ 127 255) 0 (/ 128 255) 1]))))))

(deftest depth-images-and-grids-use-the-same-native-path
  (doseq [boundary [:fade :step]]
    (let [source (m/cube 10 10 2 true)
          grid (mapv #(mapv (fn [n] (/ n 65535)) %) [[0 4001 8001] [12345 31234 45678] [56789 60001 65535]])
          options [:origin [0 0 1] :normal [0 0 1] :size [3 2] :pixel-size 0.4
                   :depth-scale 0.3 :depth-boundary boundary]
          a (apply m/texture source red :depth-map depth16 options)
          b (apply m/texture source red :depth-map grid options)]
      (is (= :NoError (m/status a)))
      (is (near? (:volume (m/get-properties a)) (:volume (m/get-properties b)))))))

(deftest compose-decompose-retain-owned-appearance
  (let [source (m/cube 2 2 2 true)
        left (-> source (m/texture red :mapping :planar) m/smooth-out)
        right (-> source (m/texture blue :mapping :planar) (m/translate [5 0 0]))
        joined (m/compose [left right])
        parts (m/decompose joined)]
    (is (= 2 (count parts)))
    (is (= 2 (:images (m/model-info joined))))
    (is (every? m/model? parts))
    (is (every? #(= 1 (:images (m/model-info %))) parts))
    (is (every? #(near? 8 (:volume (m/get-properties %))) parts))
    (is (every? #(some (fn [c] (or (> (nth c 0) 0.99) (> (nth c 2) 0.99))) (colors %)) parts))))

(deftest native-model-export-is-self-contained
  (let [result (-> (m/cube 2 2 2) (m/texture red :mapping :planar)
                   (m/texture blue-half :mapping :planar))
        bytes (m/export-model result nil :tile-size 8)
        doc (support/glb-json bytes)]
    (is (= "manifold::Model" (get-in doc [:asset :generator])))
    (is (= 0x46546c67 (support/uint32 bytes 0)))
    (is (= (alength bytes) (support/uint32 bytes 8)))
    (is (= "image/png" (get-in doc [:images 0 :mimeType])))
    (is (= "OPAQUE" (get-in doc [:materials 0 :alphaMode])))
    (is (integer? (get-in doc [:meshes 0 :primitives 0 :attributes :TEXCOORD_0])))
    (is (integer? (get-in doc [:meshes 0 :primitives 0 :attributes :NORMAL])))
    (let [image (support/glb-image bytes 0)]
      (is (> (:width image) 1) "Transparent layers still use the compositor")
      (is (= [187 0 188 255] ((:pixel image) 0 0)) "Source-over blending stays linear in the exported PNG"))
    (is (= 2 (:layers (m/model-info result))))))

(deftest single-decal-exports-original-uvs-and-source-resolution
  (let [result (-> (m/cube 10 10 2 true) m/model (m/color [0 1 0 1])
                   (m/texture red-blue :origin [0 0 1] :normal [0 0 1] :size [3 2]
                              :pixel-size 0.25 :depth-map [[1 1] [1 1]]
                              :depth-scale 0.3 :depth-boundary :step))
        bytes (m/export-model result nil) doc (support/glb-json bytes)
        primitives (get-in doc [:meshes 0 :primitives])
        image (support/glb-image bytes 0)
        textured (first (filter #(get-in % [:attributes :TEXCOORD_0]) primitives))
        untextured (first (remove #(get-in % [:attributes :TEXCOORD_0]) primitives))
        uvs (support/glb-accessor bytes (get-in textured [:attributes :TEXCOORD_0]))]
    (is (= [2 1] [(:width image) (:height image)]) "No tessellation-sized image baking")
    (is (= [255 0 0 255] ((:pixel image) 0 0)))
    (is (= [0 0 255 255] ((:pixel image) 1 0)))
    (is (= 2 (count primitives)))
    (is (support/numeric= [0 1 0 1] (get-in doc [:materials (:material untextured) :pbrMetallicRoughness :baseColorFactor])))
    (is (every? #(<= -1e-6 % (+ 1 1e-6)) uvs))
    (is (some #(near? 0 %) uvs))
    (is (some #(near? 1 %) uvs))
    (is (= (* 3 (support/triangle-count result))
           (reduce + (map #(get-in doc [:accessors (:indices %) :count]) primitives))))
    (is (every? #(some? (get-in % [:attributes :NORMAL])) primitives))
    (is (= 1 (:layers (m/model-info result))))))

(deftest opaque-decals-and-boolean-materials-do-not-need-an-atlas
  (let [source (-> (m/cube 10 10 2 true) m/model (m/color [0 1 0 1]))]
    (doseq [[centers width] [[[-2 2] 2] [[-1 1] 4]]]
      (let [result (-> source
                       (m/texture red :origin [(first centers) 0 1] :normal [0 0 1]
                                  :size [width 2] :pixel-size 0.5)
                       (m/texture blue :origin [(second centers) 0 1] :normal [0 0 1]
                                  :size [width 2] :pixel-size 0.5))
            bytes (m/export-model result nil) doc (support/glb-json bytes)]
        (is (= 2 (count (:images doc))))
        (is (= #{[255 0 0 255] [0 0 255 255]}
               (set (for [i (range 2)] (let [image (support/glb-image bytes i)]
                                        (is (= [1 1] [(:width image) (:height image)]))
                                        ((:pixel image) 0 0))))))
        (is (= 3 (count (get-in doc [:meshes 0 :primitives]))))))
    (let [body (m/texture source red :mapping :planar)
          cutter (-> (m/cube 2 2 4 true) (m/texture blue :mapping :planar))
          bytes (m/export-model (m/difference body cutter) nil)
          doc (support/glb-json bytes)]
      (is (= 2 (count (:images doc))))
      (is (= 2 (count (get-in doc [:meshes 0 :primitives])))))))

(deftest solid-and-vertex-colored-models-export-without-images
  (doseq [result [(-> (m/cube 2 2 2) m/model (m/color [0.25 0.5 0.75 1]))
                  (-> (m/cube 2 2 2) (m/color [0.25 0.5 0.75 1]) m/model)]]
    (let [bytes (m/export-model result nil) doc (support/glb-json bytes)
          primitive (get-in doc [:meshes 0 :primitives 0])]
      (is (empty? (:images doc)))
      (is (< (alength bytes) 8000))
      (is (nil? (get-in primitive [:attributes :TEXCOORD_0])))
      (if-let [color-index (get-in primitive [:attributes :COLOR_0])]
        (is (every? #(support/numeric= [0.25 0.5 0.75 1] %) (partition 4 (support/glb-accessor bytes color-index))))
        (is (support/numeric= [0.25 0.5 0.75 1] (get-in doc [:materials 0 :pbrMetallicRoughness :baseColorFactor])))))))

(deftest repeat-filtering-matches-the-direct-gltf-sampler
  (let [result (m/texture (m/cube 2 2 2) red-blue :mapping :planar :scale 0)
        doc (support/glb-json (m/export-model result nil))]
    (is (every? #(every? true? (map near? % [0.5 0 0.5 1])) (colors result)))
    (is (= 10497 (get-in doc [:samplers (get-in doc [:textures 0 :sampler]) :wrapS])))))

(deftest failures-do-not-modify-models
  (let [source (m/model (m/cube 3 3 3))]
    (doseq [opts [[:mapping :unknown] [:mapping :geodesic] [:mapping :planar :opacity -1]
                  [:mapping :planar :axes [:x :x]] [:origin [0 0 0] :size [0 1]]]]
      (is (thrown? #?(:clj Exception :cljs js/Error) (apply m/texture source red opts))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (m/export-model source nil :tile-size 0)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (m/export-model source nil :tile-size 4.5)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (m/export-model source nil :color [1 0 0])))
    (is (thrown? #?(:clj Exception :cljs js/Error) (m/hull source)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (m/trim-by-plane source [0 0 1])))
    ;; JVM scenes now retain Model appearance; the CLJS exporter is a follow-up.
    #?(:clj (is (seq (:meshes (support/glb-json
                              (support/scene-bytes (animation/scene {:nodes [{:id :model :geometry source}]}))))))
       :cljs (is (thrown? js/Error
                         (support/scene-bytes (animation/scene {:nodes [{:id :model :geometry source}]})))))
    (is (= 0 (:layers (m/model-info source))))))
