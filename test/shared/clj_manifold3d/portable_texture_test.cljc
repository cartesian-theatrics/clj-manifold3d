(ns clj-manifold3d.portable-texture-test
  (:require #?(:clj [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clj-manifold3d.core :as m]
            [clj-manifold3d.test-support :as support]
            [clj-manifold3d.texture :as texture]
            [clj-manifold3d.portable-uv-audit :as audit]))

(use-fixtures :each support/with-disposal)
(defn close? [a b] (< (support/abs (- a b)) 2e-5))
(def rows support/rows)
(defn flat [object & options]
  (apply texture/geodesic-uv object :origin [0 0 1] :normal [0 0 1] :size [3 2]
         :pixel-size 0.25 :outside-uv [-1 -1] options))
(defn corner [] (m/difference (m/translate (m/cube 14 14 14) [-2 -2 -2]) (m/cube 14 14 14)))
(defn corner-patch [source & options]
  (apply texture/geodesic-uv source :origin [0 0 0] :normal [1 1 1] :u-direction [-1 1 0]
         :size [3 2] :pixel-size 0.2 :outside-uv [-1 -1] :prop-index 3 options))

(deftest procedural-images-export-identical-pixels
  (let [png (texture/image 3 2 (fn [x y] [(/ x 2.0) y 0.25 1]))
        shape (m/texture-all (m/cube 1 1 1) png)
        bytes (support/scene-bytes (m/scene {:nodes [{:id :image :geometry shape}]}))
        {:keys [width height pixel]} (support/glb-image bytes 0)]
    (is (= [3 2] [width height]))
    (is (= [0 0 64 255] (pixel 0 0)))
    (is (= [128 255 64 255] (pixel 1 1)))
    (is (= [255 255 64 255] (pixel 2 1))))
  (doseq [[w h f] [[0 1 (constantly [0 0 0 1])]
                    [2049 1 (constantly [0 0 0 1])]
                    [1 1 (constantly [2 0 0 1])]
                    [1 1 (constantly [0 0 0])]
                    [1 1 (constantly [support/nan 0 0 1])]]]
    (is (thrown? #?(:clj Exception :cljs js/Error) (texture/image w h f)))))

(deftest planar-native-and-callback-uv-are-equivalent
  (let [source (m/cube 3 4 5)
        a (texture/planar-uv-native source :axes [:x :z] :scale [0.2 0.3] :offset [4 5])
        b (texture/uv source (fn [[x _ z]] [(+ 4 (* 0.2 x)) (+ 5 (* 0.3 z))]))]
    (is (support/numeric= (sort (rows a)) (sort (rows b))))))

(deftest uv-appends-after-color-without-changing-the-source
  (let [source (m/color (m/cube 2 2 2) [0.2 0.4 0.6 1])
        mapped (texture/planar-uv-native source)]
    (is (support/numeric= 7 (support/mesh-field (m/get-mesh-gl source) "numProp")))
    (is (support/numeric= 9 (support/mesh-field (m/get-mesh-gl mapped) "numProp")))
    (doseq [[x _ z r g b a u v] (rows mapped)]
      (is (every? true? (map close? [r g b a u v] [0.2 0.4 0.6 1 x z]))))))

(deftest both-operands-keep-affine-uvs-through-all-booleans
  (let [left (texture/planar-uv-native (m/cube 3 3 3 true) :axes [:x :z] :offset [2 3])
        right (texture/planar-uv-native (m/translate (m/cube 3 3 3 true) [1 0.4 0.3])
                                       :axes [:x :z] :offset [7 8])]
    (doseq [op [m/union m/difference m/intersection]]
      (let [result (op left right) vertices (rows result)]
        (is (support/numeric= :NoError (m/status result)))
        (is (some (fn [[x _ _ u]] (close? 2 (- u x))) vertices))
        (is (some (fn [[x _ _ u]] (close? 7 (- u x))) vertices))
        (doseq [[x _ z u v] vertices]
          (is (or (and (close? (+ x 2) u) (close? (+ z 3) v))
                  (and (close? (+ x 7) u) (close? (+ z 8) v)))))))))

#?(:cljs
(deftest coloring-an-existing-uv-seam-preserves-topology
  (let [mapped (corner-patch (corner))
        colored (m/color mapped [0.2 0.4 0.6 1] 5)]
    (is (support/numeric= :NoError (m/status colored)))
    (is (close? (:volume (m/get-properties mapped)) (:volume (m/get-properties colored))))
    (is (support/numeric= (sort (rows mapped)) (sort (map #(subvec % 0 5) (rows colored)))))
    (is (every? #(every? true? (map close? (subvec % 5 9) [0.2 0.4 0.6 1])) (rows colored)))))
)

(deftest corner-miter-and-cutter-retain-barycentric-uvs-through-booleans
  (let [object (corner-patch (corner) :depth-map [[1 1] [1 1]]
                              :depth-scale 0.3 :depth-boundary :step)
        cutter (texture/planar-uv-native
                 (m/translate (m/cube 0.9 0.9 2 true) [0.45 0.45 0.8])
                 :axes [:x :z] :scale [0.3 0.2] :offset [2 3] :prop-index 3)]
    (doseq [operation [m/difference m/union m/intersection]]
      (let [result (operation object cutter)]
        (is (support/numeric= :NoError (m/status result)))
        (doseq [[owner stats] (audit/report {:object object :cutter cutter} result)]
          (testing (name owner)
            (is (pos? (:new-corners stats)))
            (is (zero? (:missing-source stats)) (pr-str stats))
            (is (zero? (:failures stats)) (pr-str stats))
            (is (< (:max-uv-error stats) 2.0e-5))))))))

(deftest zero-depth-and-signed-bilinear-displacement
  (let [source (m/cube 10 10 2 true)]
    (is (support/numeric= (rows (flat source)) (rows (flat source :depth-map [[0 0] [0 0]] :depth-fade 0))))
    (doseq [sign [-1 1]]
      (let [result (flat source :depth-map [[0 0 0] [0 0.4 0] [0 0 0]] :depth-scale sign :depth-fade 0)
            tent #(- 1 (support/abs (- (* 2 %) 1)))]
        (is (support/numeric= :NoError (m/status result)))
        (doseq [[x y z u v] (filter #(<= 0 (nth % 3)) (rows result))]
          (is (close? x (- (* 3 u) 1.5)))
          (is (close? y (- 1 (* 2 v))))
          (is (close? z (+ 1 (* sign 0.4 (tent u) (tent v))))))))))

(deftest stepped-depth-has-correct-swept-volume
  (doseq [height [-0.3 0.3]]
    (let [result (flat (m/cube 10 10 2 true) :depth-map [[1 1] [1 1]]
                       :depth-scale height :depth-boundary :step)]
      (is (support/numeric= :NoError (m/status result)))
      (is (close? (+ 200 (* 6 height)) (:volume (m/get-properties result)))))))

(deftest mitered-inner-corner-keeps-exact-offset-planes
  (let [source (corner)]
    (doseq [height [-0.15 0 0.01 0.3 1]]
      (let [result (corner-patch source :depth-map [[1 1] [1 1]] :depth-scale height :depth-boundary :step)
            patch (filter #(<= 0 (nth % 3)) (rows result))]
        (is (support/numeric= :NoError (m/status result)))
        (is (support/numeric= 0 (support/genus result)))
        (is (seq patch))
        (is (every? #(close? height (apply min (subvec % 0 3))) patch))
        (is (some #(every? (partial close? height) (subvec % 0 3)) patch))))))

(deftest corner-zero-height-stepped-boundary-is-watertight
  (doseq [boundary [:fade :step] sign [-1 1]]
    (let [result (corner-patch (corner) :depth-map [[0 0 1] [0 1 1] [0 0 1]]
                               :depth-scale (* sign 0.1) :depth-boundary boundary)]
      (is (support/numeric= :NoError (m/status result)))
      (is (support/numeric= 0 (support/genus result))))))

(deftest local-surface-mapping-is-not-sphere-specific
  (doseq [[source origin normal size]
          [[(m/sphere 5 32) [0 0 5] [0 0 1] [3 2]]
           [(m/revolve (m/translate (m/circle 2 32) [5 0]) 48) [5 0 2] [0 0 1] [3 2]]
           [(m/intersection (m/difference (m/sphere 6 48) (m/sphere 5 48))
                            (m/translate (m/cube 16 16 6 true) [0 0 -3])) [0 0 -5] [0 0 1] [3 2]]]]
    (let [mapped (texture/geodesic-uv source :origin origin :normal normal :size size :pixel-size 0.2
                                     :depth-map [[1 1] [1 1]] :depth-scale 0.2 :depth-boundary :step)]
      (is (support/numeric= :NoError (m/status mapped)))
      (is (> (:volume (m/get-properties mapped)) (:volume (m/get-properties source)))))))

(deftest native-unwrap-retains-physical-seams
  (let [source (m/sphere 3 16) mapped (texture/unwrap-native source)]
    (is (support/numeric= :NoError (m/status mapped)))
    (is (close? (:volume (m/get-properties source)) (:volume (m/get-properties mapped))))
    (doseq [[_ _ _ u v] (rows mapped)]
      (is (<= 0 u 1)) (is (<= 0 v 1)))))

(deftest invalid-inputs-fail-without-corrupting-the-source
  (let [source (m/cube 10 10 2 true)]
    (doseq [options [[:depth-map []] [:depth-map [[0]]] [:depth-map [[0 0] [0]]]
                     [:depth-map [[1 1] [1 1]] :depth-fade 0]
                     [:depth-map [[1 1] [1 1]] :depth-boundary :step :depth-fade 0.2]
                     [:depth-map [[0 0] [0 support/nan]]]
                     [:depth-scale 1] [:unknown true] [:size [0 1]]]]
      (is (thrown? #?(:clj Exception :cljs js/Error) (apply flat source options))))
    (is (support/numeric= :NoError (m/status (flat source))))))
