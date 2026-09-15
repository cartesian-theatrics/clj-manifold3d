(ns clj-manifold3d.boolean-uv-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-manifold3d.core :as m]
            [clj-manifold3d.texture :as texture]
            [clj-manifold3d.uv-audit :as audit]))

(defn- planar-operands []
  {:object (texture/planar-uv-native (m/cube 4 4 4 true)
             :axes [:x :y] :scale [0.07 0.11] :offset [0.2 0.3] :prop-index 3)
   :cutter (-> (texture/planar-uv-native (m/cube 3 3 3 true)
                  :axes [:y :z] :scale [0.13 -0.09] :offset [0.62 0.71] :prop-index 3)
               (m/rotate [17 23 11])
               (m/translate [1.1 0.4 0.6]))})

(defn- assert-preserved [operands result]
  (let [report (audit/report operands result)]
    (is (= :NoError (m/status result)))
    (doseq [[owner stats] report]
      (testing (name owner)
        (is (pos? (:triangles stats)))
        (is (pos? (:new-corners stats)) "Includes newly interpolated intersection vertices")
        (is (zero? (:missing-source stats)) (pr-str stats))
        (is (zero? (:failures stats)) (pr-str stats))
        (is (< (:max-uv-error stats) 2.0e-5))))))

(deftest distinct-operand-uvs-survive-difference-union-and-intersection
  (let [{:keys [object cutter] :as operands} (planar-operands)]
    (doseq [[operation f] [[:difference m/difference]
                           [:union m/union]
                           [:intersection m/intersection]]]
      (testing (name operation)
        (assert-preserved operands (f object cutter))))))

(deftest surface-walk-uvs-survive-on-both-operands
  (let [object (texture/geodesic-uv (m/sphere 3 32)
                 :origin [0 0 3] :normal [0 0 1] :u-direction [1 0 0]
                 :size [3 2] :pixel-size 0.25 :prop-index 3
                 :uv-rect [0 0 0.45 0.45] :outside-uv [-1 -1])
        cutter (texture/geodesic-uv (m/translate (m/sphere 1.4 24) [0.8 0 2.8])
                 :origin [0.8 0 1.4] :normal [0 0 -1] :u-direction [0 1 0]
                 :size [1.8 1.5] :pixel-size 0.2 :prop-index 3
                 :uv-rect [0.55 0.55 1 1] :outside-uv [-2 -2])]
    (assert-preserved {:object object :cutter cutter} (m/difference object cutter))))

(deftest audit-detects-wrong-uvs-with-identical-geometry
  (let [{:keys [object cutter]} (planar-operands)
        result (m/difference object cutter)
        wrong-object (texture/planar-uv-native object
                       :axes [:x :y] :scale 0.3 :offset [8 8] :prop-index 3)
        report (audit/report {:object wrong-object :cutter cutter} result)]
    (is (pos? (get-in report [:object :failures])))
    (is (zero? (get-in report [:cutter :failures])))))
