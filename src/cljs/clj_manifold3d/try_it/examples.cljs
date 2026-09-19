(ns clj-manifold3d.try-it.examples
  (:require [clojure.string :as str]))

(def ^:private flag-drawing
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

(def ^:private wavy-depth-bindings
  "      ;; A depth grid is sampled in the same local UV chart as the flag. The
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

(def ^:private previous-flag-surface
  "
      surface (texture/geodesic-uv (m/sphere 12 128)
                :origin [0 -9.6 7.2] :normal [0 -0.8 0.6]
                :u-direction [1 0 0]
                :size [13.3 7] :pixel-size 0.18
                :uv-rect [0.1 0.1 0.9 0.9]
                :outside-uv [0.02 0.02]
                :depth-map [[1 1] [1 1]]
                :depth-scale 0.65
                :depth-boundary :step)]

  ;; The playground embeds this image in the downloadable GLB.
  {:geometry surface :texture image})")

(def ^:private flag-surface
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

(def examples
  [{:id "loft" :title "Twisted loft" :description "One hollow profile. Thirteen frames. A little twist."
    :code "(require '[clj-manifold3d.core :as m])\n\n;; Frame rotations are radians; solid rotations are degrees.\n(let [profile (m/difference\n                (m/square 20 20 true)\n                (m/square 14 14 true))\n\n      frames (mapv\n               (fn [i]\n                 (-> (m/frame)\n                     (m/rotate [0 0 (* i 0.10)])\n                     (m/translate [0 0 (* i 3)])))\n               (range 13))]\n\n  ;; Try changing the twist, height, or number of frames.\n  (m/loft profile frames :isomorphic))"}
   {:id "boolean" :title "Boolean cut" :description "Subtract three cylinders from a rounded solid."
    :code "(require '[clj-manifold3d.core :as m])\n\n(let [body (m/intersection\n             (m/cube 24 24 24 true)\n             (m/sphere 16 64))\n      bore (m/cylinder 40 6 6 48 true)]\n  (m/difference\n    body\n    bore\n    (m/rotate bore [90 0 0])\n    (m/rotate bore [0 90 0])))"}
   {:id "depth" :title "American flag · wavy surface UV" :description "Colored geometry → PNG → native UVs, with a two-cycle cloth wave stepped out from the sphere."
    :previous-codes ["(require '[clj-manifold3d.core :as m]\n         '[clj-manifold3d.texture :as texture])\n\n(texture/geodesic-uv\n  (m/sphere 12 64)\n  :origin [0 0 12]\n  :normal [0 0 1]\n  :size [12 9]\n  :pixel-size 0.5\n  :depth-map [[0 0 0 0 0]\n              [0 1 1 1 0]\n              [0 1 2 1 0]\n              [0 1 1 1 0]\n              [0 0 0 0 0]]\n  :depth-scale 1.5\n  :depth-fade 0)"]
    :code (str flag-drawing flag-surface)}
   {:id "animation" :title "Pivot animation" :description "An offset arm, a parent pivot, and three rotation keyframes."
    :previous-codes ["(require '[clj-manifold3d.animation :as animation])\n\n;; Return a scene to preview its animation.\n(animation/pivot-arm-scene\n  {:length 35\n   :width 6\n   :thickness 4\n   :base-radius 8\n   :base-height 5})"]
    :code "(require '[clj-manifold3d.core :as m]
         '[clj-manifold3d.animation :as animation])

(let [length 35
      width 6
      thickness 4
      duration 2                 ;; seconds, out and back
      swing (/ math/pi 2)        ;; 90 degrees in radians

      ;; Rotation is a quaternion [x y z w], here about Z.
      track (animation/keyframes
              (mapv (fn [[fraction angle]]
                      {:time (* duration fraction)
                       :translation [0 0 0]
                       :rotation [0 0 (math/sin (/ angle 2))
                                      (math/cos (/ angle 2))]
                       :scale [1 1 1]})
                    [[0 0] [0.5 swing] [1 0]]))]

  (m/scene
    {:name \"Pivot arm\"
     :nodes [{:id :base
              :name \"Base\"
              :geometry (m/cylinder 5 8 8 32 true)}

             ;; The empty parent rotates about the origin.
             {:id :arm-pivot
              :name \"Arm pivot\"
              :children [:arm]}

             ;; Offset the child's center so its end is at the pivot.
             {:id :arm
              :name \"Arm\"
              :geometry (m/cube length width thickness true)
              :transform {:translation [(/ length 2) 0 0]}}]

     :animations [{:name \"Pivot\"
                   :channels [{:node :arm-pivot
                               :path :rotation
                               :track track}]}]}))"}])

(defn example [id]
  (when-let [example (first (filter #(= id (:id %)) examples))]
    ;; The first live flag draft used an oversized chart. Migrate that exact
    ;; stock source too, while leaving any user modifications alone.
    (cond-> example
      (= id "depth") (update :previous-codes into
                             [(str flag-drawing previous-flag-surface)
                              (str/replace (str flag-drawing previous-flag-surface) ":size [13.3 7]" ":size [17.1 9]")
                              (str/replace (:code example) ":size [13.3 7]" ":size [17.1 9]")]))))

(defn upgrade-code [id code]
  "Upgrade the old flat flag surface while retaining unrelated user edits."
  (if (and (= id "depth")
           (string? code)
           (not (str/includes? code "wave-columns"))
           (str/includes? code ":depth-map [[1 1] [1 1]]"))
    (-> code
        (str/replace "      surface ("
                     (str wavy-depth-bindings "      surface ("))
        (str/replace ":depth-map [[1 1] [1 1]]" ":depth-map flag-depth")
        (str/replace ":depth-scale 0.65" ":depth-scale 0.75"))
    code))
