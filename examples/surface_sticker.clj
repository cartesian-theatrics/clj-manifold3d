(ns surface-sticker
  "REPL examples for native surface walking and boolean-compatible UVs."
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.texture :as texture]))

(def atlas "resources/images/american-flag-atlas-light.png")

(defn sticker
  ([shape origin normal right]
   (sticker shape origin normal right [6 4]))
  ([shape origin normal right size]
   (texture/geodesic-uv shape
     :origin origin :normal normal :u-direction right
     :size size :pixel-size 0.1
     :uv-rect [0.25 (/ 1.0 3) 0.75 (/ 2.0 3)]
     :outside-uv [0.05 0.05] :prop-index 3)))

(defn donut
  ([] (donut [6 4]))
  ([size]
   (let [torus (-> (m/circle 3 64)
                   (m/translate [7 0])
                   (m/revolve 128))
         diagonal (/ 3.0 (Math/sqrt 2.0))]
     ;; Place the sticker halfway between the outside wall and top of the tube.
     (sticker torus [(+ 7 diagonal) 0 diagonal] [1 0 1] [0 1 0] size))))

(defn models []
  (let [sphere (sticker (m/sphere 10 96) [0 0 10] [0 0 1] [1 0 0])
        cutter (m/translate (m/cylinder 8 1 1 64 true) [2.6 0 9])]
    {:sphere sphere
     :sphere-boolean (m/difference sphere cutter)
     :donut (donut)
     :cylinder (sticker (m/cylinder 10 5 5 96)
                        [5 0 5] [1 0 0] [0 1 0])}))

(defn donut-cut
  "Both operands have native surface-walk UVs in channels 3/4 of one atlas."
  []
  (let [object (donut [7.5 5])
        diagonal (/ 2.2 (Math/sqrt 2.0))
        cutter (texture/geodesic-uv
                 (m/translate (m/sphere 2.2 64) [9.5 0.5 2.5])
                 ;; Map the cutter's inward-facing side, which becomes the
                 ;; visible bowl after subtraction. Use the striped atlas
                 ;; region and rotate its local frame to distinguish it.
                 :origin [(- 9.5 diagonal) 0.5 (- 2.5 diagonal)]
                 :normal [-1 0 -1] :u-direction [1 0 -1]
                 :size [4 3] :pixel-size 0.1 :prop-index 3
                 :uv-rect [0.52 (/ 1.0 3) 0.74 (/ 2.0 3)]
                 :outside-uv [0.05 0.05])]
    {:object object :cutter cutter :result (m/difference object cutter)}))

(defn export! [models]
  (into {}
        (map (fn [[name model]]
               [name (texture/export-glb model
                       (str "surface-walk-" (clojure.core/name name) ".glb")
                       atlas)]))
        models))

(comment
  (def examples (models))
  (export! examples)
  (export! {:donut-large (donut [7.5 5])})
  (def cut (donut-cut))
  (export! {:donut-dual-uv (:result cut) :cutter-uv (:cutter cut)})
  ;; f3d --camera-position=0,-14,48 --camera-view-up=0,1,0 surface-walk-sphere.glb
  )
