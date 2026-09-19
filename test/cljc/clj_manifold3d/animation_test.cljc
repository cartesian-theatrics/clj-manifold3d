(ns clj-manifold3d.animation-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-manifold3d.animation :as animation]))

(def track
  (animation/keyframes
   [{:time 0.0 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}
    {:time 1.0 :translation [10 20 30] :rotation [0 0 1 0] :scale [2 3 4]}]))

(deftest sample-rigid-track
  (testing "translation and scale interpolate"
    (is (= [5.0 10.0 15.0] (:translation (animation/sample track 0.5))))
    (is (= [1.5 2.0 2.5] (:scale (animation/sample track 0.5)))))
  (testing "quaternion interpolation remains normalized"
    (is (< (Math/abs (- 1.0
                        (Math/sqrt (reduce + (map #(* % %) (:rotation (animation/sample track 0.5)))))))
           1.0e-9)))
  (testing "outside times clamp"
    (is (= [0.0 0.0 0.0] (:translation (animation/sample track -1))))
    (is (= [10.0 20.0 30.0] (:translation (animation/sample track 2))))))

(deftest reject-invalid-keyframes
  (is (thrown? Exception
               (animation/keyframes
                [{:time 0 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}
                 {:time 0 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}])))
  (is (thrown? Exception
               (animation/keyframes
                [{:time 0 :translation [0 0] :rotation [0 0 0 1] :scale [1 1 1]}]))))
