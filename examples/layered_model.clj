(ns layered-model
  (:require [clj-manifold3d.core :as m]))

(defn build
  "Two surface decals and a separately textured cutter, all in one native value."
  []
  (let [flag "resources/images/american-flag-generated.png"
        cutter (-> (m/cube 8 8 30 true)
                   (m/texture flag :mapping :planar :axes [:x :z] :scale 0.15)
                   (m/translate [10 0 0]))]
    (-> (m/sphere 12 96)
        m/model
        (m/color [0.65 0.7 0.68 1])
        (m/texture flag :name "Raised front flag"
                   :origin [0 -9.6 7.2] :normal [0 -0.8 0.6]
                   :u-direction [1 0 0] :size [13.3 7] :pixel-size 0.25
                   :depth-map [[1 1] [1 1]] :depth-scale 0.4
                   :depth-boundary :step)
        (m/texture flag :name "Back flag"
                   :origin [0 9.6 7.2] :normal [0 0.8 0.6]
                   :u-direction [-1 0 0] :size [9.5 5] :pixel-size 0.25)
        (m/difference cutter))))

(comment
  (def result (build))
  (m/model-info result) ; => {:layers 3, :images 3, ...}
  (m/export-model result "target/layered-model.glb"))
