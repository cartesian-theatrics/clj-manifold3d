(ns surface-depth
  "Surface-normal relief using the same native chart and UV properties."
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.texture :as texture]
            [surface-sticker :as sticker]))

(defn ripple-grid
  "A normalized height grid; rows run from image top to bottom."
  [columns rows]
  (mapv (fn [y]
          (mapv (fn [x]
                  (let [u (/ (double x) (dec columns))
                        v (/ (double y) (dec rows))]
                    (if (or (zero? x) (zero? y) (= x (dec columns)) (= y (dec rows)))
                      0.0
                      (* (Math/sin (* Math/PI u))
                         (Math/sin (* Math/PI v))
                         (+ 0.65 (* 0.35 (Math/cos (* 6 Math/PI u))))))))
                (range columns)))
        (range rows)))

(defn donut
  "Positive amplitude embosses; negative amplitude engraves."
  ([amplitude] (donut (ripple-grid 49 33) amplitude))
  ([depth-map amplitude] (donut depth-map amplitude :fade))
  ([depth-map amplitude boundary]
   (let [shape (-> (m/circle 3 64) (m/translate [7 0]) (m/revolve 128))
         diagonal (/ 3.0 (Math/sqrt 2.0))]
     (texture/geodesic-uv shape
       :origin [(+ 7 diagonal) 0 diagonal] :normal [1 0 1] :u-direction [0 1 0]
       :size [7.5 5] :pixel-size 0.1
       :uv-rect [0.25 (/ 1.0 3) 0.75 (/ 2.0 3)] :outside-uv [0.05 0.05]
       :prop-index 3 :depth-map depth-map :depth-scale amplitude
       :depth-boundary boundary :depth-fade (if (= :step boundary) 0.0 0.4)))))

(defn concave-bowl
  "A thick spherical bowl with a radius-10 concave inner surface."
  []
  (m/intersection
    (m/difference (m/sphere 12 128) (m/sphere 10 128))
    (m/translate (m/cube 30 30 12 true) [0 0 -6])))

(defn bowl-step
  "Raise a flag into the cavity along its converging surface normals."
  ([height] (bowl-step (concave-bowl) height))
  ([bowl height]
   (texture/geodesic-uv bowl
     :origin [0 0 -10] :normal [0 0 1] :u-direction [1 0 0]
     :size [10.5 7] :pixel-size 0.12
     :uv-rect [0.25 (/ 1.0 3) 0.75 (/ 2.0 3)] :outside-uv [0.05 0.05]
     :prop-index 3 :depth-map [[1 1] [1 1]]
     :depth-scale height :depth-boundary :step)))

(defn inner-square-corner
  "Three perpendicular interior faces meeting at the origin, with 2-unit walls."
  []
  (m/difference (m/translate (m/cube 14 14 14) [-2 -2 -2])
                (m/cube 14 14 14)))

(defn corner-mapping
  "Map across all three faces; optional depth uses an automatic planar miter."
  [corner & depth-options]
  (apply texture/geodesic-uv corner
         :origin [0 0 0] :normal [1 1 1] :u-direction [-1 1 0]
         :size [7.5 5] :pixel-size 0.12
         :uv-rect [0.25 (/ 1.0 3) 0.75 (/ 2.0 3)] :outside-uv [0.05 0.05]
         :prop-index 3 depth-options))

(defn export! [name shape]
  (texture/export-glb shape (str "surface-depth-" (clojure.core/name name) ".glb") sticker/atlas))

(comment
  (def raised (donut 0.5))
  (def engraved (donut -0.4))
  (export! :donut-raised raised)
  (export! :donut-engraved engraved)
  ;; Image decoding, bilinear sampling and all vertex displacement run in C++.
  (def image-relief (donut "resources/images/american-flag-generated.png" 0.05))
  (export! :donut-image image-relief)
  (def stepped (donut [[1 1] [1 1]] 0.35 :step))
  (export! :donut-step stepped)
  (def concave-step (bowl-step 1.0))
  (export! :concave-bowl-step concave-step)
  (def corner (inner-square-corner))
  (export! :inner-square-corner-uv (corner-mapping corner))
  ;; Each face is 0.3 units from its original plane; the corner moves [0.3 0.3 0.3].
  (def corner-step (corner-mapping corner :depth-map [[1 1] [1 1]]
                                  :depth-scale 0.3 :depth-boundary :step))
  (export! :inner-square-corner-step corner-step)
  (export! :inner-square-corner-engraved
           (corner-mapping corner :depth-map [[1 1] [1 1]]
                           :depth-scale -0.3 :depth-boundary :step))
  (export! :inner-square-corner-ripple
           (corner-mapping corner :depth-map (ripple-grid 49 33)
                           :depth-scale 0.3 :depth-fade 0))
  ;; High-contrast image details need shallow relief to pass the triangle-fold guard.
  (export! :inner-square-corner-image
           (corner-mapping corner :depth-map "resources/images/american-flag-generated.png"
                           :depth-scale 0.005 :depth-boundary :step)))
