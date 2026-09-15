(ns clj-manifold3d.texture-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [clj-manifold3d.core :as manifold]
            [clj-manifold3d.texture :as texture])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]))

(defn- about= [a b]
  (< (Math/abs (- (double a) (double b))) 1.0e-4))

(defn- mesh-rows [mesh]
  (let [num-prop (int (.numProp mesh))
        properties (.toFloatArray (.vertProperties mesh))]
    (mapv (fn [vertex-index]
            (let [offset (* vertex-index num-prop)]
          (mapv #(aget properties (+ offset %)) (range num-prop))))
          (range (.NumVert mesh)))))

(defn- uv-area [[_ _ _ u0 v0] [_ _ _ u1 v1] [_ _ _ u2 v2]]
  (* 0.5 (- (* (- u1 u0) (- v2 v0))
            (* (- v1 v0) (- u2 u0)))))

(defn- mesh-triangles [mesh]
  (let [rows (mesh-rows mesh)
        triangles (.toIntArray (.triVerts mesh))]
    (mapv (fn [[a b c]]
            [(nth rows a) (nth rows b) (nth rows c)])
          (partition 3 triangles))))

(defn- glb-json [path]
  (let [bytes (Files/readAllBytes (.toPath (java.io.File. path)))
        buffer (doto (ByteBuffer/wrap bytes)
                 (.order ByteOrder/LITTLE_ENDIAN))
        _magic (.getInt buffer)
        _version (.getInt buffer)
        _length (.getInt buffer)
        json-length (.getInt buffer)
        _chunk-type (.getInt buffer)
        json-bytes (byte-array json-length)]
    (.get buffer json-bytes)
    (json/read-str (String. json-bytes StandardCharsets/UTF_8))))

(deftest uv-properties-survive-boolean-interpolation
  (let [mapping (fn [[x y _]] [(+ 10.0 x) (* 0.5 y)])
        left (texture/uv (manifold/cube 2 2 2 true) mapping :prop-index 3)
        right (texture/uv (manifold/translate (manifold/cube 2 2 2 true)
                                              [0.75 0 0])
                          mapping
                          :prop-index 3)
        result (manifold/difference left right)
        mesh (manifold/get-mesh-gl result)
        rows (mesh-rows mesh)]
    (is (= 5 (.numProp mesh)))
    (is (seq rows))
    (doseq [[x y _ u v] rows]
      (is (about= u (+ 10.0 x)))
      (is (about= v (* 0.5 y))))))

(deftest uv-appends-after-color-properties
  (let [colored (manifold/color (manifold/cube 2 2 2 true) [1 0 0 1])
        textured (texture/uv colored (fn [[x _ z]] [x z]))
        mesh (manifold/get-mesh-gl textured)
        rows (mesh-rows mesh)]
    (is (= 9 (.numProp mesh)))
    (is (every? (fn [row]
                  (and (= [1.0 0.0 0.0 1.0] (subvec row 3 7))
                       (about= (nth row 7) (nth row 0))
                       (about= (nth row 8) (nth row 2))))
                rows))))

(deftest native-planar-uv-properties-survive-boolean
  (let [left (texture/planar-uv-native (manifold/cube 2 2 2 true)
                                       :axes [:x :z]
                                       :scale 0.5
                                       :prop-index 3)
        right (texture/planar-uv-native
               (manifold/translate (manifold/cube 2 2 2 true) [0.75 0 0])
               :axes [:x :z]
               :scale 0.5
               :prop-index 3)
        result (manifold/difference left right)
        mesh (manifold/get-mesh-gl result)]
    (is (= 5 (.numProp mesh)))
    (doseq [[x _ z u v] (mesh-rows mesh)]
      (is (about= u (* 0.5 x)))
      (is (about= v (* 0.5 z))))))

(deftest native-planar-uv-appends-after-color-properties
  (let [textured (texture/planar-uv-native
                  (manifold/color (manifold/cube 2 2 2 true) [1 0 0 1])
                  :axes [:x :z])
        mesh (manifold/get-mesh-gl textured)]
    (is (= 9 (.numProp mesh)))
    (doseq [[x _ z r g b a u v] (mesh-rows mesh)]
      (is (= [1.0 0.0 0.0 1.0] [r g b a]))
      (is (about= u x))
      (is (about= v z)))))

(deftest native-lscm-unwrap-splits-and-packs-charts
  (let [source (manifold/cube 2 2 2 true)
        unwrapped (texture/unwrap-native source
                                         :seam-angle 45.0
                                         :padding 0.02
                                         :prop-index 3)
        mesh (manifold/get-mesh-gl unwrapped)
        rows (mesh-rows mesh)]
    (is (= :NoError (manifold/status unwrapped)))
    (is (= 5 (.numProp mesh)))
    (is (> (.NumVert mesh) (.NumVert (manifold/get-mesh-gl source))))
    (is (every? (fn [[_ _ _ u v]]
                  (and (<= 0.0 u 1.0)
                       (<= 0.0 v 1.0)))
                rows))
    (is (every? #(> (Math/abs (double (apply uv-area %))) 1.0e-8)
                (mesh-triangles mesh)))))

(deftest native-lscm-unwrap-works-on-smooth-closed-surface
  (let [source (manifold/sphere 2 16)
        unwrapped (texture/unwrap-native source :prop-index 3)
        mesh (manifold/get-mesh-gl unwrapped)
        rows (mesh-rows mesh)]
    (is (= :NoError (manifold/status unwrapped)))
    (is (= 5 (.numProp mesh)))
    (is (seq rows))
    (is (every? (fn [[_ _ _ u v]]
                  (and (Double/isFinite (double u))
                       (Double/isFinite (double v))))
                rows))))

(deftest geodesic-uv-develops-a-local-surface-chart
  (let [source (manifold/sphere 2 24)
        mapped (texture/geodesic-uv source
                                    :origin [0 2 0]
                                    :normal [0 1 0]
                                    :u-direction [1 0 0]
                                    :size [1.8 1.2]
                                    :uv-rect [0.25 0.333 0.75 0.667]
                                    :outside-uv [0.05 0.05]
                                    :prop-index 3)
        mesh (manifold/get-mesh-gl mapped)
        rows (mesh-rows mesh)
        patch-rows (filter (fn [[_ _ _ u v]]
                            (or (not (about= u 0.05))
                                (not (about= v 0.05))))
                          rows)]
    (is (= :NoError (manifold/status mapped)))
    (is (= 5 (.numProp mesh)))
    (is (< (.NumTri (manifold/get-mesh-gl source)) (.NumTri mesh)))
    (is (seq patch-rows))
    (is (every? (fn [[_ _ _ u v]]
                  (and (Double/isFinite (double u))
                       (Double/isFinite (double v))))
                rows))
    (is (every? (fn [[_ _ _ u v]]
                  (and (<= 0.0 u 1.0)
                       (<= 0.0 v 1.0)))
                rows))))

(deftest geodesic-uv-properties-survive-boolean
  (let [mapped (fn [object]
                 (texture/geodesic-uv object
                                      :origin [0 0 1]
                                      :normal [0 0 1]
                                      :u-direction [1 0 0]
                                      :size [0.4 0.4]
                                      :outside-uv [0.9 0.9]
                                      :prop-index 3))
        left (mapped (manifold/cube 2 2 2 true))
        right (mapped (manifold/translate (manifold/cube 2 2 2 true)
                                          [0.75 0 0]))
        result (manifold/difference left right)
        mesh (manifold/get-mesh-gl result)]
    (is (= :NoError (manifold/status result)))
    (is (= 5 (.numProp mesh)))
    (is (pos? (.NumTri mesh)))))

(deftest native-surface-walk-preserves-geometry-and-has-a-clean-seam
  (let [source (manifold/sphere 2 24)
        mapped (texture/geodesic-uv-native
                source
                :origin [0 0 2]
                :normal [0 0 1]
                :u-direction [1 0 0]
                :size [1.8 1.2]
                :uv-rect [0.25 0.333 0.75 0.667]
                :outside-uv [0.05 0.05]
                :pixel-size 0.15
                :prop-index 3)
        mapped-mesh (manifold/get-mesh-gl mapped)
        source-mesh (manifold/get-mesh-gl source)
        rows (mesh-rows mapped-mesh)
        patch-rows (filter (fn [[_ _ _ u v]]
                            (or (not (about= u 0.05))
                                (not (about= v 0.05))))
                          rows)
        result (manifold/difference
                mapped
                (manifold/translate (manifold/cube 1.0 1.0 1.0 true)
                                    [0 0 2.0]))
        result-mesh (manifold/get-mesh-gl result)]
    (is (= :NoError (manifold/status mapped)))
    (is (= 5 (.numProp mapped-mesh)))
    (is (> (.NumTri mapped-mesh) (.NumTri source-mesh)))
    (is (about= (.volume source) (.volume mapped)))
    (is (about= (.surfaceArea source) (.surfaceArea mapped)))
    (is (seq patch-rows))
    (is (every? (fn [[_ _ _ u v]]
                  (and (Double/isFinite (double u))
                       (Double/isFinite (double v))))
                rows))
    (is (every? (fn [triangle]
                  (let [outside? (fn [[_ _ _ u v]]
                                   (and (about= u 0.05) (about= v 0.05)))]
                    (or (every? outside? triangle)
                        (not-any? outside? triangle))))
                (mesh-triangles mapped-mesh)))
    (is (about= (* 0.5 (- 0.667 0.333))
                (reduce + (map #(Math/abs (double (apply uv-area %)))
                               (mesh-triangles mapped-mesh)))))
    (is (every? (fn [[_ _ z]] (> z 1.5)) patch-rows))
    (is (= :NoError (manifold/status result)))
    (is (= 5 (.numProp result-mesh)))))

(deftest native-surface-walk-inserts-an-interior-sticker-boundary
  (let [source (manifold/cube 10 10 2 true)
        mapped (texture/geodesic-uv-native source
                 :origin [0.31 0.17 1] :normal [0 0 1]
                 :size [3 2] :pixel-size 0.25
                 :outside-uv [-1 -1])
        rows (mesh-rows (manifold/get-mesh-gl mapped))
        patch (filter #(not (about= -1 (nth % 3))) rows)]
    (is (seq patch))
    (is (about= (.volume source) (.volume mapped)))
    (doseq [[x y z u v] patch]
      (is (about= z 1))
      (is (about= u (/ (+ (- x 0.31) 1.5) 3)))
      (is (about= v (- 1 (/ (+ (- y 0.17) 1) 2)))))
    (is (some #(and (about= (nth % 3) 0) (about= (nth % 4) 0)) patch))
    (is (some #(and (about= (nth % 3) 1) (about= (nth % 4) 1)) patch))))

(deftest native-surface-walk-preserves-color-and-transformed-input
  (let [source (-> (manifold/sphere 2 24)
                   (manifold/scale [1.3 0.8 1])
                   (manifold/translate [3 4 5])
                   (manifold/color [0.2 0.4 0.7 1]))
        mapped (texture/geodesic-uv source
                 :origin [3.1 4.1 7] :normal [0 0 1]
                 :u-direction [1 0.2 0] :size [1.3 0.8] :pixel-size 0.1)
        mesh (manifold/get-mesh-gl mapped)]
    (is (= 9 (.numProp mesh)))
    (is (about= (.volume source) (.volume mapped)))
    (is (every? (fn [row] (every? true? (map about= [0.2 0.4 0.7 1] (subvec row 3 7))))
                (mesh-rows mesh)))))

(deftest native-surface-walk-rejects-invalid-spacing-and-folds
  (let [source (manifold/sphere 2 32)]
    (doseq [spacing [0 -1 Double/NaN]]
      (is (thrown? Exception
                   (texture/geodesic-uv-native source :origin [0 0 2]
                     :normal [0 0 1] :size [1 1] :pixel-size spacing))))
    (is (thrown? Exception
                 (texture/geodesic-uv-native source :origin [0 0 2]
                   :normal [0 0 1] :size [14 1] :pixel-size 0.2)))))

(deftest uv-rejects-invalid-values
  (is (thrown? Exception
               (texture/uv (manifold/cube 1 1 1) (constantly [0]))))
  (is (thrown? Exception
               (texture/uv (manifold/cube 1 1 1) [[0 0]] :prop-index 2)))
  (is (thrown? Exception
               (texture/unwrap-native (manifold/cube 1 1 1)
                                      :seam-angle 181.0)))
  (is (thrown? Exception
               (texture/unwrap-native (manifold/cube 1 1 1)
                                      :padding 0.5))))

(deftest exports-embedded-textured-glb
  (let [file (java.io.File/createTempFile "clj-manifold3d-texture-" ".glb")
        path (.getPath file)
        image "resources/images/colored-manifold.png"
        textured (texture/uv (manifold/cube 2 2 2 true)
                             (fn [[x _ z]] [x z])
                             :prop-index 3)]
    (.deleteOnExit file)
    (texture/export-glb textured path image)
    (let [gltf (glb-json path)
          primitive (get-in gltf ["meshes" 0 "primitives" 0])]
      (is (= "2.0" (get-in gltf ["asset" "version"])))
      (is (contains? (get primitive "attributes") "TEXCOORD_0"))
      (is (= "image/png" (get-in gltf ["images" 0 "mimeType"])))
      (is (= 0 (get-in gltf ["materials" 0 "pbrMetallicRoughness"
                             "baseColorTexture" "index"]))))))
