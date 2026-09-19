(ns clj-manifold3d.parity-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clj-manifold3d.core :as m]
            [clj-manifold3d.runtime :as rt]
            [clj-manifold3d.parity-models :as models]
            [clj-manifold3d.fixtures :as fixtures]
            [clj-manifold3d.portable-texture-test :refer [rows]]
            [goog.object :as gobj]))

(use-fixtures :each m/with-disposal)
(defn close? [a b] (< (js/Math.abs (- a b)) (* 2e-5 (max 1 (js/Math.abs a)))))
(defn uv-area [shape]
  (let [vertices (rows shape) indices (gobj/get (m/get-mesh-gl shape) "triVerts")]
    (reduce + (for [[a b c] (partition 3 (array-seq indices))
                    :let [[_ _ _ au av] (vertices a) [_ _ _ bu bv] (vertices b) [_ _ _ cu cv] (vertices c)]]
                (/ (js/Math.abs (- (* (- bu au) (- cv av)) (* (- bv av) (- cu au)))) 2)))))

(deftest jvm-and-wasm-evaluate-identical-model-programs
  (let [reference (:parity @fixtures/data) actual (models/models)]
    (is (= (set (keys reference)) (set (keys actual))))
    (doseq [[label shape] actual]
      (testing (name label)
        (let [expected (get reference label) bounds (m/bounds shape)]
          (is (= :NoError (m/status shape)))
          (is (= (:genus expected) (rt/call shape "genus")))
          (is (= (:property-width expected) (gobj/get (m/get-mesh-gl shape) "numProp")))
          (doseq [property [:volume :surface-area]]
            (is (close? (get-in expected [:properties property]) (get (m/get-properties shape) property))))
          (doseq [bound [:min :max]]
            (is (every? true? (map close? (get expected bound) (get bounds bound)))))
          (when-let [area (:uv-area expected)] (is (close? area (uv-area shape)))))))))
