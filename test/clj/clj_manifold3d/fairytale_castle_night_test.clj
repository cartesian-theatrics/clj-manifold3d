(ns clj-manifold3d.fairytale-castle-night-test
  (:require [clojure.test :refer [deftest is]]
            [clj-manifold3d.core :as m]
            [fairytale-castle :as castle]
            [fairytale-castle-night :as night]))

(def terrain (delay (night/terrain)))
(defn geometry [id] (:geometry (first (filter #(= id (:id %)) @terrain))))

(deftest terrain-is-solid-and-trees-follow-the-surface
  (doseq [{:keys [id geometry]} @terrain]
    (is (= :NoError (m/status geometry)) (name id))
    (is (pos? (:volume (m/get-properties geometry))) (name id)))
  (is (> (night/terrain-height 290 325) 160))
  (with-open [index (m/spatial-index (m/union (geometry :foothills) (geometry :mountain-rock)))]
    (doseq [{:keys [id translation]} @terrain :when (.startsWith (name id) "pine-")
            :let [[x y z] translation
                  hit (m/ray-cast index [x y 300] [0 0 -1])]]
      (is (some? hit))
      (when hit (is (< (Math/abs (- (nth (:position hit) 2) z 0.12)) 0.001))))))

(deftest bridge-meets-a-supported-level-approach
  (let [[body deck] (map :geometry (castle/bridge 9))
        approach (geometry :bridge-approach)]
    (is (= :NoError (m/status body)))
    (is (not (m/contains-point? body [144 -24 -2])) "Last arch remains open")
    (is (m/contains-point? body [138 -24 -2]) "Last pier remains solid")
    (is (pos? (:volume (m/get-properties (m/intersection deck approach)))) "No gap at deck/road join")
    (with-open [index (m/spatial-index (geometry :foothills))]
      (doseq [x (range 100 152 2) y [-28.75 -24 -19.25]
              :let [hit (m/ray-cast index [x y 100] [0 0 -1])]]
        ;; Deck top is 3.55 and rails start at 3.5; land may support the deck
        ;; from inside its thickness, but must remain below its walking face.
        (when hit (is (< (nth (:position hit) 2) 3.5) "Bank must not bury the bridge deck or rails")))
      (doseq [x (range 151 214 2) y [-27.5 -24 -20.5]
              :let [hit (m/ray-cast index [x y 10] [0 0 -1])]]
        (is (some? hit) (str "Missing land under approach at " [x y]))
        (when hit (is (< (Math/abs (- 3.25 (nth (:position hit) 2))) 0.001)))))))
