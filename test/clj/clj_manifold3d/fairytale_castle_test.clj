(ns clj-manifold3d.fairytale-castle-test
  (:require [clojure.test :refer [deftest is]]
            [clj-manifold3d.core :as m]
            [fairytale-castle :as castle]))

(deftest gate-is-a-real-through-opening
  (let [body (:geometry (first (castle/gatehouse)))]
    (is (= :NoError (m/status body)))
    (is (not (m/contains-point? body [0 -26 8])))
    (is (m/contains-point? body [7 -26 8]))))

(deftest bridge-arches-leave-solid-piers
  (let [body (:geometry (first (castle/bridge)))]
    (is (= :NoError (m/status body)))
    (is (not (m/contains-point? body [60 -24 -2])))
    (is (m/contains-point? body [54 -24 -2]))
    (is (m/contains-point? body [60 -24 2]))))

(deftest tower-parts-are-valid-and-windows-are-recessed
  (let [parts (castle/tower {:x 0 :y 0 :radius 5.5 :height 22 :roof-height 15 :flag? true})
        body (:geometry (first parts))]
    (is (> (count parts) 70))
    (is (every? #(= :NoError (m/status (:geometry %))) parts))
    (is (every? #(pos? (:volume (m/get-properties (:geometry %)))) parts))
    (is (not (m/contains-point? body [0 -5.3 12])))
    (is (m/contains-point? body [0 0 12]))))

(deftest final-masonry-cannot-refill-the-window-openings
  (let [parts (castle/tower {:x 0 :y 0 :radius 5.5 :height 22 :roof-height 15})
        nodes (castle/material-nodes parts)
        by-id (into {} (map (juxt :id :geometry) nodes))]
    (is (not (contains? by-id :opening)))
    ;; The 7.8-high stone course used to cross this lower window.
    (doseq [angle [0 60 120 180 240 300] z [11.83 12.2 13.1]
            :let [a (* Math/PI (/ angle 180))
                  turn (fn [[x y z]] [(- (* x (Math/cos a)) (* y (Math/sin a)))
                                     (+ (* x (Math/sin a)) (* y (Math/cos a))) z])
                  origin (turn [0.32 -8 z]) direction (turn [0 1 0])
                  hits (sort-by :distance
                                (keep (fn [[style geometry]]
                                        (when-let [hit (m/ray-cast geometry origin direction :max-distance 4)]
                                          (assoc hit :style style))) by-id))]]
      (is (= :glass (:style (first hits))) (str "Window view blocked at " angle " degrees, z=" z ": " hits)))))
