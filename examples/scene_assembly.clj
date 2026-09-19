(ns scene-assembly
  "JVM scene authoring, import/edit/export, and native interference checks.
  clojure -M:clj-dev -m scene-assembly target/scene-assembly.glb"
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.scene :as scene]
            [clojure.java.io :as io]))

(defn assembly []
  (m/scene
   {:name "Moving arm and obstacle"
    :nodes [{:id :pivot :name "Pivot" :children [:arm]}
            {:id :arm :name "Arm"
             :geometry (-> (m/cube 6 0.8 0.8 true) m/model (m/color [0.1 0.5 0.8 1]))
             :translation [3 0 0]
             :material {:metalness 0.5 :roughness 0.3}
             :extras {"partID" "arm-1"}}
            {:id :obstacle :name "Obstacle"
             :geometry (-> (m/cube 1 1 1 true) m/model (m/color [0.9 0.25 0.1 1]))
             :translation [0 4 0]}]
    :animations [{:name "Swing" :channels
                  [{:node :pivot :path :rotation
                    :track [{:time 0 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}
                            {:time 1 :translation [0 0 0] :rotation [0 0 0.7071067811865475 0.7071067811865476]
                             :scale [1 1 1]}]}]}]}))

(defn interference [document]
  ;; Compile/export once, then sample lightweight scene data for each pose.
  ;; Build a reusable BVH for the static part; the moving solid uses its world pose.
  (with-open [obstacle (scene/solid document "Obstacle")
              index (m/spatial-index obstacle)]
    (mapv (fn [t]
            (with-open [arm (-> document (scene/sample-scene t) (scene/solid "Arm"))]
              {:time t :overlap? (m/overlap? index arm)})) [0 0.5 1])))

(defn -main [& [filename]]
  (let [filename (or filename "target/scene-assembly.glb")
        document (-> (assembly)
                     (m/export-scene nil)
                     m/import-scene
                     (scene/update-node "Arm" update :extras assoc "inspected" true))]
    (io/make-parents filename)
    (m/export-model document filename)
    (println filename)
    (println (interference document))))
