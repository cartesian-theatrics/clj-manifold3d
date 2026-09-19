(ns clj-manifold3d.parity-models
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.texture :as texture]))

(defn models
  "Identical model programs evaluated by the JVM and optimized CLJS tests."
  []
  (let [cube (m/cube 3 4 5 true)
        sphere (m/sphere 3 32)
        cutter (m/translate (m/cube 4 4 4 true) [1 0.4 0.7])
        section (m/difference (m/square 6 6 true) (m/square 2 2 true))
        corner (m/difference (m/translate (m/cube 14 14 14) [-2 -2 -2]) (m/cube 14 14 14))
        mapping-options [:origin [0 0 0] :normal [1 1 1] :u-direction [-1 1 0]
                         :size [3 2] :pixel-size 0.2 :outside-uv [-1 -1] :prop-index 3]]
    {:cube cube
     :surface-grid (m/surface [[1 2 3] [3 2 1]] 0.5)
     :sphere sphere
     :cylinder (m/cylinder 7 3 1 48 true)
     :union (m/union sphere cutter)
     :difference (m/difference sphere cutter)
     :intersection (m/intersection sphere cutter)
     :extrusion (m/extrude section 4 5 20 [0.8 0.9])
     :torus (m/revolve (m/translate (m/circle 2 32) [5 0]) 48)
     :transform (m/transform cube (-> (m/frame) (m/rotate [0.1 0.2 0.3]) (m/translate [2 3 4])))
     :loft (m/loft section [(m/frame) (m/translate (m/frame) [0 0 5])] :isomorphic)
     :planar (texture/planar-uv-native sphere :axes [:x :z] :scale [0.3 0.2] :offset [1 2])
     :surface (texture/geodesic-uv sphere :origin [0 0 3] :normal [0 0 1]
                                   :size [3 2] :pixel-size 0.2 :outside-uv [-1 -1])
     :corner (apply texture/geodesic-uv corner mapping-options)
     :corner-step (apply texture/geodesic-uv corner
                         :depth-map [[1 1] [1 1]] :depth-scale 0.3 :depth-boundary :step mapping-options)
     :corner-variable (apply texture/geodesic-uv corner
                             :depth-map [[0 0 1] [0 1 1] [0 0 1]] :depth-scale 0.1 :depth-boundary :step mapping-options)}))
