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

(defn- uv-areas [object]
  (let [rows (support/rows object)]
    (mapv (fn [face]
            (let [[[u0 v0] [u1 v1] [u2 v2]] (map #(take-last 2 (rows %)) face)]
              (support/abs (- (* (- u1 u0) (- v2 v0)) (* (- v1 v0) (- u2 u0))))))
          (partition 3 (support/mesh-field (m/get-mesh-gl object) "triVerts")))))

(defn- used-samplers [doc]
  (for [primitive (mapcat :primitives (:meshes doc))
        :let [texture (get-in doc [:materials (:material primitive) :pbrMetallicRoughness :baseColorTexture :index])]
        :when (some? texture)]
    (get-in doc [:samplers (get-in doc [:textures texture :sampler])])))

(deftest whole-surface-box-mapping-keeps-solids-and-covers-every-face
  (doseq [source [(m/cube 4 6 8 true)
                  (m/sphere 3 24)
                  (m/revolve (m/translate (m/circle 1 12) [3 0]) 24)
                  (m/difference (m/cube 6 6 6 true) (m/translate (m/cube 5 5 5 true) [2 2 2]))
                  (m/union (m/cube 2 2 2) (m/translate (m/cube 2 2 2) [5 0 0]))]]
    (let [result (m/texture-all source red-blue :size [2 3])
          bytes (m/export-model result nil) doc (support/glb-json bytes)]
      (is (m/model? result))
      (is (= :NoError (m/status result)))
      (is (near? (:volume (m/get-properties source)) (:volume (m/get-properties result))))
      (is (support/numeric= (support/bounds source) (support/bounds result)))
      (is (= (support/triangle-count source) (support/triangle-count result)))
      (is (every? #(and (support/finite? %) (pos? %)) (uv-areas result)))
      (is (= 1 (count (:images doc))))
      (is (= [2 1] ((juxt :width :height) (support/glb-image bytes 0))))
      (is (seq (used-samplers doc)))
      (is (every? #(= [10497 10497] ((juxt :wrapS :wrapT) %)) (used-samplers doc)))
      (is (every? #(some? (get-in % [:attributes :TEXCOORD_0])) (mapcat :primitives (:meshes doc)))))))

(deftest whole-surface-box-scale-seams-and-immutable-layers
  (let [source (-> (m/cube 4 4 4 true) (m/color [0 0 1 1]) m/model)
        result (m/texture-all source red :size [2 2] :opacity 0.5)
        remapped (m/texture-all result blue-half :size [4 4] :origin [1 1 1] :scale [-2 2] :offset [0.25 0.5])
        mesh (m/get-mesh-gl result)]
    (is (= 0 (:layers (m/model-info source))))
    (is (= 1 (:layers (m/model-info result))))
    (is (= 2 (:layers (m/model-info remapped))))
    (is (seq (support/mesh-field mesh "mergeFromVert")))
    (is (= 9 (support/mesh-field mesh "numProp")))
    (is (every? #(every? true? (map near? % [0.5 0 0.5 1])) (colors result)))
    ;; Remapping must not destroy either base colors or the first UV pair.
    (is (every? (set (support/rows result)) (map #(subvec % 0 9) (support/rows remapped))))
    (doseq [[_ _ z _ _ _ _ u v] (support/rows result)]
      (is (and (support/finite? u) (support/finite? v) (<= -2 z 2))))
    (let [a (m/texture-all (m/cube 4 4 4 true) red-blue :size [2 2])
          b (m/texture-all (m/cube 4 4 4 true) red-blue :size [4 4] :offset [0.25 0.5])]
      (is (every? true?
                  (map (fn [ra rb]
                         (and (near? (+ 0.25 (* 0.5 (nth ra 3))) (nth rb 3))
                              (near? (+ 0.5 (* 0.5 (nth ra 4))) (nth rb 4))))
                       (support/rows a) (support/rows b)))))))

(deftest whole-surface-atlases-use-native-unwrapping
  (doseq [source [(m/cube 4 4 4 true) (m/sphere 3 16)
                  (m/revolve (m/translate (m/circle 1 8) [3 0]) 16)]
          pack? [true false]]
    (let [result (m/texture-all source red-blue :mapping :unwrap :pack? pack? :scale 0.75)
          doc (support/glb-json (m/export-model result nil))]
      (is (= :NoError (m/status result)))
      (is (near? (:volume (m/get-properties source)) (:volume (m/get-properties result))))
      (is (every? #(and (support/finite? %) (pos? %)) (uv-areas result)))
      (is (= 1 (:layers (m/model-info result))))
      (is (seq (used-samplers doc)))
      (is (every? #(= (if pack? 33071 10497) (:wrapS %)) (used-samplers doc)))
      (when pack? (is (every? #(<= 0 % 1) (mapcat #(take-last 2 %) (support/rows result))))))))

(deftest whole-surface-textures-survive-booleans-and-transforms
  (let [base (m/cube 4 4 4 true)
        left (m/texture-all base red :size [2 2])
        right (-> base (m/texture-all blue :mapping :unwrap) (m/translate [1 0.5 0.25]))]
    (doseq [op [m/union m/difference m/intersection]]
      (let [result (-> (op left right) (m/rotate [10 20 30]) (m/translate [2 3 4]) (m/refine 2))
            samples (colors result)
            doc (support/glb-json (m/export-model result nil))]
        (is (= :NoError (m/status result)))
        (is (= 2 (:layers (m/model-info result)) (:images (m/model-info result))))
        (is (some #(> (nth % 0) 0.99) samples))
        (is (some #(> (nth % 2) 0.99) samples))
        (is (= 2 (count (:images doc))))))))

(deftest local-decals-compose-over-whole-surface-texturing
  (let [base (m/texture-all (m/cube 10 10 2 true) red :size [2 2])
        decal (m/texture base blue :origin [0 0 1] :normal [0 0 1] :size [3 2] :pixel-size 0.5)
        covered (m/texture-all decal red :size [2 2])]
    (is (some #(> (nth % 0) 0.99) (colors decal)))
    (is (some #(> (nth % 2) 0.99) (colors decal)))
    (is (every? #(> (nth % 0) 0.99) (colors covered)))
    (is (= 3 (:layers (m/model-info covered))))
    (is (near? 200 (:volume (m/get-properties covered))))))

(deftest whole-surface-options-fail-before-changing-inputs
  (let [source (m/model (m/cube 2 2 2))]
    (doseq [opts [[:mapping :geodesic] [:size [0 1]] [:scale 0] [:origin [0 0 support/nan]]
                  [:normal [0 0 1]] [:axes [:x :y]] [:uv-rect [0 0 1 1]]
                  [:depth-map [[1 1] [1 1]]] [:pack? false]
                  [:mapping :unwrap :scale [1 2]] [:mapping :unwrap :seam-angle 181]
                  [:mapping :unwrap :padding 0.5] [:mapping :unwrap :pack? nil]
                  [:mapping :unwrap :size [2 2]]]]
      (is (thrown? #?(:clj Exception :cljs js/Error) (apply m/texture-all source red opts))))
    (is (= 0 (:layers (m/model-info source))))
    (is (near? 8 (:volume (m/get-properties source))))))

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
    (is (seq (:meshes (support/glb-json
                       (support/scene-bytes (animation/scene {:nodes [{:id :model :geometry source}]}))))))
    (is (= 0 (:layers (m/model-info source))))))

(deftest animated-scenes-retain-colors-and-distinct-textures
  (let [colored (m/color (m/cube 2 3 4) [0.8 0.2 0.1 1])
        textured (m/texture (m/cube 2 2 2) red-blue :mapping :planar)
        other (m/texture (m/cube 1 1 1) blue :mapping :planar)
        track (animation/keyframes [{:time 0 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}
                                    {:time 1 :translation [1 0 0] :rotation [0 0 1 0] :scale [1 1 1]}])
        value (animation/scene {:nodes [{:id :color :geometry colored}
                                         {:id :texture :geometry textured :translation [4 0 0]}
                                         {:id :other :geometry other :translation [8 0 0]}
                                         {:id :copy :geometry colored :translation [-4 0 0]}
                                         {:id :tint :geometry colored :material {:color [1 1 1 0.5] :roughness 0.3 :metalness 0.4}}]
                                :animations [{:channels [{:node :texture :path :rotation :track track}]}]})
        bytes (support/scene-bytes value) doc (support/glb-json bytes)
        primitive (fn [node] (get-in doc [:meshes (get-in doc [:nodes node :mesh]) :primitives 0]))
        material (fn [node] (get-in doc [:materials (:material (primitive node))]))
        color-values (support/glb-accessor bytes (get-in (primitive 0) [:attributes :COLOR_0]))]
    (is (= 4 (count (:meshes doc))) "Repeated geometry shares its asset, distinct materials do not")
    (is (= (get-in doc [:nodes 0 :mesh]) (get-in doc [:nodes 3 :mesh])))
    (is (every? true? (map near? [0.8 0.2 0.1] (take 3 color-values))))
    (is (every? #(contains? (:attributes (primitive %)) :TEXCOORD_0) [1 2]))
    (is (= 2 (count (:images doc))))
    (doseq [[node expected] [[1 [255 0 0 255]] [2 [0 0 255 255]]]]
      (let [texture (get-in (material node) [:pbrMetallicRoughness :baseColorTexture :index])
            image-index (get-in doc [:textures texture :source])
            image (support/glb-image bytes image-index)]
        (is (= expected ((:pixel image) 0 0)))))
    (is (= [1 1 1 0.5] (get-in (material 4) [:pbrMetallicRoughness :baseColorFactor])))
    (is (= "BLEND" (:alphaMode (material 4))))
    (is (= 0.3 (get-in (material 4) [:pbrMetallicRoughness :roughnessFactor])))
    (is (= 1 (get-in doc [:animations 0 :channels 0 :target :node])))
    (is (= [0.0 1.0] (support/glb-accessor bytes (get-in doc [:animations 0 :samplers 0 :input]))))
    (is (support/numeric= [0 0 0 1 0 0 1 0] (support/glb-accessor bytes (get-in doc [:animations 0 :samplers 0 :output]))))
    (doseq [view (:bufferViews doc)]
      (is (zero? (mod (:byteOffset view 0) 4)))
      (is (<= (+ (:byteOffset view 0) (:byteLength view)) (get-in doc [:buffers 0 :byteLength]))))))
