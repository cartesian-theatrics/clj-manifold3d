(ns clj-manifold3d.raptor-3-test
  (:require [clojure.test :refer [deftest is]]
            [clj-manifold3d.core :as m]
            [raptor-3 :as r])
  (:import [org.bytedeco.javacpp PointerScope]))

(deftest nozzle-has-an-open-bore-and-a-visible-stencil
  (with-open [_ (PointerScope.)]
    (binding [r/*segments* 64]
      (let [parts (r/nozzle) jacket (:geometry (first parts))
            stencil (:geometry (first (filter #(= "SN1 nozzle marking" (:name %)) parts)))]
        (doseq [z [1 500 1000 1420 1599]]
          (is (not (m/contains-point? jacket [0 0 z]))))
        (is (m/contains-point? jacket [640 0 4]))
        (is (not (m/contains-point? jacket [625 0 4])))
        (let [shell-hit (m/ray-cast jacket [0 -800 900] [0 1 0])
              paint-hit (m/ray-cast stencil [0 -800 900] [0 1 0])]
          (is (some? shell-hit))
          (is (some? paint-hit))
          (is (< (:distance paint-hit) (:distance shell-hit))))))))

(deftest transported-sweep-is-closed-through-a-bend
  (with-open [_ (PointerScope.)]
    (binding [r/*segments* 64]
      (let [solid (r/tube 10 (r/fair-curve [[0 0 0] [0 0 100] [50 0 150] [150 0 150]]))]
        (is (= :NoError (m/status solid)))
        (is (m/contains-point? solid [0 0 50]))
        (is (m/contains-point? solid [100 0 150]))
        (is (not (m/contains-point? solid [50 30 150])))))))

(deftest fixture-is-optional-and-scene-is-in-metres
  (with-open [_ (PointerScope.)]
    (let [scene (r/assembly {:quality :draft :stand? false})
          by-id (into {} (map (juxt :id identity) (:nodes scene)))]
      (is (= [0.001 0.001 0.001] (get-in by-id [:root :transform :scale])))
      (is (empty? (:children (:transport by-id))))
      (is (> (count (:children (:engine by-id))) 300))
      (is (every? :material (filter :geometry (:nodes scene)))))))
