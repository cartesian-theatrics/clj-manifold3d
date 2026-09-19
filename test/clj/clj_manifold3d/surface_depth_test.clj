(ns clj-manifold3d.surface-depth-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-manifold3d.core :as m]
            [clj-manifold3d.texture :as texture]
            [clj-manifold3d.uv-audit :as audit])
  (:import [java.awt.image BufferedImage]
           [javax.imageio ImageIO]))

(defn- close? [a b]
  (< (Math/abs (- (double a) (double b))) 2.0e-6))

(defn- rows [model]
  (let [mesh (m/get-mesh-gl model)
        stride (.numProp mesh)]
    (mapv vec (partition stride (.toFloatArray (.vertProperties mesh))))))

(defn- flat-patch [source & options]
  (apply texture/geodesic-uv source
         :origin [0 0 1] :normal [0 0 1] :u-direction [1 0 0]
         :size [3 2] :pixel-size 0.25 :outside-uv [-1 -1] options))

(deftest signed-depth-is-bilinear-and-normal-to-a-flat-face
  (let [source (m/cube 10 10 2 true)]
    (doseq [sign [-1 1]]
      (let [result (flat-patch source :depth-map [[0 0 0] [0 0.4 0] [0 0 0]]
                               :depth-scale sign :depth-fade 0)
            patch (filter #(<= 0 (nth % 3)) (rows result))]
        (is (= :NoError (m/status result)))
        (is (pos? (* sign (- (.volume result) (.volume source)))))
        (is (seq patch))
        (doseq [[x y z u v] patch]
          (let [tent #(- 1.0 (Math/abs (- (* 2.0 %) 1.0)))]
            (is (close? x (- (* 3 u) 1.5)))
            (is (close? y (- 1 (* 2 v))))
            (is (close? z (+ 1 (* sign 0.4 (tent u) (tent v)))))))
        (is (every? #(or (close? -1 (nth % 2)) (close? 1 (nth % 2)))
                    (filter #(neg? (nth % 3)) (rows result))))))
    (is (close? 200 (.volume source)) "Mapping leaves its input unchanged")))

(deftest zero-depth-is-identical-to-uv-only-mapping
  (let [source (m/cube 10 10 2 true)
        plain (flat-patch source)
        zero (flat-patch source :depth-map [[0 0] [0 0]] :depth-fade 0)]
    (is (= (rows plain) (rows zero)))
    (is (= (.volume plain) (.volume zero)))))

(deftest sharp-steps-close-with-side-walls-for-both-depth-signs
  (let [source (m/cube 10 10 2 true)]
    (doseq [sign [-1 1]]
      (let [result (flat-patch source :depth-map [[1 1] [1 1]]
                               :depth-scale (* sign 0.3) :depth-boundary :step)
            vertices (rows result)
            top (filter #(<= 0 (nth % 3)) vertices)
            side (filter #(and (neg? (nth % 3))
                               (close? (+ 1 (* sign 0.3)) (nth % 2))) vertices)]
        (is (= :NoError (m/status result)))
        (is (close? (+ 200 (* sign 3 2 0.3)) (.volume result)))
        (is (seq top))
        (is (every? #(close? (+ 1 (* sign 0.3)) (nth % 2)) top))
        (is (seq side) "Side-wall UVs use the outside atlas region")
        (is (every? #(or (close? 1.5 (Math/abs (first %)))
                         (close? 1.0 (Math/abs (second %)))) side))
        (is (= :NoError (m/status (m/difference result
                                   (m/translate (m/cube 1 1 4 true) [1 0 1])))))))))

(deftest depth-is-independent-of-atlas-placement-and-preserves-color
  (let [source (m/color (m/cube 10 10 2 true) [0.2 0.4 0.6 1])
        options [:depth-map [[1 1] [1 1]] :depth-scale 0.3 :depth-fade 0.5]
        plain (apply flat-patch source options)
        atlas (apply flat-patch source :uv-rect [0.25 0.2 0.75 0.8] options)
        positions #(set (map (fn [row] (subvec row 0 3)) (rows %)))]
    (is (= (positions plain) (positions atlas)))
    (is (every? #(every? true? (map close? [0.2 0.4 0.6 1] (subvec % 3 7)))
                (rows atlas)))
    (is (close? 1.3 (apply max (map #(nth % 2) (rows atlas)))))
    (is (= 9 (.numProp (m/get-mesh-gl atlas))))))

(defn- depth-image [samples image-type]
  (let [height (count samples), width (count (first samples))
        image (BufferedImage. width height image-type)
        raster (.getRaster image)
        file (java.io.File/createTempFile "manifold-depth-" ".png")]
    (.deleteOnExit file)
    (doseq [y (range height), x (range width)]
      (.setSample raster x y 0 (int (get-in samples [y x]))))
    (ImageIO/write image "png" file)
    (.getPath file)))

(deftest native-depth-image-matches-numeric-grid-including-16-bit-precision
  (doseq [[image-type denominator samples]
          [[BufferedImage/TYPE_BYTE_GRAY 255 [[0 19 41] [51 127 180] [193 211 255]]]
           [BufferedImage/TYPE_USHORT_GRAY 65535
            [[0 4001 8001] [12345 31234 45678] [56789 60001 65535]]]]
          boundary [:fade :step]]
    (let [source (m/cube 10 10 2 true)
          path (depth-image samples image-type)
          numeric (mapv #(mapv (fn [sample] (/ (double sample) denominator)) %) samples)
          options [:depth-scale 0.4 :depth-offset (if (= :step boundary) 0 -0.12)
                   :depth-boundary boundary :depth-fade (if (= :step boundary) 0 0.3)]
          image-result (apply flat-patch source :depth-map path options)
          grid-result (apply flat-patch source :depth-map numeric options)
          expected (sort (rows grid-result)), actual (sort (rows image-result))]
      (is (= :NoError (m/status image-result)))
      (is (= (count expected) (count actual)))
      (is (every? true? (map #(every? true? (map close? %1 %2)) expected actual)))
      (is (close? (.volume grid-result) (.volume image-result))))))

(deftest displaced-operands-preserve-both-uv-charts-through-booleans
  (doseq [boundary [:fade :step]]
    (let [object (texture/geodesic-uv (m/sphere 3 32)
                 :origin [0 0 3] :normal [0 0 1] :size [3 2] :pixel-size 0.25
                 :prop-index 3 :uv-rect [0 0 0.45 0.45] :outside-uv [-1 -1]
                 :depth-map [[1 1] [1 1]] :depth-scale 0.2 :depth-boundary boundary)
        cutter (texture/geodesic-uv (m/translate (m/sphere 1.4 24) [0.8 0 2.8])
                 :origin [0.8 0 1.4] :normal [0 0 -1] :u-direction [0 1 0]
                 :size [1.8 1.5] :pixel-size 0.2 :prop-index 3
                 :uv-rect [0.55 0.55 1 1] :outside-uv [-2 -2]
                 :depth-map [[1 1] [1 1]] :depth-scale -0.1 :depth-boundary boundary)]
    (doseq [operation [m/difference m/union m/intersection]]
      (let [result (operation object cutter)]
        (is (= :NoError (m/status result)))
        (doseq [[owner stats] (audit/report {:object object :cutter cutter} result)]
          (testing (name owner)
            (is (pos? (:new-corners stats)))
            (is (zero? (:missing-source stats)) (pr-str stats))
            (is (zero? (:failures stats)) (pr-str stats))
            (is (< (:max-uv-error stats) 2.0e-5)))))))))

(deftest invalid-depth-inputs-fail-explicitly
  (let [source (m/cube 10 10 2 true)]
    (doseq [options [[:depth-map []] [:depth-map [[0]]] [:depth-map [[0 0] [0]]]
                     [:depth-map [[0 0] [0 Double/NaN]]]
                     [:depth-map "missing-depth-image.png"]
                     [:depth-map [[1 1] [1 1]] :depth-fade 0]
                     [:depth-map [[0 0] [0 0]] :depth-offset 1 :depth-fade 0]
                     [:depth-map [[0 0] [0 0]] :depth-scale Double/POSITIVE_INFINITY]
                     [:depth-map [[0 0] [0 0]] :depth-fade -1]
                     [:depth-map [[1 1] [1 1]] :depth-boundary :step :depth-fade 0.2]
                     [:depth-map [[-1 1] [-1 1]] :depth-boundary :step :depth-offset 0.031]
                     [:depth-map [[0 0] [0 0]] :depth-boundary :unknown]
                     [:depth-scale 0.3]]]
      (is (thrown? Exception (apply flat-patch source options)) (pr-str options)))))

(deftest stepped-boundaries-can-meet-at-zero-height-samples
  (let [result (flat-patch (m/cube 10 10 2 true)
                 :depth-map [[-1 1] [-1 1]] :depth-scale 0.1 :depth-boundary :step)]
    (is (= :NoError (m/status result)))
    (is (close? 200 (.volume result)))))

(deftest excessive-depth-that-folds-the-surface-is-rejected
  (is (thrown-with-msg? Exception #"folds or collapses"
        (texture/geodesic-uv (m/sphere 2 32)
          :origin [0 0 2] :normal [0 0 1] :size [1.5 1.5] :pixel-size 0.15
          :depth-map [[1 1] [1 1]] :depth-scale -2))))

(deftest positive-step-follows-converging-normals-inside-a-concave-bowl
  (let [source (m/intersection
                 (m/difference (m/sphere 6 64) (m/sphere 5 64))
                 (m/translate (m/cube 16 16 6 true) [0 0 -3]))
        result (texture/geodesic-uv source
                 :origin [0 0 -5] :normal [0 0 1] :u-direction [1 0 0]
                 :size [4 3] :pixel-size 0.2 :prop-index 3 :outside-uv [-1 -1]
                 :depth-map [[1 1] [1 1]] :depth-scale 0.4 :depth-boundary :step)
        patch (filter #(<= 0 (nth % 3)) (rows result))
        radius (fn [[x y z]] (Math/sqrt (+ (* x x) (* y y) (* z z))))]
    (is (= :NoError (m/status result)))
    (is (= 0 (.genus source) (.genus result)))
    (is (< (.volume source) (.volume result)))
    (is (seq patch))
    ;; Positive depth decreases the cavity radius, rather than offsetting in
    ;; the fixed chart direction. Allow for the original faceted sphere.
    (is (every? #(<= 4.57 (radius %) 4.6002) patch))
    (is (= :NoError (m/status (m/difference result
                               (m/translate (m/cube 0.8 0.8 3 true) [0.8 0 -4.6])))))))

(defn- inner-corner []
  (m/difference (m/translate (m/cube 14 14 14) [-2 -2 -2])
                (m/cube 14 14 14)))

(defn- corner-patch [source & options]
  (apply texture/geodesic-uv source
         :origin [0 0 0] :normal [1 1 1] :u-direction [-1 1 0]
         :size [3 2] :pixel-size 0.2 :outside-uv [-1 -1]
         :prop-index 3 options))

(deftest sharp-inner-corner-miters-all-three-faces-at-the-requested-depth
  (let [source (inner-corner)
        uv-only (corner-patch source)
        patch (filter #(<= 0 (nth % 3)) (rows uv-only))]
    (is (= :NoError (m/status uv-only)))
    (is (close? (.volume source) (.volume uv-only)))
    (is (= 0 (.genus uv-only)))
    (doseq [[normal-axis a b] [[0 1 2] [1 0 2] [2 0 1]]]
      (is (some #(and (close? 0 (nth % normal-axis))
                       (> (nth % a) 0.05) (> (nth % b) 0.05)) patch)
          "The patch reaches the interior of every incident face"))
    (is (= (rows uv-only) (rows (corner-patch source :depth-map [[1 1] [1 1]]
                                             :depth-scale 0 :depth-boundary :step))))
    (doseq [height [-0.1 0.01 0.3 1.0]]
      (let [result (corner-patch source :depth-map [[1 1] [1 1]]
                                  :depth-scale height :depth-boundary :step)
            top (filter #(<= 0 (nth % 3)) (rows result))]
        (is (= :NoError (m/status result)))
        (is (= 0 (.genus result)))
        (is (pos? (* height (- (.volume result) (.volume source)))))
        (is (seq top))
        ;; The entire patch moves [d d d]. Each original plane is offset by d,
        ;; including the shared corner, regardless of the UV triangulation.
        (is (every? #(close? height (apply min (subvec % 0 3))) top))
        (is (some #(every? (partial close? height) (subvec % 0 3)) top))
        (doseq [[normal-axis a b] [[0 1 2] [1 0 2] [2 0 1]]]
          (is (some #(and (close? height (nth % normal-axis))
                           (> (nth % a) (+ height 0.05))
                           (> (nth % b) (+ height 0.05))) top)))))))

(deftest variable-corner-depth-is-bilinear-and-continuous-across-creases
  (doseq [height [-0.1 0.1]]
    (let [result (corner-patch (inner-corner)
                              :depth-map [[0 0 0] [0 1 0] [0 0 0]]
                              :depth-scale height :depth-fade 0)
          top (filter #(<= 0 (nth % 3)) (rows result))
          tent #(- 1.0 (Math/abs (- (* 2.0 %) 1.0)))]
      (is (= :NoError (m/status result)))
      (is (= 0 (.genus result)))
      (is (seq top))
      (doseq [[x y z u v] top]
        (is (close? (min x y z) (* height (tent u) (tent v))))))))

(deftest corner-depth-images-match-numeric-grids
  (doseq [[image-type denominator]
          [[BufferedImage/TYPE_BYTE_GRAY 255] [BufferedImage/TYPE_USHORT_GRAY 65535]]
          boundary [:fade :step]]
    (let [source (inner-corner)
          samples [[0 0 denominator] [0 denominator denominator] [0 0 denominator]]
          path (depth-image samples image-type)
          numeric (mapv #(mapv (fn [sample] (/ (double sample) denominator)) %) samples)
          options [:depth-scale 0.1 :depth-boundary boundary]
          image-result (apply corner-patch source :depth-map path options)
          grid-result (apply corner-patch source :depth-map numeric options)
          expected (sort (rows grid-result)), actual (sort (rows image-result))]
      (is (= :NoError (m/status image-result)))
      (is (= (count expected) (count actual)))
      (is (every? true? (map #(every? true? (map close? %1 %2)) expected actual)))
      (is (close? (.volume grid-result) (.volume image-result))))))

(deftest corner-miter-and-cutter-retain-uvs-through-booleans
  (let [object (corner-patch (inner-corner) :depth-map [[1 1] [1 1]]
                              :depth-scale 0.3 :depth-boundary :step)
        cutter (texture/planar-uv-native
                 (m/translate (m/cube 0.9 0.9 2 true) [0.45 0.45 0.8])
                 :axes [:x :z] :scale [0.3 0.2] :offset [2 3] :prop-index 3)]
    (doseq [operation [m/difference m/union m/intersection]]
      (let [result (operation object cutter)
            report (audit/report {:object object :cutter cutter} result)]
        (is (= :NoError (m/status result)))
        (doseq [[owner stats] report]
          (testing (name owner)
            (is (pos? (:new-corners stats)))
            (is (zero? (:missing-source stats)) (pr-str stats))
            (is (zero? (:failures stats)) (pr-str stats))
            (is (< (:max-uv-error stats) 2.0e-5))))))))
