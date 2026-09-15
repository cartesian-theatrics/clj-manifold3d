(ns clj-manifold3d.split-lifetime-test
  (:require [clojure.test :refer [deftest is]]
            [clj-manifold3d.core :as m]))

(deftest split-results-own-their-native-solids
  ;; The API now closes its temporary native pair before returning. Returned
  ;; copies must remain usable across collection and subsequent transforms.
  (let [cube (m/cube [10 10 10])
        parts (vec (concat (m/split-by-plane cube [1 0 0] 5)
                           (m/split cube (m/cube [5 10 10]))))]
    (System/gc)
    (doseq [part parts]
      (let [moved (m/translate part [30 20 10])]
        (is (= :NoError (m/status moved)))
        (is (< (Math/abs (- 500 (:volume (m/get-properties moved)))) 1.0e-6))))))
