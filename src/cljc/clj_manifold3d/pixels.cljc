(ns clj-manifold3d.pixels
  "Validated, portable RGBA pixels for synchronous procedural PNG generation.")
(defn dimensions! [width height]
  (when-not (every? #(and (integer? %) (<= 1 % 2048)) [width height])
    (throw (ex-info "Image dimensions must be integers in [1, 2048]" {:width width :height height}))))
(defn rgba8 [pixel]
  (when-not (and (sequential? pixel) (= 4 (count pixel))
                 (every? #(and (number? %) (<= 0 % 1)) pixel))
    (throw (ex-info "Pixel must contain four finite sRGB/alpha values in [0, 1]" {:pixel pixel})))
  (mapv #(int (#?(:clj Math/round :cljs js/Math.round) (double (* 255 %)))) pixel))
