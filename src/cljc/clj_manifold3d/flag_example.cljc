(ns clj-manifold3d.flag-example
  "Shared, runnable American flag surface-UV demonstration source.")

(def drawing
  "(require '[clj-manifold3d.core :as m]
         '[clj-manifold3d.texture :as texture])

;; Draw the flag with real, colored Manifold geometry.
(let [red [0.55 0.012 0.025 1]
      white [1 1 1 1]
      blue [0.008 0.025 0.16 1]
      stripe-height (/ 10 13)
      canton-width 7.6
      canton-height (* 7 stripe-height)

      stripes (for [i (range 0 13 2)]
                (-> (m/cube 19 stripe-height 0.15)
                    (m/translate [0 (* i stripe-height) 0])
                    (m/color red)))

      ;; Ten alternating radii make a five-pointed star.
      star (-> (m/cross-section
                 (mapv (fn [i]
                         (let [a (+ (/ math/pi 2) (* i (/ math/pi 5)))
                               r (if (even? i) 0.3 0.115)]
                           [(* r (math/cos a)) (* r (math/sin a))]))
                       (range 10)))
               (m/extrude 0.1)
               (m/color white))

      ;; Nine rows, alternating six and five: fifty stars.
      stars (for [row (range 9)
                  col (range (if (even? row) 6 5))]
              (m/translate star
                [(* canton-width (/ (+ (if (even? row) 1 2) (* 2 col)) 12))
                 (- 10 (* canton-height (/ (inc row) 10)))
                 0.2]))

      flag (apply m/union
             (concat [(m/color (m/cube 19 10 0.1) white)
                      (-> (m/cube canton-width canton-height 0.25)
                          (m/translate [0 (- 10 canton-height) 0])
                          (m/color blue))]
                     stripes stars))

      ;; Bake from +Z, with a neutral margin for the sphere. The image itself
      ;; stays flat; the native surface mapping below supplies the cloth wave.
      image (texture/bake flag
              :width 950 :height 500
              :bounds [-2.375 -1.25 21.375 11.25]
              :background [0.65 0.7 0.68 1])

      ;; A depth grid is sampled in the same local UV chart as the flag. The
      ;; pole is held steady and the wave grows toward the free edge, with a
      ;; small vertical phase shift so it reads as cloth blowing in the wind.
      wave-columns 33
      wave-rows 17
      wave-depth (fn [column row]
                   (let [u (/ column (dec wave-columns))
                         v (/ row (dec wave-rows))
                         phase (+ (* 4 math/pi u) (* 0.65 math/pi v))
                         amplitude (* u (+ 0.2 (* 0.6 u)))]
                     (+ 1.2 (* amplitude (math/sin phase)))))
      flag-depth (mapv (fn [row]
                         (mapv #(wave-depth % row) (range wave-columns)))
                       (range wave-rows))
")

(def surface
  "
      surface (-> (m/sphere 12 128)
                  m/model
                  (m/color [0.65 0.7 0.68 1])
                  (m/texture image
                    :origin [0 -9.6 7.2] :normal [0 -0.8 0.6]
                    :u-direction [1 0 0]
                    :size [13.3 7] :pixel-size 0.18
                    :uv-rect [0.1 0.1 0.9 0.9]
                    :depth-map flag-depth
                    :depth-scale 0.75
                    :depth-boundary :step))]

  ;; One native value owns the geometry, UVs, colors, and images.
  ;; Continue threading: texture, transform, boolean, or export-model.
  surface)")

(def source (str drawing surface))

