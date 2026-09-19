(ns clj-manifold3d.portable-builders-test
  (:require #?(:clj [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clj-manifold3d.builders :as b]
            [clj-manifold3d.core :as m]
            [clj-manifold3d.test-support :as support]))

(use-fixtures :each support/with-disposal)

(defn close? [a b]
  (< (support/abs (- a b))
     (* 0.01 (max 1.0 (support/abs a) (support/abs b)))))

(defn volume [object]
  (:volume (m/get-properties object)))

(deftest repeated-scad-etc-patterns
  (let [positions [[-4 -2] [4 -2] [-4 2] [4 2]]
        disks (b/disks-at 1 positions 64)
        plate (b/cut (m/square 12 8 true) disks)
        capsule (b/capsule [-4 0] [4 0] 1 64)]
    (is (close? (* 4 support/pi) (m/area disks)))
    (is (close? (- (* 12 8) (* 4 support/pi)) (m/area plate)))
    (is (close? (+ (* 8 2) support/pi) (m/area capsule)))
    (is (close? (m/area disks) (m/area (b/bolt-pattern 1 8 4 64))))))

(deftest rod-and-tube-geometry
  (let [rod (b/rod-between [1 2 3] [1 2 13] 2 64)
        rods (b/rods-between [[[0 0 0] [0 0 4]]
                              [[10 0 0] [10 0 4]]] 1 64)
        tube (b/tube 10 5 3 64)
        torus (b/torus 6 4 48 96)]
    (is (support/numeric= {:min [-1 0 3] :max [3 4 13]}
                          (support/bounds rod)))
    (is (close? (* support/pi 4 10) (volume rod)))
    (is (close? (* support/pi 2 4) (volume rods)))
    (is (close? (* support/pi (- 25 9) 10) (volume tube)))
    (is (close? (* 2 support/pi support/pi 5) (volume torus)))))

(deftest batched-combinators-and-placement
  (let [one (m/cube [2 2 2])
        moved (b/copies-at one [[0 0 0] [4 0 0]])
        fused (b/fuse moved)
        hulled (b/hull-at (m/circle 1 32) [[-3 0] [3 0]])]
    (is (= 2 (count moved)))
    (is (close? 16 (volume fused)))
    (is (close? (+ (* 6 2) support/pi) (m/area hulled)))
    (is (identical? one (b/fuse one)))
    (is (identical? one (b/cut one)))
    (is (nil? (b/fuse [nil nil])))
    (is (nil? (b/hull nil nil)))))

(deftest invalid-specific-inputs-fail
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (b/rod-between [0 0 0] [0 0 0] 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (b/tube 0 2 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (b/torus 2 2)))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (b/bolt-pattern 1 0 4))))
