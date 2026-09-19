(ns clj-manifold3d.raster
  "Unlit, orthographic +Z texture baking of opaque, vertex-colored geometry.
  Works in Node and browser workers without a canvas or GPU."
  (:require [clj-manifold3d.glb :as glb]))

(defn- finite? [x] (and (number? x) (js/Number.isFinite x)))
(defn- srgb-byte [linear]
  (let [x (max 0 (min 1 linear))]
    (js/Math.round (* 255 (if (<= x 0.0031308) (* 12.92 x)
                            (- (* 1.055 (js/Math.pow x (/ 1 2.4))) 0.055))))))

(defn render
  "Snapshot colored Manifold/MeshGL as {:width :height :data RGBA bytes}.
  :bounds is [xmin ymin xmax ymax]; +Y is image-up. Colors are linear RGBA
  properties (absolute :color-index, default 3), encoded as sRGB. A Z buffer
  picks the nearest surface, with barycentric interpolation of vertex colors.
  Opaque input only; no lighting or antialiasing."
  [artwork & {:keys [width height bounds background color-index]
             :or {width 512 height 512 background [1 1 1 1] color-index 3}}]
  (let [{:keys [rows indices num-prop] minimum :min maximum :max} (glb/mesh-data artwork)
        [xmin ymin xmax ymax :as bounds] (or bounds [(minimum 0) (minimum 1) (maximum 0) (maximum 1)])]
    (when-not (and (integer? width) (integer? height) (<= 1 width 2048) (<= 1 height 2048))
      (throw (ex-info "Texture dimensions must be integers from 1 to 2048" {})))
    (when-not (and (= 4 (count bounds)) (every? finite? bounds) (< xmin xmax) (< ymin ymax))
      (throw (ex-info "Texture bounds must be finite [xmin ymin xmax ymax] with positive area" {})))
    (when-not (and (integer? color-index) (<= 3 color-index) (<= (+ color-index 4) num-prop))
      (throw (ex-info "Texture artwork requires RGBA properties; use m/color first" {})))
    (when-not (and (= 4 (count background)) (every? finite? background)
                   (= 1 (last background))
                   (every? (fn [row] (let [rgba (subvec row color-index (+ color-index 4))]
                                      (and (every? finite? rgba) (= 1 (last rgba))))) rows))
      (throw (ex-info "Texture baking supports finite, opaque RGBA colors only" {})))
    (let [pixels (* width height) data (js/Uint8Array. (* 4 pixels))
          depths (js/Float64Array. pixels)
          background (conj (mapv srgb-byte (take 3 background)) 255)
          vertices (mapv (fn [row]
                           (into [(* width (/ (- (row 0) xmin) (- xmax xmin)))
                                  (* height (/ (- ymax (row 1)) (- ymax ymin)))
                                  (row 2)]
                                 (subvec row color-index (+ color-index 3)))) rows)]
      (.fill depths js/Number.NEGATIVE_INFINITY)
      (dotimes [pixel pixels]
        (dotimes [channel 4] (aset data (+ (* pixel 4) channel) (background channel))))
      ;; Mutation is confined to these fresh pixel/depth buffers; inputs stay immutable.
      (doseq [[ia ib ic] (partition 3 indices)]
        (let [[ax ay az :as a] (vertices ia) [bx by bz :as b] (vertices ib) [cx cy cz :as c] (vertices ic)
              denominator (+ (* (- by cy) (- ax cx)) (* (- cx bx) (- ay cy)))]
          (when (> (js/Math.abs denominator) 1e-12)
            (let [left (max 0 (js/Math.floor (min ax bx cx)))
                  right (min (dec width) (js/Math.ceil (max ax bx cx)))
                  top (max 0 (js/Math.floor (min ay by cy)))
                  bottom (min (dec height) (js/Math.ceil (max ay by cy)))]
              (doseq [y (range top (inc bottom)) x (range left (inc right))]
                (let [px (- (+ x 0.5) cx) py (- (+ y 0.5) cy)
                      u (/ (+ (* (- by cy) px) (* (- cx bx) py)) denominator)
                      v (/ (+ (* (- cy ay) px) (* (- ax cx) py)) denominator)
                      w (- 1 u v) pixel (+ x (* y width)) z (+ (* u az) (* v bz) (* w cz))]
                  (when (and (>= u -1e-9) (>= v -1e-9) (>= w -1e-9) (> z (aget depths pixel)))
                    (aset depths pixel z)
                    (dotimes [channel 3]
                      (aset data (+ (* pixel 4) channel)
                            (srgb-byte (+ (* u (a (+ 3 channel))) (* v (b (+ 3 channel))) (* w (c (+ 3 channel))))))))))))))
      {:width width :height height :data data})))
